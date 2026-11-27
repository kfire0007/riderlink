package com.riderlink.intercom.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.audio.ChimeType
import com.riderlink.intercom.audio.IntercomAudioEngine
import com.riderlink.intercom.network.ConnectionController
import com.riderlink.intercom.network.ConnectionState
import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.TransportMode
import com.riderlink.intercom.network.TransportStats
import com.riderlink.intercom.network.UdpAudioTransport
import com.riderlink.intercom.oem.BatteryOptimizationHelper
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.QrPayload
import com.riderlink.intercom.web.LocalWebBridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service coordinating ConnectionController, IntercomAudioEngine,
 * headset routing, cellular call pause/resume, hardware buttons, notifications, and wake locks.
 */
class IntercomForegroundService : Service() {

    data class ServiceDiagnostics(
        val isRunning: Boolean = false,
        val wakeLockHeld: Boolean = false,
        val wifiLockHeld: Boolean = false,
        val isMuted: Boolean = false,
        val isBatteryExempt: Boolean = false,
        val jitterDepthFrames: Int = 0
    )

    inner class LocalBinder : Binder() {
        fun getService(): IntercomForegroundService = this@IntercomForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var connectionController: ConnectionController
    private lateinit var audioEngine: IntercomAudioEngine
    private lateinit var headsetRouteManager: HeadsetRouteManager
    private lateinit var callHandler: PhoneCallInterruptionHandler
    private lateinit var hardwareButtons: HardwareButtonController
    private lateinit var webBridgeServer: LocalWebBridgeServer
    private lateinit var notificationController: NotificationController
    private lateinit var lockManager: SessionLockManager
    private val talkModeController = TalkModeController()

    private var statsRefreshJob: Job? = null
    private var webHostObserverJob: Job? = null
    private var webStartJob: Job? = null
    private var stopJob: Job? = null
    private var audioPipelineStarted = false
    private var hasConnectedOnce = false
    private var webRxSeq = 0L
    private var lastWebRttMs = 0L

    private val _diagnostics = MutableStateFlow(ServiceDiagnostics())
    val diagnostics: StateFlow<ServiceDiagnostics> = _diagnostics.asStateFlow()

    val connectionState: StateFlow<ConnectionState>
        get() = connectionController.connectionState

    val transportStats: StateFlow<TransportStats>
        get() = connectionController.transportStats

    val audioRouteState: StateFlow<AudioRouteState>
        get() = headsetRouteManager.routeState

    val talkControlState: StateFlow<TalkControlState>
        get() = talkModeController.state

    val activeTransportMode: TransportMode?
        get() = connectionController.activeMode

    override fun onCreate() {
        super.onCreate()
        notificationController = NotificationController(this).apply { createChannel() }
        lockManager = SessionLockManager(this)

        audioEngine = IntercomAudioEngine(
            shouldTransmit = { talkModeController.shouldTransmitMic() },
            isBypassNoiseGate = {
                val s = talkModeController.state.value
                s.mode == TalkMode.PUSH_TO_TALK && s.isPttTransmitting
            },
            onEncodedFrame = { bytes, offset, length, codecId ->
                connectionController.transport?.sendAudio(bytes, offset, length, codecId)
                if (webBridgeServer.isClientConnected) {
                    webBridgeServer.sendAudio(bytes, offset, length)
                }
            }
        )

        webBridgeServer = LocalWebBridgeServer(
            openKeystore = { resources.openRawResource(R.raw.riderlink_local) },
            keystorePassword = "riderlink",
            loadHtmlBytes = { assets.open("web/index.html").use { it.readBytes() } },
            listener = object : LocalWebBridgeServer.Listener {
                override fun onClientConnected(clientIp: String) {
                    webRxSeq = 0L
                    connectionController.markWebBridgeConnected(clientIp)
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        handleLinkStateChange(LinkState.CONNECTED)
                        updateNotification()
                    }
                }

                override fun onAudioFrame(pcmBytes: ByteArray, offset: Int, length: Int) {
                    webRxSeq++
                    connectionController.updateWebBridgeStats(lastWebRttMs, webRxSeq)
                    audioEngine.onIncomingAudio(webRxSeq, pcmBytes, offset, length)
                }

                override fun onHeartbeat(rttMs: Long?) {
                    if (rttMs != null) lastWebRttMs = rttMs
                    connectionController.updateWebBridgeStats(lastWebRttMs, webRxSeq)
                }

                override fun onClientControl(message: String) {
                    // ctrl:goodbye breaks LocalWebBridgeServer's read loop so finally notifies disconnect once.
                }

                override fun onClientDisconnected() {
                    connectionController.markWebBridgeDisconnected()
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        handleLinkStateChange(LinkState.RECONNECTING)
                        updateNotification()
                    }
                }
            }
        )

        headsetRouteManager = HeadsetRouteManager(this)
        callHandler = PhoneCallInterruptionHandler(this) { isCallActive ->
            onLocalCallStateChanged(isCallActive)
        }
        hardwareButtons = HardwareButtonController(
            context = this,
            getTalkMode = { talkModeController.state.value.mode },
            onPttPressedChanged = { pressed -> setPttPressed(pressed) },
            onTogglePtt = { togglePtt() },
            onToggleMute = { toggleMute() }
        )

        connectionController = ConnectionController(
            context = this,
            scope = serviceScope,
            transportListener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {
                    audioEngine.onIncomingAudio(header.sequenceNumber, payload, offset, length)
                }

                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {
                    when (controlCode) {
                        ControlCode.PEER_ON_CALL -> {
                            talkModeController.setPeerOnCall(true)
                            audioEngine.playChime(ChimeType.PEER_ON_CALL)
                        }
                        ControlCode.PEER_CALL_ENDED -> {
                            talkModeController.setPeerOnCall(false)
                            audioEngine.playChime(ChimeType.RECONNECTED)
                        }
                        ControlCode.GOODBYE -> {
                            if (audioPipelineStarted) {
                                audioEngine.remoteAudioMuted = true
                                audioEngine.playChime(ChimeType.LINK_LOST)
                            }
                        }
                    }
                }

                override fun onLinkStateChanged(state: LinkState) {
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        handleLinkStateChange(state)
                        updateNotification()
                    }
                }
            }
        )

        refreshDiagnostics()
        webHostObserverJob?.cancel()
        webHostObserverJob = serviceScope.launch {
            connectionController.connectionState.collect { state ->
                if (state is ConnectionState.HostReady) {
                    webStartJob?.cancel()
                    webStartJob = serviceScope.launch(Dispatchers.IO) {
                        runCatching {
                            webBridgeServer.start(
                                port = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
                                sessionToken = state.qrPayload.sessionToken
                            )
                        }.onFailure { err ->
                            Log.w(TAG, "LocalWebBridgeServer start failed: ${err.message}")
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RIDE) {
            stopSession(stopService = true)
            return START_NOT_STICKY
        }
        if (!_diagnostics.value.isRunning) {
            promoteToForeground()
        }
        if (intent?.action == ACTION_TOGGLE_MUTE) {
            toggleMute()
        }
        return START_STICKY
    }

    fun startHostHotspot() {
        prepareSessionStart()
        connectionController.startHostHotspot()
        startStatsRefreshLoop()
    }

    fun startHostManualLan() {
        prepareSessionStart()
        connectionController.startHostManualLan()
        startStatsRefreshLoop()
    }

    fun joinViaQr(qrPayload: QrPayload) {
        prepareSessionStart()
        connectionController.joinViaQr(qrPayload)
        startStatsRefreshLoop()
    }

    fun joinViaManualLan(
        hostIp: String,
        port: Int,
        sessionToken: Int,
        boundNetwork: Network? = null
    ) {
        prepareSessionStart()
        connectionController.joinViaManualLan(hostIp, port, sessionToken, boundNetwork)
        startStatsRefreshLoop()
    }

    fun setTalkMode(mode: TalkMode) {
        talkModeController.setTalkMode(mode)
        updateNotification()
    }

    fun toggleMute() {
        val next = talkModeController.toggleMute()
        _diagnostics.value = _diagnostics.value.copy(isMuted = next)
        if (audioPipelineStarted) {
            audioEngine.playChime(if (next) ChimeType.MUTED else ChimeType.UNMUTED)
        }
        updateNotification()
    }

    fun setPttPressed(pressed: Boolean) {
        val was = talkModeController.state.value.isPttTransmitting
        talkModeController.setPttPressed(pressed)
        if (audioPipelineStarted && was != pressed) {
            audioEngine.playChime(if (pressed) ChimeType.PTT_DOWN else ChimeType.PTT_UP)
        }
    }

    fun togglePtt() {
        val active = talkModeController.togglePtt()
        if (audioPipelineStarted) {
            audioEngine.playChime(if (active) ChimeType.PTT_DOWN else ChimeType.PTT_UP)
        }
    }

    fun setMicGateThreshold(rms: Int) {
        talkModeController.setMicGateThreshold(rms)
        audioEngine.setNoiseGateThreshold(rms)
    }

    fun refreshBatteryStatus() {
        refreshDiagnostics()
    }

    fun handleHardwareKeyDown(keyCode: Int, repeatCount: Int): Boolean =
        hardwareButtons.onKeyDown(keyCode, repeatCount)

    fun handleHardwareKeyUp(keyCode: Int): Boolean =
        hardwareButtons.onKeyUp(keyCode)

    fun stopSession(stopService: Boolean = true) {
        stopJob?.cancel()
        stopJob = null
        webStartJob?.cancel()
        webStartJob = null
        statsRefreshJob?.cancel()
        statsRefreshJob = null

        stopAudioPipeline()
        talkModeController.reset()
        hasConnectedOnce = false
        webRxSeq = 0L
        lastWebRttMs = 0L
        lockManager.release()
        refreshDiagnostics()
        _diagnostics.value = _diagnostics.value.copy(
            isRunning = false,
            isMuted = false
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        if (!stopService) {
            webBridgeServer.stop()
            connectionController.stop()
            return
        }

        stopJob = serviceScope.launch {
            withTimeoutOrNull(250L) {
                withContext(Dispatchers.IO) {
                    runCatching { connectionController.transport?.sendControl(ControlCode.GOODBYE) }
                    if (webBridgeServer.isClientConnected) {
                        runCatching { webBridgeServer.sendTextControl("ctrl:goodbye") }
                    }
                }
            }
            webBridgeServer.stop()
            connectionController.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopJob?.cancel()
        webHostObserverJob?.cancel()
        stopSession(stopService = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun prepareSessionStart() {
        stopJob?.cancel()
        stopJob = null
        hasConnectedOnce = false
        talkModeController.reset()
        promoteToForeground()
        lockManager.acquire()
        refreshDiagnostics()
    }

    private fun handleLinkStateChange(state: LinkState) {
        when (state) {
            LinkState.CONNECTED -> {
                if (!audioPipelineStarted) {
                    startAudioPipeline()
                }
                audioEngine.remoteAudioMuted = false
                if (!hasConnectedOnce) {
                    hasConnectedOnce = true
                    audioEngine.playChime(ChimeType.CONNECTED)
                } else {
                    audioEngine.playChime(ChimeType.RECONNECTED)
                }
            }
            LinkState.RECONNECTING -> {
                if (audioPipelineStarted) {
                    audioEngine.remoteAudioMuted = true
                    audioEngine.resetJitterBuffer()
                    audioEngine.playChime(ChimeType.LINK_LOST)
                }
            }
            LinkState.DISCONNECTED -> {
                stopAudioPipeline()
            }
            else -> Unit
        }
    }

    private fun startAudioPipeline() {
        if (audioPipelineStarted) return
        audioPipelineStarted = true
        headsetRouteManager.start()
        audioEngine.setNoiseGateThreshold(talkModeController.state.value.micGateThresholdRms)
        audioEngine.start()
        callHandler.start()
        hardwareButtons.start()
    }

    private fun stopAudioPipeline() {
        if (!audioPipelineStarted) return
        audioPipelineStarted = false
        hardwareButtons.stop()
        callHandler.stop()
        audioEngine.stop()
        headsetRouteManager.stop()
    }

    private fun onLocalCallStateChanged(isCallActive: Boolean) {
        if (!audioPipelineStarted) return
        if (isCallActive) {
            talkModeController.setLocalCallPaused(true)
            audioEngine.setPausedForCall(true)
            headsetRouteManager.suspendForCellularCall()
            serviceScope.launch(Dispatchers.IO) {
                runCatching { connectionController.transport?.sendControl(ControlCode.PEER_ON_CALL) }
                if (webBridgeServer.isClientConnected) {
                    runCatching { webBridgeServer.sendTextControl("ctrl:peer_on_call") }
                }
            }
        } else {
            headsetRouteManager.resumeAfterCellularCall()
            audioEngine.setPausedForCall(false)
            talkModeController.setLocalCallPaused(false)
            serviceScope.launch(Dispatchers.IO) {
                runCatching { connectionController.transport?.sendControl(ControlCode.PEER_CALL_ENDED) }
                if (webBridgeServer.isClientConnected) {
                    runCatching { webBridgeServer.sendTextControl("ctrl:peer_call_ended") }
                }
            }
        }
    }

    private fun promoteToForeground() {
        val notification = notificationController.build(
            isConnected = connectionController.connectionState.value is ConnectionState.Connected,
            linkState = connectionController.transportStats.value.linkState,
            isMuted = _diagnostics.value.isMuted
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            type
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationController.NOTIFICATION_ID, notification, serviceType)
        _diagnostics.value = _diagnostics.value.copy(
            isRunning = true,
            isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        )
    }

    private fun startStatsRefreshLoop() {
        statsRefreshJob?.cancel()
        statsRefreshJob = serviceScope.launch {
            while (isActive) {
                if (webBridgeServer.isClientConnected) {
                    withContext(Dispatchers.IO) {
                        webBridgeServer.sendPing(System.nanoTime() / 1_000_000L)
                    }
                }
                connectionController.refreshStats()
                refreshDiagnostics()
                delay(1000L)
            }
        }
    }

    private fun refreshDiagnostics() {
        _diagnostics.value = _diagnostics.value.copy(
            wakeLockHeld = lockManager.isWakeLockHeld,
            wifiLockHeld = lockManager.isWifiLockHeld,
            isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this),
            jitterDepthFrames = if (::audioEngine.isInitialized) audioEngine.jitterBuffer.currentDepthFrames else 0
        )
    }

    private fun updateNotification() {
        if (!_diagnostics.value.isRunning) return
        notificationController.update(
            isConnected = connectionController.connectionState.value is ConnectionState.Connected,
            linkState = connectionController.transportStats.value.linkState,
            isMuted = _diagnostics.value.isMuted
        )
    }

    companion object {
        private const val TAG = "RiderLink"
        const val ACTION_TOGGLE_MUTE = "com.riderlink.intercom.action.TOGGLE_MUTE"
        const val ACTION_STOP_RIDE = "com.riderlink.intercom.action.STOP_RIDE"
    }
}
