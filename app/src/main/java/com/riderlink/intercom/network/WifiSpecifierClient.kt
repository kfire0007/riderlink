package com.riderlink.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
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

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private val active = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    fun connect(
        ssid: String,
        passphrase: String,
        onConnected: (ConnectedWifi) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        active.set(true)
        delivered.set(false)

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
                if (active.compareAndSet(true, false)) {
                    Log.w(TAG, "WifiSpecifier unavailable for ssid=$ssid")
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
        delivered.set(false)
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

        val dhcpServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            linkProperties.dhcpServerAddress?.hostAddress
        } else {
            null
        }
        val routeGateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
        val subnetGateway = clientIpv4.hostAddress?.let { ip ->
            val lastDot = ip.lastIndexOf('.')
            if (lastDot > 0) "${ip.substring(0, lastDot)}.1" else null
        }

        val gatewayIp = dhcpServer ?: routeGateway ?: subnetGateway
        Log.i(TAG, "WifiSpecifier ready: clientIp=${clientIpv4.hostAddress}, gatewayIp=$gatewayIp")
        onConnected(ConnectedWifi(network = network, gatewayIp = gatewayIp))
    }

    companion object {
        private const val TAG = "RiderLink"
        private const val CONNECT_TIMEOUT_MS = 25_000
    }
}
