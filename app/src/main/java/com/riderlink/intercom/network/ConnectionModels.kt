package com.riderlink.intercom.network

import com.riderlink.intercom.protocol.QrPayload

enum class Role {
    HOST_RIDER,
    JOIN_OBR
}

enum class TransportMode {
    LOCAL_ONLY_HOTSPOT,
    MANUAL_LAN,
    WEB_IOS_BRIDGE
}

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object StartingHotspot : ConnectionState()
    data class HostReady(
        val qrPayload: QrPayload,
        val mode: TransportMode
    ) : ConnectionState()
    data class JoiningWifi(val ssid: String, val attempt: Int = 1) : ConnectionState()
    data class ConnectingUdp(
        val hostIp: String,
        val port: Int,
        val mode: TransportMode
    ) : ConnectionState()
    data class Connected(val mode: TransportMode) : ConnectionState()
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : ConnectionState()
}
