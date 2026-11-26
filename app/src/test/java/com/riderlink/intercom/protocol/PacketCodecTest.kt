package com.riderlink.intercom.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketCodecTest {

    @Test
    fun roundTripAudioPacketBigEndian() {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val buffer = ByteArray(PacketHeader.MAX_DATAGRAM_SIZE)
        val seq = 0x8000002AL // exercises uint32 high bit
        val ts = 0xF0001234L

        val totalLen = PacketCodec.encode(
            packetType = PacketType.AUDIO,
            codecId = CodecId.PCM16_16K,
            senderId = SenderId.OBR,
            sessionToken = 4821,
            sequenceNumber = seq,
            timestampMs = ts,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
            outBuffer = buffer
        )

        assertEquals(PacketHeader.HEADER_SIZE + payload.size, totalLen)
        // Verify big-endian magic "RL"
        assertEquals(0x52.toByte(), buffer[0])
        assertEquals(0x4C.toByte(), buffer[1])

        val decoded = PacketHeader()
        val payloadLen = PacketCodec.decodeHeader(buffer, totalLen, 4821, decoded)
        assertEquals(payload.size, payloadLen)
        assertEquals(PacketHeader.VERSION, decoded.version)
        assertEquals(PacketType.AUDIO, decoded.packetType)
        assertEquals(CodecId.PCM16_16K, decoded.codecId)
        assertEquals(SenderId.OBR, decoded.senderId)
        assertEquals(4821, decoded.sessionToken)
        assertEquals(seq, decoded.sequenceNumber)
        assertEquals(ts, decoded.timestampMs)

        val extracted = buffer.copyOfRange(PacketHeader.HEADER_SIZE, totalLen)
        assertArrayEquals(payload, extracted)
    }

    @Test
    fun roundTripEmptyPingAndControlPackets() {
        val buffer = ByteArray(PacketHeader.HEADER_SIZE + 1)
        val pingLen = PacketCodec.encode(
            packetType = PacketType.PING,
            codecId = CodecId.PCM16_16K,
            senderId = SenderId.HOST_RIDER,
            sessionToken = 1001,
            sequenceNumber = 7L,
            timestampMs = 5000L,
            payload = null,
            payloadOffset = 0,
            payloadLength = 0,
            outBuffer = buffer
        )
        assertEquals(PacketHeader.HEADER_SIZE, pingLen)
        val header = PacketHeader()
        assertEquals(0, PacketCodec.decodeHeader(buffer, pingLen, 1001, header))
        assertEquals(PacketType.PING, header.packetType)

        val ctrlPayload = byteArrayOf(ControlCode.PEER_ON_CALL)
        val ctrlLen = PacketCodec.encode(
            packetType = PacketType.CONTROL,
            codecId = CodecId.PCM16_16K,
            senderId = SenderId.OBR,
            sessionToken = 1001,
            sequenceNumber = 8L,
            timestampMs = 5500L,
            payload = ctrlPayload,
            payloadOffset = 0,
            payloadLength = 1,
            outBuffer = buffer
        )
        assertEquals(1, PacketCodec.decodeHeader(buffer, ctrlLen, 1001, header))
        assertEquals(PacketType.CONTROL, header.packetType)
        assertEquals(ControlCode.PEER_ON_CALL, buffer[PacketHeader.HEADER_SIZE])
    }

    @Test
    fun rejectsBadMagicVersionTypeLengthAndToken() {
        val buffer = ByteArray(PacketHeader.HEADER_SIZE)
        val len = PacketCodec.encode(
            packetType = PacketType.PONG,
            codecId = CodecId.PCM16_16K,
            senderId = SenderId.HOST_RIDER,
            sessionToken = 9999,
            sequenceNumber = 1L,
            timestampMs = 1L,
            payload = null,
            payloadOffset = 0,
            payloadLength = 0,
            outBuffer = buffer
        )
        val header = PacketHeader()

        // Truncated
        assertEquals(-1, PacketCodec.decodeHeader(buffer, 15, 9999, header))
        // Mismatched token
        assertEquals(-1, PacketCodec.decodeHeader(buffer, len, 1234, header))

        // Bad magic
        val badMagic = buffer.copyOf()
        badMagic[0] = 0x00
        assertEquals(-1, PacketCodec.decodeHeader(badMagic, len, 9999, header))

        // Bad version
        val badVer = buffer.copyOf()
        badVer[2] = 99
        assertEquals(-1, PacketCodec.decodeHeader(badVer, len, 9999, header))

        // Bad packet type
        val badType = buffer.copyOf()
        badType[3] = 0x7F
        assertEquals(-1, PacketCodec.decodeHeader(badType, len, 9999, header))
    }
}
