package com.riderlink.intercom.protocol

import java.nio.charset.StandardCharsets

/**
 * JOIN_REQ payload (after the control code byte):
 * `[nameLen:1][utf8Name][flags:1][riderKey:4]`.
 *
 * [riderKey] is a random per-install number. A Host uses it to recognise a Peer that rejoins from
 * a new address (after its Wi-Fi dropped) as the same rider, not a new one.
 */
data class JoinRequest(
    val name: String,
    val flags: Int,
    val riderKey: Int
) {
    fun encode(): ByteArray {
        val nameBytes = truncateUtf8(name, MAX_NAME_BYTES)
        val out = ByteArray(1 + nameBytes.size + 1 + 4)
        out[0] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, out, 1, nameBytes.size)
        var i = 1 + nameBytes.size
        out[i++] = flags.toByte()
        out[i++] = (riderKey ushr 24).toByte()
        out[i++] = (riderKey ushr 16).toByte()
        out[i++] = (riderKey ushr 8).toByte()
        out[i] = riderKey.toByte()
        return out
    }

    companion object {
        const val FLAG_ANDROID: Int = 0x01
        const val MAX_NAME_BYTES: Int = 32

        fun decode(bytes: ByteArray, offset: Int, length: Int): JoinRequest? {
            if (length < 1) return null
            val nameLen = bytes[offset].toInt() and 0xFF
            if (nameLen > MAX_NAME_BYTES || length < 1 + nameLen + 1) return null
            val name = String(bytes, offset + 1, nameLen, StandardCharsets.UTF_8)
            val flags = bytes[offset + 1 + nameLen].toInt() and 0xFF
            val keyStart = offset + 1 + nameLen + 1
            val riderKey = if (length >= 1 + nameLen + 1 + 4) {
                ((bytes[keyStart].toInt() and 0xFF) shl 24) or
                    ((bytes[keyStart + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[keyStart + 2].toInt() and 0xFF) shl 8) or
                    (bytes[keyStart + 3].toInt() and 0xFF)
            } else {
                0
            }
            return JoinRequest(name, flags, riderKey)
        }

        /** Cuts [text] to at most [maxBytes] of UTF-8 without splitting a character. */
        private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
            var end = text.length
            while (end > 0) {
                val bytes = text.substring(0, end).toByteArray(StandardCharsets.UTF_8)
                if (bytes.size <= maxBytes) return bytes
                end--
                if (end > 0 && Character.isLowSurrogate(text[end])) end--
            }
            return ByteArray(0)
        }
    }
}

/**
 * JOIN_ACK payload (after the control code byte): `[assignedSenderId:1][codecId:1][rosterVersion:1]`.
 * An [assignedSenderId] of [GROUP_FULL] means the Host refused the join.
 */
data class JoinAck(
    val assignedSenderId: Int,
    val codecId: Byte,
    val rosterVersion: Int
) {
    val isRefused: Boolean get() = assignedSenderId == GROUP_FULL

    fun encode(): ByteArray = byteArrayOf(assignedSenderId.toByte(), codecId, rosterVersion.toByte())

    companion object {
        const val GROUP_FULL: Int = 0

        fun decode(bytes: ByteArray, offset: Int, length: Int): JoinAck? {
            if (length < 3) return null
            return JoinAck(
                assignedSenderId = bytes[offset].toInt() and 0xFF,
                codecId = bytes[offset + 1],
                rosterVersion = bytes[offset + 2].toInt() and 0xFF
            )
        }
    }
}
