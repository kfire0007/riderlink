package com.riderlink.intercom.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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
import com.riderlink.intercom.ui.HomeActivity
import com.riderlink.intercom.ui.RideActivity
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

/**
 * Foreground service owning RiderLink's connection controller, full-duplex audio engine,
 * headset routing, cellular call pause/resume, hardware button triggers, WakeLock, and WifiLock.
 */
class IntercomForegroundService : Service() {

    data class ServiceDiagnostics(
        val isRunning: Boolean = false,
        val wakeLockHeld: Boolean = false,
        val wifiLockHeld: Boolean = false,
        val isMuted: Boolean = false,
        val isBatteryExempt: Boolean = false,
        val jitterDepthFrames: Int = 0,
        val isLocalVoiceActive: Boolean = false
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
    private val talkModeController = TalkModeController()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statsRefreshJob: Job? = null
    private var webHostObserverJob: Job? = null
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
        createNotificationChannel()

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
                    handleLinkStateChange(LinkState.CONNECTED)
                    updateNotification()
                }

                override fun onAudioFrame(pcmBytes: ByteArray, offset: Int, length: Int) {
                    webRxSeq++
                    connectionController.updateWebBridgeStats(lastWebRttMs, webRxSeq)
                    audioEngine.onIncomingAudio(webRxSeq, pcmBytes, offset, length)
                }

                override fun onHeartbeat(rttMs: Long) {
                    if (rttMs > 0L) lastWebRttMs = rttMs
                    connectionController.updateWebBridgeStats(lastWebRttMs, webRxSeq)
                }

                override fun onClientControl(message: String) {
                    if (message == "ctrl:goodbye") {
                        onClientDisconnected()
                    }
                }

                override fun onClientDisconnected() {
                    connectionController.markWebBridgeDisconnected()
                    handleLinkStateChange(LinkState.RECONNECTING)
                    updateNotification()
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
                    }
                }

                override fun onLinkStateChanged(state: LinkState) {
                    handleLinkStateChange(state)
                    updateNotification()
                }
            }
        )
        refreshDiagnostics()
        webHostObserverJob = serviceScope.launch {
            connectionController.connectionState.collect { state ->
                if (state is ConnectionState.HostReady) {
                    runCatching {
                        webBridgeServer.start(
                            port = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
                            sessionToken = state.qrPayload.sessionToken
                        )
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RIDE) {
            stopSession()
            stopSelf()
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

    fun joinViaManualLan(hostIp: String, port: Int, sessionToken: Int) {
        prepareSessionStart()
        connectionController.joinViaManualLan(hostIp, port, sessionToken)
        startStatsRefreshLoop()
    }

    fun setTalkMode(mode: TalkMode) {
        talkModeController.setTalkMode(mode)
        updateNotification()
    }

    fun setMuted(muted: Boolean) {
        talkModeController.setMuted(muted)
        _diagnostics.value = _diagnostics.value.copy(isMuted = muted)
        if (audioPipelineStarted) {
            audioEngine.playChime(if (muted) ChimeType.MUTED else ChimeType.UNMUTED)
        }
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
        webHostObserverJob = serviceScope.launch {
            connectionController.connectionState.collect { state ->
                if (state is ConnectionState.HostReady) {
                    runCatching {
                        webBridgeServer.start(
                            port = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
                            sessionToken = state.qrPayload.sessionToken
                        )
                    }
                }
            }
        }
    }

    fun handleHardwareKeyDown(keyCode: Int, repeatCount: Int): Boolean =
        hardwareButtons.onKeyDown(keyCode, repeatCount)

    fun handleHardwareKeyUp(keyCode: Int): Boolean =
        hardwareButtons.onKeyUp(keyCode)

    fun stopSession() {
        statsRefreshJob?.cancel()
        statsRefreshJob = null
        webBridgeServer.stop()
        stopAudioPipeline()
        connectionController.stop()
        talkModeController.reset()
        hasConnectedOnce = false
        webRxSeq = 0L
        lastWebRttMs = 0L
        releaseLocks()
        _diagnostics.value = _diagnostics.value.copy(
            isRunning = false,
            isMuted = false,
            isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        webHostObserverJob?.cancel()
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun prepareSessionStart() {
        hasConnectedOnce = false
        talkModeController.reset()
        promoteToForeground()
        acquireLocks()
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
            connectionController.transport?.sendControl(ControlCode.PEER_ON_CALL)
            audioEngine.setPausedForCall(true)
            headsetRouteManager.suspendForCellularCall()
        } else {
            headsetRouteManager.resumeAfterCellularCall()
            audioEngine.setPausedForCall(false)
            talkModeController.setLocalCallPaused(false)
            connectionController.transport?.sendControl(ControlCode.PEER_CALL_ENDED)
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
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
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        _diagnostics.value = _diagnostics.value.copy(
            isRunning = true,
            isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RiderLink:IntercomWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "RiderLink:IntercomWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        refreshDiagnostics()
        webHostObserverJob = serviceScope.launch {
            connectionController.connectionState.collect { state ->
                if (state is ConnectionState.HostReady) {
                    runCatching {
                        webBridgeServer.start(
                            port = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
                            sessionToken = state.qrPayload.sessionToken
                        )
                    }
                }
            }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        refreshDiagnostics()
        webHostObserverJob = serviceScope.launch {
            connectionController.connectionState.collect { state ->
                if (state is ConnectionState.HostReady) {
                    runCatching {
                        webBridgeServer.start(
                            port = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
                            sessionToken = state.qrPayload.sessionToken
                        )
                    }
                }
            }
        }
    }

    private fun startStatsRefreshLoop() {
        statsRefreshJob?.cancel()
        statsRefreshJob = serviceScope.launch {
            while (isActive) {
                if (webBridgeServer.isClientConnected) {
                    webBridgeServer.sendPing(System.nanoTime() / 1_000_000L)
                }
                connectionController.refreshStats()
                refreshDiagnostics()
                delay(1000L)
            }
        }
    }

    private fun refreshDiagnostics() {
        _diagnostics.value = _diagnostics.value.copy(
            wakeLockHeld = wakeLock?.isHeld == true,
            wifiLockHeld = wifiLock?.isHeld == true,
            isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this),
            jitterDepthFrames = if (::audioEngine.isInitialized) audioEngine.jitterBuffer.currentDepthFrames else 0,
            isLocalVoiceActive = if (::audioEngine.isInitialized) audioEngine.isLocalVoiceActive else false
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (!_diagnostics.value.isRunning) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val targetActivity = if (connectionController.connectionState.value is ConnectionState.Connected) {
            RideActivity::class.java
        } else {
            HomeActivity::class.java
        }
        val launchIntent = Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, IntercomForegroundService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePending = PendingIntent.getService(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, IntercomForegroundService::class.java).apply {
            action = ACTION_STOP_RIDE
        }
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val linkText = when (connectionController.transportStats.value.linkState) {
            LinkState.CONNECTED -> getString(R.string.notif_state_connected)
            LinkState.RECONNECTING -> getString(R.string.notif_state_reconnecting)
            LinkState.WAITING_FOR_PEER -> getString(R.string.notif_state_waiting)
            else -> getString(R.string.notif_state_ready)
        }
        val muteLabel = if (_diagnostics.value.isMuted) {
            getString(R.string.notif_action_unmute)
        } else {
            getString(R.string.notif_action_mute)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(linkText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .addAction(0, muteLabel, mutePending)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "riderlink_intercom_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_MUTE = "com.riderlink.intercom.action.TOGGLE_MUTE"
        const val ACTION_STOP_RIDE = "com.riderlink.intercom.action.STOP_RIDE"
    }
}
