package com.riderlink.intercom.ride

import com.riderlink.intercom.protocol.RosterEntry
import com.riderlink.intercom.protocol.RosterSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRosterTest {

    private val scheduler = FakeScheduler()
    private val hotspot = FakeHotspotFormer()
    private val android = FakeGateway(LinkKind.ANDROID, port = 49152)
    private val safari = FakeGateway(LinkKind.SAFARI, port = 8443)
    private val dialer = FakeDialer()

    private val ride = Ride(
        scheduler = scheduler,
        hotspotFormer = hotspot,
        wifiJoiner = FakeWifiJoiner(),
        sharedWifi = FakeSharedWifi(),
        androidGateway = android,
        safariGateway = safari,
        hostDialer = dialer,
        voiceReceiver = RecordingVoiceReceiver(),
        hostRiderName = { "Kenneth" },
        newSessionToken = { 1234 }
    )

    private val state get() = ride.state.value
    private fun drain() = scheduler.runUntilIdle()

    private fun hostWith(vararg riders: Pair<Int, String>): List<FakeLink> {
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.ready(); drain()
        return riders.map { (id, name) -> android.riderJoins(id, name).also { drain() } }
    }

    // --- Host ------------------------------------------------------------------------------

    @Test
    fun theHostsRosterListsItselfAndEveryRiderByName() {
        val (ana, ben) = hostWith(1 to "Ana", 2 to "Ben")

        assertEquals(listOf("Kenneth", "Ana", "Ben"), state.roster.map { it.name })
        assertEquals(listOf(0, 1, 2), state.roster.map { it.riderId })
        assertTrue(state.roster.first().isHost && state.roster.first().isYou)

        val sent = ben.rostersSent.last().entries
        assertEquals(listOf("Kenneth", "Ana", "Ben"), sent.map { it.name })
        assertTrue(sent.first().has(RosterEntry.HOST))
        assertTrue(ana.rostersSent.isNotEmpty())
    }

    @Test
    fun aSafariRiderGetsItsOwnSlotAndName() {
        hostWith(1 to "Ana")
        safari.linkUp(); drain()

        val iphone = state.roster.single { it.isSafari }
        assertEquals(SAFARI_RIDER_ID, iphone.riderId)
        assertEquals("iPhone", iphone.name)
    }

    @Test
    fun speakingAndCallsShowInTheRoster_andTheChangeIsSentAtOnce() {
        val (ana, _) = hostWith(1 to "Ana", 2 to "Ben")
        val before = ana.rostersSent.size

        ride.reportSpeaking(hostTalking = true, talking = setOf(ana)); drain()
        ride.setOnCall(true); drain()

        assertTrue(state.roster.single { it.isHost }.speaking)
        assertTrue(state.roster.single { it.isHost }.onCall)
        assertTrue(state.roster.single { it.name == "Ana" }.speaking)
        assertFalse(state.roster.single { it.name == "Ben" }.speaking)
        assertTrue(ana.rostersSent.size > before)
        assertTrue(ana.rostersSent.last().entries.single { it.name == "Ana" }.has(RosterEntry.SPEAKING))
    }

    @Test
    fun theRosterIsResentEverySecondEvenWhenNothingChanged() {
        val (ana) = hostWith(1 to "Ana")
        val before = ana.rostersSent.size
        val version = ana.rostersSent.last().version

        scheduler.advanceBy(Ride.STATS_INTERVAL_MS)

        assertEquals(before + 1, ana.rostersSent.size)
        assertEquals(version, ana.rostersSent.last().version)
    }

    @Test
    fun hostMute_takesTheRiderOutOfTheRelay_andShowsInTheRoster() {
        val (ana, ben) = hostWith(1 to "Ana", 2 to "Ben")

        ride.setHostMuted(1, true); drain()
        assertTrue(ride.isHostMuted(ana))
        assertFalse(ride.isHostMuted(ben))
        assertTrue(state.roster.single { it.name == "Ana" }.hostMuted)
        assertTrue(ben.rostersSent.last().entries.single { it.name == "Ana" }.has(RosterEntry.HOST_MUTED))

        ride.setHostMuted(1, false); drain()
        assertFalse(ride.isHostMuted(ana))
    }

    @Test
    fun removingARider_tellsIt_andDropsItFromTheRide() {
        val (ana, ben) = hostWith(1 to "Ana", 2 to "Ben")

        ride.remove(1); drain()

        assertEquals(1, ana.removedCount)
        assertEquals(listOf("Kenneth", "Ben"), state.roster.map { it.name })
        assertEquals(1, state.links.size)
        assertEquals(RidePhase.Riding, state.phase)

        ride.remove(2); drain()
        assertEquals(1, ben.removedCount)
        assertEquals(RidePhase.WaitingForPeers, state.phase)
    }

    @Test
    fun aRiderWhoLeavesIsNoLongerMutedOrListed() {
        val (ana, _) = hostWith(1 to "Ana", 2 to "Ben")
        ride.setHostMuted(1, true); drain()

        android.riderLeaves(ana); drain()

        assertFalse(ride.isHostMuted(ana))
        assertEquals(listOf("Kenneth", "Ben"), state.roster.map { it.name })
    }

    // --- Peer ------------------------------------------------------------------------------

    private fun joinAndLink() {
        ride.join(PairingInvitation(null, "192.168.1.20", 49152, 1234), JoinVia.CURRENT_WIFI); drain()
        dialer.linkUp(); drain()
    }

    @Test
    fun aPeerShowsTheHostsRoster_withItsOwnEntryMarked() {
        joinAndLink()

        dialer.roster(
            RosterSync(
                3,
                listOf(
                    RosterEntry(0, "Kenneth", RosterEntry.HOST or RosterEntry.CONNECTED),
                    RosterEntry(1, "Ana", RosterEntry.CONNECTED or RosterEntry.HOST_MUTED),
                    RosterEntry(2, "Ben", RosterEntry.RECONNECTING)
                )
            ),
            yourId = 1
        ); drain()

        assertEquals(listOf("Kenneth", "Ana", "Ben"), state.roster.map { it.name })
        assertTrue(state.roster.single { it.name == "Ana" }.isYou)
        assertTrue(state.youAreHostMuted)
        assertTrue(state.roster.single { it.name == "Ben" }.reconnecting)
        assertTrue(state.roster.single { it.name == "Kenneth" }.isHost)
    }

    @Test
    fun aPeerRemovedByTheHost_endsItsRide() {
        joinAndLink()

        dialer.removed(); drain()

        assertEquals(RidePhase.Idle, state.phase)
        assertEquals(RideOutcome.RemovedByHost, state.lastOutcome)
    }
}
