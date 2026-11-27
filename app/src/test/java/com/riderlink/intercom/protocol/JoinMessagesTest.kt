package com.riderlink.intercom.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinMessagesTest {

    @Test
    fun joinRequestRoundTrips() {
        val req = JoinRequest("Bike 2 · Ana", JoinRequest.FLAG_ANDROID, riderKey = -123456789)
        val bytes = req.encode()
        assertEquals(req, JoinRequest.decode(bytes, 0, bytes.size))
    }

    @Test
    fun joinRequestCutsLongNamesWithoutSplittingCharacters() {
        val req = JoinRequest("🏍".repeat(20), 0, 1)
        val bytes = req.encode()
        val decoded = JoinRequest.decode(bytes, 0, bytes.size)!!
        assertTrue(decoded.name.toByteArray(Charsets.UTF_8).size <= JoinRequest.MAX_NAME_BYTES)
        assertEquals("🏍".repeat(8), decoded.name)
    }

    @Test
    fun joinRequestWithoutRiderKeyDecodesWithZeroKey() {
        val bytes = byteArrayOf(3, 'A'.code.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 1)
        assertEquals(JoinRequest("Ana", 1, 0), JoinRequest.decode(bytes, 0, bytes.size))
    }

    @Test
    fun truncatedJoinRequestIsRejected() {
        val bytes = byteArrayOf(10, 'A'.code.toByte())
        assertNull(JoinRequest.decode(bytes, 0, bytes.size))
    }

    @Test
    fun joinAckRoundTripsAndFlagsAFullGroup() {
        val ack = JoinAck(3, CodecId.PCM16_16K, 7)
        val bytes = ack.encode()
        assertEquals(ack, JoinAck.decode(bytes, 0, bytes.size))

        val full = JoinAck(JoinAck.GROUP_FULL, CodecId.PCM16_16K, 0)
        assertTrue(JoinAck.decode(full.encode(), 0, 3)!!.isRefused)
    }
}
