package com.riderlink.intercom.protocol

/**
 * Zero-allocation big-endian UDP packet serializer and parser.
 */
object PacketCodec {

    fun encode(
        packetType: Byte,
        codecId: Byte,
        senderId: Byte,
        sessionToken: Int,
        sequenceNumber: Long,
        timestampMs: Long,
        payload: ByteArray?,
        payloadOffset: Int,
        payloadLength: Int,
        outBuffer: ByteArray
    ): Int {
        val safePayloadLen = if (payload == null) 0 else payloadLength
        require(safePayloadLen >= 0) { "Negative payloadLength: $safePayloadLen" }
        val totalLen = PacketHeader.HEADER_SIZE + safePayloadLen
        require(outBuffer.size >= totalLen) {
            "outBuffer too small: ${outBuffer.size} < $totalLen"
        }

        outBuffer[0] = ((PacketHeader.MAGIC ushr 8) and 0xFF).toByte()
        outBuffer[1] = (PacketHeader.MAGIC and 0xFF).toByte()
        outBuffer[2] = PacketHeader.VERSION
        outBuffer[3] = packetType
        outBuffer[4] = codecId
        outBuffer[5] = senderId
        outBuffer[6] = ((sessionToken ushr 8) and 0xFF).toByte()
        outBuffer[7] = (sessionToken and 0xFF).toByte()

        outBuffer[8] = ((sequenceNumber ushr 24) and 0xFFL).toByte()
        outBuffer[9] = ((sequenceNumber ushr 16) and 0xFFL).toByte()
        outBuffer[10] = ((sequenceNumber ushr 8) and 0xFFL).toByte()
        outBuffer[11] = (sequenceNumber and 0xFFL).toByte()

        outBuffer[12] = ((timestampMs ushr 24) and 0xFFL).toByte()
        outBuffer[13] = ((timestampMs ushr 16) and 0xFFL).toByte()
        outBuffer[14] = ((timestampMs ushr 8) and 0xFFL).toByte()
        outBuffer[15] = (timestampMs and 0xFFL).toByte()

        if (payload != null && safePayloadLen > 0) {
            System.arraycopy(payload, payloadOffset, outBuffer, PacketHeader.HEADER_SIZE, safePayloadLen)
        }
        return totalLen
    }

    /**
     * Decodes a 16-byte header from [inBuffer] into [outHeader].
     * Returns payload byte length (>= 0), or -1 if invalid or token-mismatched.
     */
    fun decodeHeader(
        inBuffer: ByteArray,
        length: Int,
        expectedSessionToken: Int?,
        outHeader: PacketHeader
    ): Int {
        if (length < PacketHeader.HEADER_SIZE || inBuffer.size < length) return -1

        val magic = ((inBuffer[0].toInt() and 0xFF) shl 8) or (inBuffer[1].toInt() and 0xFF)
        if (magic != PacketHeader.MAGIC) return -1

        val version = inBuffer[2]
        if (version != PacketHeader.VERSION) return -1

        val packetType = inBuffer[3]
        if (!PacketType.isValid(packetType)) return -1

        val token = ((inBuffer[6].toInt() and 0xFF) shl 8) or (inBuffer[7].toInt() and 0xFF)
        if (expectedSessionToken != null && token != (expectedSessionToken and 0xFFFF)) {
            return -1
        }

        val seq = ((inBuffer[8].toLong() and 0xFFL) shl 24) or
            ((inBuffer[9].toLong() and 0xFFL) shl 16) or
            ((inBuffer[10].toLong() and 0xFFL) shl 8) or
            (inBuffer[11].toLong() and 0xFFL)

        val ts = ((inBuffer[12].toLong() and 0xFFL) shl 24) or
            ((inBuffer[13].toLong() and 0xFFL) shl 16) or
            ((inBuffer[14].toLong() and 0xFFL) shl 8) or
            (inBuffer[15].toLong() and 0xFFL)

        outHeader.version = version
        outHeader.packetType = packetType
        outHeader.codecId = inBuffer[4]
        outHeader.senderId = inBuffer[5]
        outHeader.sessionToken = token
        outHeader.sequenceNumber = seq
        outHeader.timestampMs = ts

        return length - PacketHeader.HEADER_SIZE
    }
}
