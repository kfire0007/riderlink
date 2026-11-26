package com.riderlink.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects the OBR (Join) phone to the Rider's LocalOnlyHotspot via Android 10+ WifiNetworkSpecifier
 * without disrupting cellular data for Google Maps / Waze.
 */
class WifiSpecifierClient(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private val active = AtomicBoolean(false)

    fun connect(
        ssid: String,
        passphrase: String,
        onConnected: (Network) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        active.set(true)

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
                if (active.get()) {
                    onConnected(network)
                }
            }

            override fun onUnavailable() {
                if (active.compareAndSet(true, false)) {
                    onError("Could not connect to Rider hotspot '$ssid'. Stay close and try again.")
                }
            }

            override fun onLost(network: Network) {
                if (active.compareAndSet(true, false)) {
                    onError("Lost local Wi-Fi link to Rider hotspot.")
                }
            }
        }

        activeCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, CONNECT_TIMEOUT_MS)
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
        val cb = activeCallback ?: return
        activeCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 25_000
    }
}
