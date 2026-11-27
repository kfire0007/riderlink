package com.riderlink.intercom.network

import android.content.Context
import android.util.Log
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.QrPayload
import com.riderlink.intercom.protocol.SenderId
import com.riderlink.intercom.protocol.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress

/**
 * Coordinates LocalOnlyHotspot / WifiNetworkSpecifier / Manual LAN connectors
 * with UdpAudioTransport and exposes live ConnectionState + TransportStats.
 */
class ConnectionController(
    context: Context,
    private val scope: CoroutineScope,
    private val transportListener: UdpAudioTransport.Listener
) {
    private val hotspotHost = LocalHotspotHost(context, scope)
    private val wifiClient = WifiSpecifierClient(context)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _transportStats = MutableStateFlow(TransportStats())
    val transportStats: StateFlow<TransportStats> = _transportStats.asStateFlow()

    @Volatile
    var activeMode: TransportMode? = null
        private set

    @Volatile
    var transport: UdpAudioTransport? = null
        private set

    @Volatile
    private var webBridgeIp: String? = null

    @Volatile
    private var webBridgeConnected: Boolean = false

    @Volatile
    private var webBridgeRttMs: Long = 0L

    @Volatile
    private var webBridgeAudioRx: Long = 0L

    fun startHostHotspot() {
        stop()
        activeMode = TransportMode.LOCAL_ONLY_HOTSPOT
        _connectionState.value = ConnectionState.StartingHotspot

        val token = SessionToken.generate()
        hotspotHost.start(
            onStarted = { creds ->
                startHostUdp(
                    hostIp = creds.hostIp,
                    ssid = creds.ssid,
                    passphrase = creds.passphrase,
                    sessionToken = token,
                    mode = TransportMode.LOCAL_ONLY_HOTSPOT
                )
            },
            onError = { err ->
                _connectionState.value = ConnectionState.Error(err)
            },
            onStoppedUnexpectedly = {
                if (_connectionState.value is ConnectionState.HostReady) {
                    transport?.stop()
                    transport = null
                    _connectionState.value = ConnectionState.Error("Local hotspot stopped by Android. Tap Try again.")
                }
            }
        )
    }

    fun startHostManualLan() {
        stop()
        activeMode = TransportMode.MANUAL_LAN
        val hostIp = LanFallbackConnector.findLocalIpv4()
        if (hostIp == null) {
            _connectionState.value = ConnectionState.Error(
                "No active Wi-Fi or Hotspot IP found. Enable your phone Hotspot or connect both phones to the same Wi-Fi."
            )
            return
        }
        val token = SessionToken.generate()
        startHostUdp(
            hostIp = hostIp,
            ssid = "Manual LAN",
            passphrase = "",
            sessionToken = token,
            mode = TransportMode.MANUAL_LAN
        )
    }

    fun joinViaQr(qrPayload: QrPayload) {
        stop()
        activeMode = TransportMode.LOCAL_ONLY_HOTSPOT
        _connectionState.value = ConnectionState.JoiningWifi(qrPayload.ssid)

        wifiClient.connect(
            ssid = qrPayload.ssid,
            passphrase = qrPayload.passphrase,
            onConnected = { connected ->
                val targetIp = connected.gatewayIp ?: qrPayload.hostIp
                Log.i("RiderLink", "joinViaQr connected: qrIp=${qrPayload.hostIp}, gatewayIp=${connected.gatewayIp}, target=$targetIp:${qrPayload.udpPort}")
                startClientUdp(
                    hostIp = targetIp,
                    port = qrPayload.udpPort,
                    sessionToken = qrPayload.sessionToken,
                    mode = TransportMode.LOCAL_ONLY_HOTSPOT,
                    boundNetwork = connected.network
                )
            },
            onError = { err ->
                _connectionState.value = ConnectionState.Error(err)
            },
            onRetrying = { nextAttempt ->
                _connectionState.value = ConnectionState.JoiningWifi(qrPayload.ssid, nextAttempt)
            }
        )
    }

    fun joinViaManualLan(
        hostIp: String,
        port: Int,
        sessionToken: Int,
        boundNetwork: android.net.Network? = null
    ) {
        stop()
        activeMode = TransportMode.MANUAL_LAN
        startClientUdp(
            hostIp = hostIp,
            port = port,
            sessionToken = sessionToken,
            mode = TransportMode.MANUAL_LAN,
            boundNetwork = boundNetwork
        )
    }

    fun markWebBridgeConnected(clientIp: String) {
        webBridgeIp = clientIp
        webBridgeConnected = true
        activeMode = TransportMode.WEB_IOS_BRIDGE
        _transportStats.value = TransportStats(
            linkState = LinkState.CONNECTED,
            rttMs = webBridgeRttMs,
            packetLossPercent = 0f,
            audioPacketsReceived = webBridgeAudioRx,
            peerAddress = clientIp
        )
        _connectionState.value = ConnectionState.Connected(TransportMode.WEB_IOS_BRIDGE)
    }

    fun updateWebBridgeStats(rttMs: Long, audioPacketsReceived: Long) {
        webBridgeRttMs = rttMs
        webBridgeAudioRx = audioPacketsReceived
    }

    fun markWebBridgeDisconnected() {
        webBridgeConnected = false
        _transportStats.value = _transportStats.value.copy(linkState = LinkState.RECONNECTING)
    }

    fun refreshStats() {
        if (activeMode == TransportMode.WEB_IOS_BRIDGE) {
            _transportStats.value = TransportStats(
                linkState = if (webBridgeConnected) LinkState.CONNECTED else LinkState.RECONNECTING,
                rttMs = webBridgeRttMs,
                packetLossPercent = 0f,
                audioPacketsReceived = webBridgeAudioRx,
                peerAddress = webBridgeIp
            )
            return
        }
        val stats = transport?.currentStats() ?: TransportStats()
        _transportStats.value = stats
    }

    fun stop() {
        transport?.stop()
        transport = null
        wifiClient.stop()
        hotspotHost.stop()
        activeMode = null
        webBridgeIp = null
        webBridgeConnected = false
        webBridgeRttMs = 0L
        webBridgeAudioRx = 0L
        _transportStats.value = TransportStats()
        _connectionState.value = ConnectionState.Idle
    }

    private fun startHostUdp(
        hostIp: String,
        ssid: String,
        passphrase: String,
        sessionToken: Int,
        mode: TransportMode
    ) {
        val udp = createTransport(SenderId.HOST_RIDER, sessionToken, mode)
        try {
            udp.start(localPort = UdpAudioTransport.DEFAULT_UDP_PORT, initialPeer = null, boundNetwork = null)
            transport = udp
            val qr = QrPayload(
                ssid = ssid,
                passphrase = passphrase,
                hostIp = hostIp,
                udpPort = udp.boundPort,
                sessionToken = sessionToken
            )
            _connectionState.value = ConnectionState.HostReady(qr, mode)
        } catch (e: Exception) {
            udp.stop()
            hotspotHost.stop()
            _connectionState.value = ConnectionState.Error("Failed to bind UDP port: ${e.message}")
        }
    }

    private fun startClientUdp(
        hostIp: String,
        port: Int,
        sessionToken: Int,
        mode: TransportMode,
        boundNetwork: android.net.Network?
    ) {
        _connectionState.value = ConnectionState.ConnectingUdp(hostIp, port, mode)
        val udp = createTransport(SenderId.OBR, sessionToken, mode)
        try {
            udp.start(
                localPort = 0,
                initialPeer = InetSocketAddress(hostIp, port),
                boundNetwork = boundNetwork
            )
            transport = udp
        } catch (e: Exception) {
            udp.stop()
            wifiClient.stop()
            _connectionState.value = ConnectionState.Error("Failed to start UDP client: ${e.message}")
        }
    }

    private fun createTransport(
        senderId: Byte,
        sessionToken: Int,
        mode: TransportMode
    ): UdpAudioTransport {
        return UdpAudioTransport(
            senderId = senderId,
            sessionToken = sessionToken,
            listener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {
                    transportListener.onAudioPacket(header, payload, offset, length)
                }

                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {
                    transportListener.onControlPacket(header, controlCode)
                }

                override fun onLinkStateChanged(state: LinkState) {
                    refreshStats()
                    if (state == LinkState.CONNECTED) {
                        _connectionState.value = ConnectionState.Connected(mode)
                    }
                    transportListener.onLinkStateChanged(state)
                }
            }
        )
    }
}
