package com.riderlink.intercom.ride.adapters

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.riderlink.intercom.network.LocalHotspotHost
import com.riderlink.intercom.network.NetworkAddressResolver
import com.riderlink.intercom.network.WifiSpecifierClient
import com.riderlink.intercom.ride.BoundNetwork
import com.riderlink.intercom.ride.Cancellable
import com.riderlink.intercom.ride.HotspotCredentials
import com.riderlink.intercom.ride.HotspotFormer
import com.riderlink.intercom.ride.JoinedNetwork
import com.riderlink.intercom.ride.RideScheduler
import com.riderlink.intercom.ride.SharedWifi
import com.riderlink.intercom.ride.WifiCredentials
import com.riderlink.intercom.ride.WifiJoiner
import kotlinx.coroutines.CoroutineScope

/** Runs ride work on the main looper, always asynchronously so commands keep their order. */
class MainThreadScheduler : RideScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(block: () -> Unit) {
        handler.post(block)
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): Cancellable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

class AndroidBoundNetwork(val network: Network) : BoundNetwork

class AndroidHotspotFormer(context: Context, scope: CoroutineScope) : HotspotFormer {
    private val host = LocalHotspotHost(context, scope)

    override fun start(events: HotspotFormer.Events) {
        host.start(
            onStarted = { creds ->
                events.onReady(HotspotCredentials(WifiCredentials(creds.ssid, creds.passphrase), creds.hostIp))
            },
            onError = events::onFailed,
            onStoppedUnexpectedly = events::onLost
        )
    }

    override fun stop() = host.stop()
}

class AndroidWifiJoiner(context: Context) : WifiJoiner {
    private val client = WifiSpecifierClient(context)

    override fun join(wifi: WifiCredentials, events: WifiJoiner.Events) {
        client.connect(
            ssid = wifi.ssid,
            passphrase = wifi.passphrase,
            onConnected = { connected ->
                events.onJoined(JoinedNetwork(connected.gatewayIp, AndroidBoundNetwork(connected.network)))
            },
            onError = events::onFailed,
            onRetrying = events::onRetrying,
            onLost = events::onLost
        )
    }

    override fun stop() = client.stop()
}

class AndroidSharedWifi(context: Context) : SharedWifi {
    private val appContext = context.applicationContext

    override fun localIpv4(): String? =
        NetworkAddressResolver.selectLanIpv4(NetworkAddressResolver.listFilteredInterfaceIpv4s())

    override fun currentNetwork(): JoinedNetwork? =
        NetworkAddressResolver.findActiveWifiGateway(appContext)?.let {
            JoinedNetwork(it.gatewayIp, AndroidBoundNetwork(it.network))
        }
}
