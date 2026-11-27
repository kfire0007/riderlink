package com.riderlink.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the OBR (Join) phone to the Rider's LocalOnlyHotspot via Android 10+ WifiNetworkSpecifier
 * without disrupting cellular data for Google Maps / Waze, and resolves the exact SoftAP gateway IP.
 */
class WifiSpecifierClient(context: Context) {

    data class ConnectedWifi(
        val network: Network,
        val gatewayIp: String?
    )

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRetryRunnable: Runnable? = null
    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private val active = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    fun connect(
        ssid: String,
        passphrase: String,
        onConnected: (ConnectedWifi) -> Unit,
        onError: (String) -> Unit,
        onRetrying: ((Int) -> Unit)? = null
    ) {
        stop()
        active.set(true)
        delivered.set(false)
        requestAttempt(
            ssid = ssid,
            passphrase = passphrase,
            attempt = 1,
            onConnected = onConnected,
            onRetrying = onRetrying,
            onError = onError
        )
    }

    private fun requestAttempt(
        ssid: String,
        passphrase: String,
        attempt: Int,
        onConnected: (ConnectedWifi) -> Unit,
        onRetrying: ((Int) -> Unit)?,
        onError: (String) -> Unit
    ) {
        if (!active.get() || delivered.get()) return

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                tryDeliver(network, connectivityManager.getLinkProperties(network), onConnected)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                tryDeliver(network, linkProperties, onConnected)
            }

            override fun onUnavailable() {
                if (activeCallback === this) {
                    activeCallback = null
                }
                runCatching { connectivityManager.unregisterNetworkCallback(this) }

                if (active.get() && !delivered.get() && attempt <= MAX_AUTO_RETRIES) {
                    val nextAttempt = attempt + 1
                    Log.w(
                        TAG,
                        "WifiSpecifier attempt $attempt unavailable for ssid=$ssid; auto-retrying in ${RETRY_DELAY_MS}ms"
                    )
                    onRetrying?.invoke(nextAttempt)
                    val retryRunnable = Runnable {
                        pendingRetryRunnable = null
                        requestAttempt(ssid, passphrase, nextAttempt, onConnected, onRetrying, onError)
                    }
                    pendingRetryRunnable = retryRunnable
                    mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                } else if (active.compareAndSet(true, false)) {
                    Log.w(TAG, "WifiSpecifier unavailable for ssid=$ssid after attempt $attempt")
                    onError("Could not connect to Rider hotspot '$ssid'. Stay close and try again.")
                }
            }

            override fun onLost(network: Network) {
                if (active.compareAndSet(true, false)) {
                    Log.w(TAG, "WifiSpecifier lost for ssid=$ssid")
                    onError("Lost local Wi-Fi link to Rider hotspot.")
                }
            }
        }

        activeCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, mainHandler, CONNECT_TIMEOUT_MS)
        } catch (se: SecurityException) {
            active.set(false)
            onError("Missing Wi-Fi/Network permission: ${se.message}")
        } catch (e: Exception) {
            active.set(false)
            onError("Wi-Fi join failed: ${e.message}")
        }
    }

    fun stop() {
        active.set(false)
        delivered.set(false)
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
        val cb = activeCallback ?: return
        activeCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun tryDeliver(
        network: Network,
        linkProperties: LinkProperties?,
        onConnected: (ConnectedWifi) -> Unit
    ) {
        if (!active.get() || linkProperties == null) return
        val clientIpv4 = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?: return

        if (!delivered.compareAndSet(false, true)) return

        val gatewayIp = NetworkAddressResolver.extractGatewayIpv4(linkProperties)
        Log.i(TAG, "WifiSpecifier ready: clientIp=${clientIpv4.hostAddress}, gatewayIp=$gatewayIp")
        onConnected(ConnectedWifi(network = network, gatewayIp = gatewayIp))
    }

    companion object {
        private const val TAG = "RiderLink"
        private const val CONNECT_TIMEOUT_MS = 25_000
        private const val MAX_AUTO_RETRIES = 1
        private const val RETRY_DELAY_MS = 1_800L
    }
}
