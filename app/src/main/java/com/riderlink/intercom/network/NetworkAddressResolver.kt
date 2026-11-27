package com.riderlink.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

data class WifiGateway(
    val network: Network,
    val gatewayIp: String?
)

/**
 * Canonical IPv4 address, gateway, and Wi-Fi network resolution shared by
 * LocalOnlyHotspot, WifiNetworkSpecifier, and Manual LAN fallback.
 */
object NetworkAddressResolver {

    val IGNORED_INTERFACE_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "v4-", "clat", "tun", "ppp", "dummy", "epdg", "ims"
    )

    private val SOFTAP_NEW_PREFIXES = listOf("ap", "swlan", "softap", "wlan")
    private val SOFTAP_FALLBACK_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "wlan2")
    private val LAN_PREFIXES = listOf("wlan", "ap", "swlan", "softap")

    fun extractGatewayIpv4(linkProperties: LinkProperties): String? {
        val clientIpv4 = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        val dhcpServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            linkProperties.dhcpServerAddress?.hostAddress
        } else {
            null
        }
        val routeGateway = linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
        val subnetGateway = clientIpv4?.let { ip ->
            val lastDot = ip.lastIndexOf('.')
            if (lastDot > 0) "${ip.substring(0, lastDot)}.1" else null
        }
        return dhcpServer ?: routeGateway ?: subnetGateway
    }

    fun listFilteredInterfaceIpv4s(): List<Pair<String, String>> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<Pair<String, String>>()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (IGNORED_INTERFACE_PREFIXES.any { name.startsWith(it) }) continue
            for (addr in iface.inetAddresses.toList().filterIsInstance<Inet4Address>()) {
                if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    addr.hostAddress?.let { result.add(name to it) }
                }
            }
        }
        return result
    }

    fun selectSoftApIpv4(
        candidates: List<Pair<String, String>>,
        preExistingIps: Set<String>
    ): String? {
        val newIps = candidates.filter { it.second !in preExistingIps }
        newIps.firstOrNull { it.second.startsWith("192.168.") && it.second.endsWith(".1") }
            ?.let { return it.second }
        newIps.firstOrNull { (iface, _) -> SOFTAP_NEW_PREFIXES.any { iface.startsWith(it) } }
            ?.let { return it.second }
        newIps.firstOrNull { it.second.endsWith(".1") }?.let { return it.second }
        newIps.firstOrNull()?.let { return it.second }

        for (prefix in SOFTAP_FALLBACK_PREFIXES) {
            candidates.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return null
    }

    fun selectLanIpv4(candidates: List<Pair<String, String>>): String? {
        for (prefix in LAN_PREFIXES) {
            candidates.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return candidates.firstOrNull()?.second
    }

    fun findActiveWifiGateway(context: Context): WifiGateway? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wifiNet = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null

        val lp = cm.getLinkProperties(wifiNet) ?: return WifiGateway(wifiNet, null)
        return WifiGateway(network = wifiNet, gatewayIp = extractGatewayIpv4(lp))
    }
}
