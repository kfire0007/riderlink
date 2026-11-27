package com.riderlink.intercom.network

import android.content.Context
import com.riderlink.intercom.protocol.QrPayload
import com.riderlink.intercom.protocol.SessionToken

/**
 * Manual Hotspot / Same-Wi-Fi LAN fallback helper sharing the exact same UDP engine.
 */
object LanFallbackConnector {

    data class ManualJoinConfig(
        val hostIp: String,
        val udpPort: Int,
        val sessionToken: Int
    )

    fun findActiveWifiGateway(context: Context): WifiGateway? =
        NetworkAddressResolver.findActiveWifiGateway(context)

    fun findLocalIpv4(): String? =
        NetworkAddressResolver.selectLanIpv4(NetworkAddressResolver.listFilteredInterfaceIpv4s())

    fun validateJoinInputs(hostIp: String, portRaw: String, tokenRaw: String): ManualJoinConfig? {
        val ip = hostIp.trim()
        if (!QrPayload.isValidIpv4(ip)) return null

        val port = portRaw.trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val token = SessionToken.parse(tokenRaw) ?: return null
        return ManualJoinConfig(hostIp = ip, udpPort = port, sessionToken = token)
    }
}
