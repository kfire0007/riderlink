package com.riderlink.intercom.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatMonitorTest {

    @Test
    fun transitionsWaitingConnectedReconnectingAndRecoversSilently() {
        var fakeTime = 1_000L
        val transitions = mutableListOf<LinkState>()
        val monitor = HeartbeatMonitor(
            pingIntervalMs = 1_000L,
            dropoutTimeoutMs = 3_000L,
            nowMs = { fakeTime },
            onStateChanged = { transitions.add(it) }
        )

        monitor.start("192.168.49.1:49152")
        assertEquals(LinkState.WAITING_FOR_PEER, monitor.snapshot().linkState)
        assertTrue(monitor.tick(fakeTime))
        monitor.recordPingSent(fakeTime)

        // Receive PONG 24 ms later
        fakeTime += 24L
        monitor.recordPongReceived(echoedTimestampMs = 1_000L, now = fakeTime)
        assertEquals(LinkState.CONNECTED, monitor.snapshot().linkState)
        assertEquals(24L, monitor.snapshot().rttMs)

        // Before 1000 ms, tick should not ask for another PING
        fakeTime += 500L
        assertFalse(monitor.tick(fakeTime))

        // After 3000 ms without any received packets -> RECONNECTING
        fakeTime += 2_600L
        assertTrue(monitor.tick(fakeTime))
        assertEquals(LinkState.RECONNECTING, monitor.snapshot().linkState)

        // Packet returns -> silent recovery to CONNECTED
        fakeTime += 100L
        monitor.recordPacketReceived(fakeTime)
        assertEquals(LinkState.CONNECTED, monitor.snapshot().linkState)

        assertEquals(
            listOf(
                LinkState.WAITING_FOR_PEER,
                LinkState.CONNECTED,
                LinkState.RECONNECTING,
                LinkState.CONNECTED
            ),
            transitions
        )
    }

    @Test
    fun computesAudioPacketLossPercentage() {
        val monitor = HeartbeatMonitor()
        monitor.start()
        monitor.recordPacketReceived()

        // Sequences 1, 2, 3, 5, 6 (seq 4 lost -> 1 lost out of 6 expected = ~16.67%)
        monitor.recordAudioSequence(1L)
        monitor.recordAudioSequence(2L)
        monitor.recordAudioSequence(3L)
        monitor.recordAudioSequence(5L)
        monitor.recordAudioSequence(6L)

        val stats = monitor.snapshot()
        assertEquals(5L, stats.audioPacketsReceived)
        assertEquals(16.67f, stats.packetLossPercent, 0.2f)
    }
}
