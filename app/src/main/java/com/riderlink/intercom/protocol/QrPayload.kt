package com.riderlink.intercom.protocol

data class QrPayload(
    val ssid: String,
    val passphrase: String,
    val hostIp: String,
    val udpPort: Int,
    val sessionToken: Int
) {
    fun encode(): String {
        val encSsid = escape(ssid)
        val encPass = escape(passphrase)
        val tokenStr = SessionToken.format(sessionToken)
        return "$PREFIX|$encSsid|$encPass|$hostIp|$udpPort|$tokenStr"
    }

    /** Standard iPhone Camera Wi-Fi QR format: WIFI:T:WPA;S:<ssid>;P:<pass>;; */
    fun toStandardWifiQr(): String {
        val escSsid = escapeWifiField(ssid)
        val escPass = escapeWifiField(passphrase)
        return "WIFI:T:WPA;S:$escSsid;P:$escPass;;"
    }

    fun toIosWebUrl(httpsPort: Int = 8443): String =
        "https://$hostIp:$httpsPort/?token=${SessionToken.format(sessionToken)}"

    companion object {
        const val PREFIX = "RL1"

        private fun escapeWifiField(value: String): String =
            value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace(":", "\\:")

        fun parse(raw: String): QrPayload? {
            val parts = raw.trim().split('|')
            if (parts.size != 6 || parts[0] != PREFIX) return null

            val ssid = unescape(parts[1]) ?: return null
            if (ssid.isEmpty()) return null

            val passphrase = unescape(parts[2]) ?: return null
            val hostIp = parts[3].trim()
            if (!isValidIpv4(hostIp)) return null

            val port = parts[4].trim().toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            val token = SessionToken.parse(parts[5]) ?: return null

            return QrPayload(
                ssid = ssid,
                passphrase = passphrase,
                hostIp = hostIp,
                udpPort = port,
                sessionToken = token
            )
        }

        private fun escape(value: String): String =
            value.replace("%", "%25").replace("|", "%7C")

        private fun unescape(value: String): String? {
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '%') {
                    if (i + 2 >= value.length) return null
                    val hex = value.substring(i + 1, i + 3)
                    when (hex.uppercase()) {
                        "25" -> sb.append('%')
                        "7C" -> sb.append('|')
                        else -> return null
                    }
                    i += 3
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }

        fun isValidIpv4(ip: String): Boolean {
            val octets = ip.split('.')
            if (octets.size != 4) return false
            for (octet in octets) {
                if (octet.isEmpty() || octet.length > 3) return false
                val num = octet.toIntOrNull() ?: return false
                if (num !in 0..255) return false
            }
            return true
        }
    }
}
