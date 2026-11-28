package com.riderlink.intercom.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterMessagesTest {

    @Test
    fun rosterRoundTrips() {
        val roster = RosterSync(
            version = 4,
            entries = listOf(
                RosterEntry(0, "Rider (Host)", RosterEntry.HOST or RosterEntry.CONNECTED or RosterEntry.SPEAKING),
                RosterEntry(1, "Ana", RosterEntry.CONNECTED or RosterEntry.ON_CALL),
                RosterEntry(2, "Ben", RosterEntry.RECONNECTING or RosterEntry.HOST_MUTED),
                RosterEntry(10, "iPhone", RosterEntry.CONNECTED or RosterEntry.SAFARI)
            )
        )
        val bytes = roster.encode()
        assertEquals(roster, RosterSync.decode(bytes, 0, bytes.size))
    }

    @Test
    fun aFullGroupFitsInOneDatagram() {
        val entries = (0 until RosterSync.MAX_ENTRIES).map { RosterEntry(it, "x".repeat(40), RosterEntry.CONNECTED) }
        val bytes = RosterSync(1, entries).encode()
        assertTrue("roster is ${bytes.size} B", bytes.size < PacketHeader.MAX_DATAGRAM_SIZE - PacketHeader.HEADER_SIZE - 1)
        assertEquals(32, RosterSync.decode(bytes, 0, bytes.size)!!.entries.first().name.length)
    }

    @Test
    fun truncatedRosterIsRejected() {
        val bytes = RosterSync(1, listOf(RosterEntry(1, "Ana", 1))).encode()
        assertNull(RosterSync.decode(bytes, 0, bytes.size - 2)) // cut into the entry, not just the optional title
    }

    @Test
    fun nowPlayingRidesAlongAtTheEnd_andAnOldRosterStillDecodes() {
        val withMusic = RosterSync(2, listOf(RosterEntry(0, "Kenneth", RosterEntry.HOST)), nowPlaying = "Highway Song")
        val bytes = withMusic.encode()
        assertEquals(withMusic, RosterSync.decode(bytes, 0, bytes.size))

        val old = bytes.copyOf(bytes.size - 1 - "Highway Song".length) // a v0.2 Host sends no title
        assertEquals(null, RosterSync.decode(old, 0, old.size)!!.nowPlaying)
    }

    @Test
    fun aRemovedRiderIsRefused() {
        val ack = JoinAck(JoinAck.REMOVED, CodecId.PCM16_16K, 0)
        val decoded = JoinAck.decode(ack.encode(), 0, 3)!!
        assertTrue(decoded.isRefused)
        assertTrue(decoded.wasRemoved)
    }
}
