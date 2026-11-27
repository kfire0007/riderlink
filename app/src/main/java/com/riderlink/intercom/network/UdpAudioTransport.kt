package com.riderlink.intercom.network

import android.net.Network
import com.riderlink.intercom.protocol.CodecId
import com.riderlink.intercom.protocol.PacketCodec
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.PacketType
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead UDP transport for RiderLink audio, heartbeats, and control messages.
 * Optionally binds its DatagramSocket to a specific Android [Network] (e.g. WifiNetworkSpecifier)
 * so cellular data for Google Maps/Waze remains undisturbed.
 */
class UdpAudioTransport(
    senderId: Byte,
    private val sessionToken: Int,
    private val listener: Listener,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    interface Listener {
        fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int)
        fun onControlPacket(header: PacketHeader, controlCode: Byte)

        /** Like [onControlPacket], with the bytes that follow the control code (Phase 2 JOIN_* etc.). */
        fun onControlPacket(header: PacketHeader, controlCode: Byte, payload: ByteArray, offset: Int, length: Int) =
            onControlPacket(header, controlCode)
        fun onLinkStateChanged(state: LinkState)
    }

    /** Changes once a Phase 2 Host assigns this Peer a sender id in JOIN_ACK. */
    @Volatile
    var senderId: Byte = senderId

    private val running = AtomicBoolean(false)
    private val audioTxSeq = AtomicLong(0L)
    private val controlTxSeq = AtomicLong(0L)
    private val txLock = Any()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var peerAddress: InetSocketAddress? = null

    @Volatile
    private var pinPeer: Boolean = false

    private val rxBuffer = ByteArray(PacketHeader.MAX_DATAGRAM_SIZE + 64)
    private val rxHeader = PacketHeader()
    private val txAudioBuffer = ByteArray(PacketHeader.MAX_DATAGRAM_SIZE)
    private val txControlBuffer = ByteArray(PacketHeader.HEADER_SIZE + MAX_CONTROL_PAYLOAD)
    private val txPongBuffer = ByteArray(PacketHeader.HEADER_SIZE)
    private val txPacket = DatagramPacket(txAudioBuffer, 0)

    private var rxThread: Thread? = null
    private var heartbeatThread: Thread? = null

    val heartbeatMonitor = HeartbeatMonitor(
        nowMs = nowMs,
        onStateChanged = { listener.onLinkStateChanged(it) }
    )

    val boundPort: Int
        get() = socket?.localPort ?: -1

    fun start(
        localPort: Int = DEFAULT_UDP_PORT,
        initialPeer: InetSocketAddress? = null,
        boundNetwork: Network? = null
    ) {
        if (!running.compareAndSet(false, true)) return

        val sock = DatagramSocket(localPort).apply {
            reuseAddress = true
            try {
                trafficClass = 0xB8 // DSCP EF (Expedited Forwarding) hint
            } catch (_: SocketException) {
            }
        }
        if (boundNetwork != null) {
            boundNetwork.bindSocket(sock)
        }
        socket = sock
        peerAddress = initialPeer
        pinPeer = (initialPeer != null)
        audioTxSeq.set(0L)
        controlTxSeq.set(0L)

        heartbeatMonitor.start(initialPeer?.let { "${it.address.hostAddress}:${it.port}" })
        logInfo("UDP started: localPort=${sock.localPort}, peer=$initialPeer, boundNet=${boundNetwork != null}")

        rxThread = Thread({ runRxLoop(sock) }, "RiderLink-UDP-RX").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        heartbeatThread = Thread({ runHeartbeatLoop() }, "RiderLink-UDP-HB").apply {
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        heartbeatMonitor.stop()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        peerAddress = null
        rxThread?.interrupt()
        heartbeatThread?.interrupt()
        rxThread = null
        heartbeatThread = null
    }

    fun sendAudio(
        payload: ByteArray,
        offset: Int,
        length: Int,
        codecId: Byte = CodecId.PCM16_16K
    ): Boolean {
        val peer = peerAddress ?: return false
        val sock = socket ?: return false
        if (!running.get()) return false

        val seq = audioTxSeq.incrementAndGet()
        val ts = nowMs() and 0xFFFFFFFFL
        synchronized(txLock) {
            val totalLen = PacketCodec.encode(
                packetType = PacketType.AUDIO,
                codecId = codecId,
                senderId = senderId,
                sessionToken = sessionToken,
                sequenceNumber = seq,
                timestampMs = ts,
                payload = payload,
                payloadOffset = offset,
                payloadLength = length,
                outBuffer = txAudioBuffer
            )
            return sendRaw(sock, peer, txAudioBuffer, totalLen)
        }
    }

    fun sendControl(controlCode: Byte, extra: ByteArray? = null): Boolean {
        val extraLen = extra?.size ?: 0
        require(1 + extraLen <= MAX_CONTROL_PAYLOAD) { "Control payload too large: ${1 + extraLen}" }
        val peer = peerAddress ?: return false
        val sock = socket ?: return false
        if (!running.get()) return false

        val seq = controlTxSeq.incrementAndGet()
        val ts = nowMs() and 0xFFFFFFFFL
        synchronized(txLock) {
            val payload = ByteArray(1 + extraLen)
            payload[0] = controlCode
            if (extra != null) System.arraycopy(extra, 0, payload, 1, extraLen)
            val totalLen = PacketCodec.encode(
                packetType = PacketType.CONTROL,
                codecId = CodecId.PCM16_16K,
                senderId = senderId,
                sessionToken = sessionToken,
                sequenceNumber = seq,
                timestampMs = ts,
                payload = payload,
                payloadOffset = 0,
                payloadLength = payload.size,
                outBuffer = txControlBuffer
            )
            return sendRaw(sock, peer, txControlBuffer, totalLen)
        }
    }

    fun currentStats(): TransportStats = heartbeatMonitor.snapshot()

    private fun runRxLoop(sock: DatagramSocket) {
        val packet = DatagramPacket(rxBuffer, rxBuffer.size)
        while (running.get() && !sock.isClosed) {
            try {
                packet.length = rxBuffer.size
                sock.receive(packet)
                val payloadLen = PacketCodec.decodeHeader(
                    inBuffer = rxBuffer,
                    length = packet.length,
                    expectedSessionToken = sessionToken,
                    outHeader = rxHeader
                )
                if (payloadLen < 0) continue

                val senderAddr = packet.socketAddress as? InetSocketAddress
                if (senderAddr != null && peerAddress != senderAddr) {
                    val canLearnPeer = !pinPeer &&
                        (peerAddress == null || heartbeatMonitor.snapshot().linkState != LinkState.CONNECTED)
                    if (canLearnPeer) {
                        peerAddress = senderAddr
                        logInfo("UDP peer learned: ${senderAddr.address.hostAddress}:${senderAddr.port}")
                        heartbeatMonitor.setPeerAddress("${senderAddr.address.hostAddress}:${senderAddr.port}")
                    }
                }

                val now = nowMs()
                when (rxHeader.packetType) {
                    PacketType.AUDIO -> {
                        heartbeatMonitor.recordPacketReceived(now)
                        heartbeatMonitor.recordAudioSequence(rxHeader.sequenceNumber)
                        listener.onAudioPacket(rxHeader, rxBuffer, PacketHeader.HEADER_SIZE, payloadLen)
                    }
                    PacketType.PING -> {
                        heartbeatMonitor.recordPacketReceived(now)
                        sendPong(sock, senderAddr, rxHeader.timestampMs)
                    }
                    PacketType.PONG -> {
                        heartbeatMonitor.recordPongReceived(rxHeader.timestampMs, now)
                    }
                    PacketType.CONTROL -> {
                        heartbeatMonitor.recordPacketReceived(now)
                        if (payloadLen >= 1) {
                            listener.onControlPacket(
                                rxHeader,
                                rxBuffer[PacketHeader.HEADER_SIZE],
                                rxBuffer,
                                PacketHeader.HEADER_SIZE + 1,
                                payloadLen - 1
                            )
                        }
                    }
                }
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get()) break
            }
        }
    }

    private fun runHeartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(HEARTBEAT_TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (!running.get()) break
            val now = nowMs()
            if (heartbeatMonitor.tick(now)) {
                val peer = peerAddress
                val sock = socket
                if (peer != null && sock != null && !sock.isClosed) {
                    sendPing(sock, peer, now)
                    heartbeatMonitor.recordPingSent(now)
                }
            }
        }
    }

    private fun sendPing(sock: DatagramSocket, peer: InetSocketAddress, now: Long) {
        val seq = controlTxSeq.incrementAndGet()
        synchronized(txLock) {
            val totalLen = PacketCodec.encode(
                packetType = PacketType.PING,
                codecId = CodecId.PCM16_16K,
                senderId = senderId,
                sessionToken = sessionToken,
                sequenceNumber = seq,
                timestampMs = now and 0xFFFFFFFFL,
                payload = null,
                payloadOffset = 0,
                payloadLength = 0,
                outBuffer = txControlBuffer
            )
            sendRaw(sock, peer, txControlBuffer, totalLen)
        }
    }

    private fun sendPong(sock: DatagramSocket, peer: InetSocketAddress?, echoedTs: Long) {
        if (peer == null) return
        val seq = controlTxSeq.incrementAndGet()
        synchronized(txLock) {
            val totalLen = PacketCodec.encode(
                packetType = PacketType.PONG,
                codecId = CodecId.PCM16_16K,
                senderId = senderId,
                sessionToken = sessionToken,
                sequenceNumber = seq,
                timestampMs = echoedTs,
                payload = null,
                payloadOffset = 0,
                payloadLength = 0,
                outBuffer = txPongBuffer
            )
            sendRaw(sock, peer, txPongBuffer, totalLen)
        }
    }

    private fun sendRaw(
        sock: DatagramSocket,
        peer: InetSocketAddress,
        data: ByteArray,
        length: Int
    ): Boolean {
        return try {
            txPacket.setData(data, 0, length)
            txPacket.socketAddress = peer
            sock.send(txPacket)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun logInfo(msg: String) {
        runCatching { android.util.Log.i("RiderLink", msg) }
    }

    companion object {
        const val DEFAULT_UDP_PORT = 49152
        const val MAX_CONTROL_PAYLOAD = 64
        private const val HEARTBEAT_TICK_MS = 250L
    }
}
