package com.riderlink.intercom

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.riderlink.intercom.ride.Ride
import com.riderlink.intercom.ride.adapters.AndroidHotspotFormer
import com.riderlink.intercom.ride.adapters.AndroidSharedWifi
import com.riderlink.intercom.ride.adapters.AndroidWifiJoiner
import com.riderlink.intercom.ride.adapters.MainThreadScheduler
import com.riderlink.intercom.ride.adapters.SafariPeerGateway
import com.riderlink.intercom.ride.adapters.UdpHostDialer
import com.riderlink.intercom.ride.adapters.UdpGroupGateway
import com.riderlink.intercom.service.IntercomForegroundService
import com.riderlink.intercom.service.SessionLockManager
import com.riderlink.intercom.voice.RideVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Builds the app's long-lived objects once, by hand (docs/adr/0001): the [Ride], its Android
 * adapters, the [RideVoice] side, and the watcher that keeps the foreground service running
 * while a ride is live.
 */
class RiderLinkApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var ride: Ride
        private set
    lateinit var voice: RideVoice
        private set
    lateinit var locks: SessionLockManager
        private set
    lateinit var profile: RiderProfile
        private set

    override fun onCreate() {
        super.onCreate()
        profile = RiderProfile(this)
        val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RiderLink-Ride-IO").apply { isDaemon = true }
        }
        ride = Ride(
            scheduler = MainThreadScheduler(),
            hotspotFormer = AndroidHotspotFormer(this, appScope),
            wifiJoiner = AndroidWifiJoiner(this),
            sharedWifi = AndroidSharedWifi(this),
            androidGateway = UdpGroupGateway(io, log = { Log.i(TAG, it) }),
            safariGateway = SafariPeerGateway(
                openKeystore = { resources.openRawResource(R.raw.riderlink_local) },
                keystorePassword = "riderlink",
                loadHtmlBytes = { assets.open("web/index.html").use { it.readBytes() } },
                log = { Log.w(TAG, it) }
            ),
            hostDialer = UdpHostDialer(io, identity = { profile.joinRequest() }),
            voiceReceiver = { link, sequence, bytes, offset, length ->
                voice.onVoice(link, sequence, bytes, offset, length)
            },
            log = { Log.i(TAG, it) }
        )
        voice = RideVoice(this, ride, appScope)
        locks = SessionLockManager(this)
        keepForegroundWhileRiding()
    }

    /** Starts the foreground service whenever a ride leaves Idle; the service stops itself at Idle. */
    private fun keepForegroundWhileRiding() {
        appScope.launch {
            ride.state.map { !it.isIdle }.distinctUntilChanged().collect { live ->
                if (live) {
                    try {
                        ContextCompat.startForegroundService(
                            this@RiderLinkApp,
                            Intent(this@RiderLinkApp, IntercomForegroundService::class.java)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not start the foreground service: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RiderLink"

        fun from(context: Context): RiderLinkApp = context.applicationContext as RiderLinkApp
    }
}
