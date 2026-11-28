package com.riderlink.intercom.protocol

import java.nio.charset.StandardCharsets

/** One rider in a group ride, as the Host announces it. [senderId] 0 is the Host itself. */
data class RosterEntry(
    val senderId: Int,
    val name: String,
    val flags: Int
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        const val CONNECTED = 0x01
        const val RECONNECTING = 0x02
        const val SPEAKING = 0x04
        const val ON_CALL = 0x08
        const val HOST_MUTED = 0x10
        const val HOST = 0x20
        const val SAFARI = 0x40
    }
}

/**
 * ROSTER_SYNC payload (after the control code byte):
 * `[rosterVersion:1][count:1]` then per rider `[senderId:1][statusFlags:1][nameLen:1][utf8Name]`,
 * then optionally `[titleLen:1][utf8Title]`: the group music now playing (v0.3+; older apps ignore it).
 * The Host sends it to every Peer about once a second and whenever it changes.
 */
data class RosterSync(
    val version: Int,
    val entries: List<RosterEntry>,
    val nowPlaying: String? = null
) {
    fun encode(): ByteArray {
        val names = entries.map { truncateUtf8(it.name, JoinRequest.MAX_NAME_BYTES) }
        val title = nowPlaying?.let { truncateUtf8(it, MAX_TITLE_BYTES) } ?: ByteArray(0)
        val out = ByteArray(2 + entries.size * 3 + names.sumOf { it.size } + 1 + title.size)
        out[0] = version.toByte()
        out[1] = entries.size.toByte()
        var i = 2
        entries.forEachIndexed { index, entry ->
            out[i++] = entry.senderId.toByte()
            out[i++] = entry.flags.toByte()
            val name = names[index]
            out[i++] = name.size.toByte()
            System.arraycopy(name, 0, out, i, name.size)
            i += name.size
        }
        out[i++] = title.size.toByte()
        System.arraycopy(title, 0, out, i, title.size)
        return out
    }

    companion object {
        /** A group is the Host + 9 Android Peers + 1 Safari peer. */
        const val MAX_ENTRIES = 11
        const val MAX_TITLE_BYTES = 48

        fun decode(bytes: ByteArray, offset: Int, length: Int): RosterSync? {
            if (length < 2) return null
            val count = bytes[offset + 1].toInt() and 0xFF
            if (count > MAX_ENTRIES) return null
            var i = offset + 2
            val end = offset + length
            val entries = ArrayList<RosterEntry>(count)
            repeat(count) {
                if (i + 3 > end) return null
                val id = bytes[i].toInt() and 0xFF
                val flags = bytes[i + 1].toInt() and 0xFF
                val nameLen = bytes[i + 2].toInt() and 0xFF
                i += 3
                if (nameLen > JoinRequest.MAX_NAME_BYTES || i + nameLen > end) return null
                entries += RosterEntry(id, String(bytes, i, nameLen, StandardCharsets.UTF_8), flags)
                i += nameLen
            }
            var nowPlaying: String? = null
            if (i < end) {
                val titleLen = bytes[i].toInt() and 0xFF
                if (titleLen in 1..MAX_TITLE_BYTES && i + 1 + titleLen <= end) {
                    nowPlaying = String(bytes, i + 1, titleLen, StandardCharsets.UTF_8)
                }
            }
            return RosterSync(bytes[offset].toInt() and 0xFF, entries, nowPlaying)
        }

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
