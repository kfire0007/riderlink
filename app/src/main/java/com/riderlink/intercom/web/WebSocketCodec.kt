package com.riderlink.intercom.web

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal RFC 6455 WebSocket handshake and frame codec (zero external dependencies).
 */
object WebSocketCodec {

    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    data class WsFrame(
        val fin: Boolean,
        val opcode: Int,
        val payload: ByteArray
    )

    fun computeAcceptKey(secWebSocketKey: String): String {
        val concat = (secWebSocketKey.trim() + WS_GUID).toByteArray(StandardCharsets.US_ASCII)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(concat)
        return Base64.getEncoder().encodeToString(sha1)
    }

    fun readFrame(input: DataInputStream, maxPayloadBytes: Int = 65_536): WsFrame? {
        val b1: Int
        val b2: Int
        try {
            b1 = input.readUnsignedByte()
            b2 = input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }

        val fin = (b1 and 0x80) != 0
        val opcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        var length = (b2 and 0x7F).toLong()

        when (length) {
            126L -> length = input.readUnsignedShort().toLong()
            127L -> length = input.readLong()
        }

        if (length < 0L || length > maxPayloadBytes.toLong()) {
            throw IllegalArgumentException("WebSocket frame too large: $length")
        }

        val maskKey = if (masked) {
            ByteArray(4).also { input.readFully(it) }
        } else {
            null
        }

        val payload = ByteArray(length.toInt())
        if (payload.isNotEmpty()) {
            input.readFully(payload)
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
            }
        }
        return WsFrame(fin = fin, opcode = opcode, payload = payload)
    }

    fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        val header = when {
            length <= 125 -> byteArrayOf(
                (0x80 or (opcode and 0x0F)).toByte(),
                length.toByte()
            )
            length <= 65_535 -> byteArrayOf(
                (0x80 or (opcode and 0x0F)).toByte(),
                126.toByte(),
                ((length ushr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
            else -> throw IllegalArgumentException("Unsupported frame length: $length")
        }
        output.write(header)
        if (length > 0) {
            output.write(payload, offset, length)
        }
        output.flush()
    }
}
