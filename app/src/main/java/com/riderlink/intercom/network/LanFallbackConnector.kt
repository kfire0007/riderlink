package com.riderlink.intercom.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.riderlink.intercom.protocol.SessionToken
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manual Hotspot / Same-Wi-Fi LAN fallback helper sharing the exact same UDP engine.
 */
object LanFallbackConnector {

    data class ManualJoinConfig(
        val hostIp: String,
        val udpPort: Int,
        val sessionToken: Int
    )

    fun findActiveWifiGatewayIp(context: Context): String? {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val wifiNet = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null

        val lp = cm.getLinkProperties(wifiNet) ?: return null
        val clientIp = lp.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        val dhcp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.dhcpServerAddress?.hostAddress
        } else {
            null
        }
        val route = lp.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
        val subnetGateway = clientIp?.let { ip ->
            val dot = ip.lastIndexOf('.')
            if (dot > 0) "${ip.substring(0, dot)}.1" else null
        }
        return dhcp ?: route ?: subnetGateway
    }

    fun findLocalIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return null
        }

        var fallback: String? = null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            val addr = iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?: continue

            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                return addr.hostAddress
            }
            if (fallback == null) {
                fallback = addr.hostAddress
            }
        }
        return fallback
    }

    fun validateJoinInputs(hostIp: String, portRaw: String, tokenRaw: String): ManualJoinConfig? {
        val ip = hostIp.trim()
        val octets = ip.split('.')
        if (octets.size != 4) return null
        for (octet in octets) {
            val num = octet.toIntOrNull() ?: return null
            if (num !in 0..255) return null
        }

        val port = portRaw.trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val token = SessionToken.parse(tokenRaw) ?: return null
        return ManualJoinConfig(hostIp = ip, udpPort = port, sessionToken = token)
    }
}
