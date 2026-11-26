package com.riderlink.intercom.network

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
