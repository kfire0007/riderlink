package com.riderlink.intercom.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.riderlink.intercom.RiderLinkApp
import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while a Ride is live: the notification (with Mute and
 * End ride), the wake lock and the low-latency Wi-Fi lock. It owns nothing else; the Ride and
 * the voice side are app-scoped (docs/adr/0001). It stops itself as soon as the Ride is Idle.
 */
class IntercomForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: RiderLinkApp
    private lateinit var notificationController: NotificationController
    private var watching = false

    override fun onCreate() {
        super.onCreate()
        app = RiderLinkApp.from(this)
        notificationController = NotificationController(this).apply { createChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote first: a service started with startForegroundService must do so promptly.
        promoteToForeground()
        when (intent?.action) {
            ACTION_STOP_RIDE -> app.ride.end()
            ACTION_TOGGLE_MUTE -> app.voice.toggleMute()
        }
        if (!watching) {
            watching = true
            scope.launch {
                combine(app.ride.state, app.voice.talkControlState) { ride, talk -> ride to talk.isMuted }
                    .collect { (ride, muted) ->
                        if (ride.isIdle) {
                            stopKeepingAlive()
                        } else {
                            app.locks.acquire() // idempotent; covers a restart right after stopSelf()
                            notificationController.update(ride.anyLinked, notificationLinkState(ride), muted)
                        }
                    }
            }
        }
        // A restarted process has an Idle ride and nothing to resume, so don't come back.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        app.locks.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopKeepingAlive() {
        app.locks.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground() {
        val ride = app.ride.state.value
        val notification = notificationController.build(
            isConnected = ride.anyLinked,
            linkState = notificationLinkState(ride),
            isMuted = app.voice.talkControlState.value.isMuted
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
    }

    private fun notificationLinkState(ride: RideState): LinkState = when {
        ride.anyLinked -> LinkState.CONNECTED
        ride.links.isNotEmpty() -> LinkState.RECONNECTING
        ride.phase == RidePhase.WaitingForPeers || ride.phase == RidePhase.Linking -> LinkState.WAITING_FOR_PEER
        else -> LinkState.IDLE
    }

    companion object {
        const val ACTION_TOGGLE_MUTE = "com.riderlink.intercom.action.TOGGLE_MUTE"
        const val ACTION_STOP_RIDE = "com.riderlink.intercom.action.STOP_RIDE"
    }
}
