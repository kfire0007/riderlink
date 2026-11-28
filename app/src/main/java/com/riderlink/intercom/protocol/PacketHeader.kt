package com.riderlink.intercom.protocol

/**
 * Fixed 16-byte big-endian UDP packet header layout for RiderLink.
 *
 * Byte offsets:
 *  0..1  magic (0x524C = "RL")
 *  2     version (1)
 *  3     packetType (AUDIO, PING, PONG, CONTROL)
 *  4     codecId (PCM16_16K, OPUS)
 *  5     senderId (0 = Host/Rider, 1 = OBR, 2..N = Phase 2 peers)
 *  6..7  sessionToken (uint16)
 *  8..11 sequenceNumber (uint32)
 * 12..15 timestampMs (uint32)
 */
object PacketType {
    const val AUDIO: Byte = 0x01
    const val PING: Byte = 0x02
    const val PONG: Byte = 0x03
    const val CONTROL: Byte = 0x04

    fun isValid(value: Byte): Boolean =
        value == AUDIO || value == PING || value == PONG || value == CONTROL
}

object ControlCode {
    const val PEER_ON_CALL: Byte = 0x03
    const val PEER_CALL_ENDED: Byte = 0x04
    const val GOODBYE: Byte = 0x05

    /** Phase 2 group join: Peer → Host, payload [JoinRequest]. Old Hosts ignore it. */
    const val JOIN_REQ: Byte = 0x10

    /** Phase 2 group join: Host → Peer, payload [JoinAck]. */
    const val JOIN_ACK: Byte = 0x11

    /** Host → Peers, payload [RosterSync]: who is in the group and what each rider is doing. */
    const val ROSTER_SYNC: Byte = 0x12

    /** Host → one Peer, no payload: you were removed from this ride. */
    const val HOST_KICK_PEER: Byte = 0x14

    /** Peer → Host, payload `[muted:1][volume0to100:1]`: how loud this rider wants the group music. */
    const val MUSIC_PREF: Byte = 0x15
}

object CodecId {
    const val PCM16_16K: Byte = 0x01
    /** Opus, 16 kHz mono, 20 ms frames (Phase 2). */
    const val OPUS: Byte = 0x02
}

object SenderId {
    const val HOST_RIDER: Byte = 0x00
    const val OBR: Byte = 0x01
}

class PacketHeader(
    var version: Byte = VERSION,
    var packetType: Byte = PacketType.AUDIO,
    var codecId: Byte = CodecId.PCM16_16K,
    var senderId: Byte = SenderId.HOST_RIDER,
    var sessionToken: Int = 0,
    var sequenceNumber: Long = 0L,
    var timestampMs: Long = 0L
) {
    companion object {
        const val MAGIC: Int = 0x524C
        const val VERSION: Byte = 1
        const val HEADER_SIZE: Int = 16
        const val MAX_AUDIO_PAYLOAD_SIZE: Int = 640
        const val MAX_DATAGRAM_SIZE: Int = HEADER_SIZE + MAX_AUDIO_PAYLOAD_SIZE
    }
}
