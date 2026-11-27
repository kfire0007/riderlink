package com.riderlink.intercom.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTest {

    private val scheduler = FakeScheduler()
    private val hotspot = FakeHotspotFormer()
    private val joiner = FakeWifiJoiner()
    private val sharedWifi = FakeSharedWifi()
    private val android = FakeGateway(LinkKind.ANDROID, port = 49152)
    private val safari = FakeGateway(LinkKind.SAFARI, port = 8443)
    private val dialer = FakeDialer()
    private val voice = RecordingVoiceReceiver()
    private val tokens = ArrayDeque(listOf(1111, 2222, 3333, 4444))

    private val ride = Ride(
        scheduler = scheduler,
        hotspotFormer = hotspot,
        wifiJoiner = joiner,
        sharedWifi = sharedWifi,
        androidGateway = android,
        safariGateway = safari,
        hostDialer = dialer,
        voiceReceiver = voice,
        newSessionToken = { tokens.removeFirst() }
    )

    private val state get() = ride.state.value

    private fun drain() = scheduler.runUntilIdle()

    private fun hostOnHotspotAndLinkAndroid() {
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.ready(); drain()
        android.linkUp(); drain()
    }

    private val hotspotInvitation = PairingInvitation(
        wifi = WifiCredentials("DIRECT-rl", "secret12"),
        hostIp = "192.168.49.1",
        port = 49152,
        sessionToken = 1234
    )

    // --- Hosting -------------------------------------------------------------------------

    @Test
    fun hostOnHotspot_publishesInvitationAndOpensBothGateways() {
        ride.host(NetworkKind.HOTSPOT); drain()
        assertEquals(RidePhase.FormingNetwork, state.phase)
        assertEquals(RideRole.HOST, state.role)

        hotspot.ready(hostIp = "192.168.49.1"); drain()

        assertEquals(RidePhase.WaitingForPeers, state.phase)
        assertEquals(
            PairingInvitation(WifiCredentials("DIRECT-rl", "secret12"), "192.168.49.1", 49152, 1111),
            state.invitation
        )
        assertEquals(listOf(1111), android.openedWithTokens)
        assertEquals(listOf(1111), safari.openedWithTokens)
    }

    @Test
    fun hostOnSharedWifi_usesLocalAddressAndNoWifiCredentials() {
        ride.host(NetworkKind.SHARED_WIFI); drain()

        assertEquals(RidePhase.WaitingForPeers, state.phase)
        assertEquals(PairingInvitation(null, "192.168.1.20", 49152, 1111), state.invitation)
        assertEquals(0, hotspot.startCount)
    }

    @Test
    fun hostOnSharedWifi_withoutAnAddress_fails() {
        sharedWifi.localIp = null
        ride.host(NetworkKind.SHARED_WIFI); drain()
        assertTrue(state.phase is RidePhase.Failed)
    }

    @Test
    fun hostingTheSameKindAgain_doesNothing() {
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.ready(); drain()
        ride.host(NetworkKind.HOTSPOT); drain()

        assertEquals(1, hotspot.startCount)
        assertEquals(RidePhase.WaitingForPeers, state.phase)
    }

    @Test
    fun hostingAnotherKind_endsTheRideThenHostsAfterTeardown() {
        hostOnHotspotAndLinkAndroid()

        ride.host(NetworkKind.SHARED_WIFI); drain()
        assertEquals(RidePhase.Ending, state.phase)
        assertEquals(1, android.link.goodbyes)

        scheduler.advanceBy(Ride.GOODBYE_GRACE_MS)
        assertEquals(RidePhase.WaitingForPeers, state.phase)
        assertEquals(NetworkKind.SHARED_WIFI, state.networkKind)
        assertTrue(hotspot.stopCount >= 1)
        assertEquals(listOf(1111, 2222), android.openedWithTokens)
    }

    @Test
    fun androidGatewayCannotListen_fails() {
        android.failOpen = true
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.ready(); drain()

        assertTrue(state.phase is RidePhase.Failed)
        assertTrue(hotspot.stopCount >= 1)
    }

    @Test
    fun safariGatewayCannotListen_rideStillHosts() {
        safari.failOpen = true
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.ready(); drain()
        assertEquals(RidePhase.WaitingForPeers, state.phase)
    }

    @Test
    fun hotspotFailsToForm_fails_andRetryFormsAgain() {
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.fail("No Wi-Fi channel"); drain()
        assertEquals(RidePhase.Failed("No Wi-Fi channel"), state.phase)

        ride.retry(); drain()
        assertEquals(RidePhase.FormingNetwork, state.phase)
        assertEquals(2, hotspot.startCount)
    }

    // --- Links at the Host ---------------------------------------------------------------

    @Test
    fun firstLink_startsRiding_andReconnectingKeepsTheSameLink() {
        hostOnHotspotAndLinkAndroid()
        assertEquals(RidePhase.Riding, state.phase)
        assertEquals(listOf(RideLink(LinkKind.ANDROID, LinkStatus.LINKED)), state.links)

        android.reconnecting(); drain()
        assertEquals(listOf(RideLink(LinkKind.ANDROID, LinkStatus.RECONNECTING)), state.links)
        assertEquals(RidePhase.Riding, state.phase)

        android.linkUp(); drain()
        assertEquals(listOf(RideLink(LinkKind.ANDROID, LinkStatus.LINKED)), state.links)
    }

    @Test
    fun safariAndAndroidLinkedAtOnce_voiceFlowsBothWaysPerLink() {
        hostOnHotspotAndLinkAndroid()
        safari.linkUp(); drain()
        assertEquals(setOf(LinkKind.ANDROID, LinkKind.SAFARI), state.links.map { it.kind }.toSet())

        android.voice(7, byteArrayOf(1, 2))
        safari.voice(1, byteArrayOf(3))
        assertEquals(2, voice.frames.size)
        assertSame(android.link, voice.frames[0].link)
        assertEquals(7L, voice.frames[0].sequence)
        assertSame(safari.link, voice.frames[1].link)

        ride.sendVoice(byteArrayOf(9, 9, 9), 0, 3)
        assertEquals(1, android.link.voiceSent.size)
        assertEquals(1, safari.link.voiceSent.size)
    }

    @Test
    fun voiceFromAPeerThatSaidGoodbye_isDropped() {
        hostOnHotspotAndLinkAndroid()
        android.goodbye(); drain()
        android.voice(8, byteArrayOf(1))
        assertTrue(voice.frames.isEmpty())
    }

    @Test
    fun peerGoodbyeAtHost_removesLink_andHostWaitsWithTheSameInvitation() {
        hostOnHotspotAndLinkAndroid()
        val invitation = state.invitation

        android.goodbye(); drain()

        assertEquals(RidePhase.WaitingForPeers, state.phase)
        assertTrue(state.links.isEmpty())
        assertEquals(invitation, state.invitation)
        assertTrue(android.isOpen)

        android.linkUp(); drain()
        assertEquals(RidePhase.Riding, state.phase)
    }

    @Test
    fun peerControls_updateItsOnCallAndMutedFlags() {
        hostOnHotspotAndLinkAndroid()
        android.control(PeerControl.ON_CALL); drain()
        assertTrue(state.links.single().onCall)
        android.control(PeerControl.CALL_ENDED); drain()
        assertFalse(state.links.single().onCall)

        safari.linkUp(); drain()
        safari.control(PeerControl.MUTED); drain()
        assertTrue(state.links.single { it.kind == LinkKind.SAFARI }.muted)
    }

    @Test
    fun localCall_isToldToEveryLink_andToLinksThatArriveDuringIt() {
        hostOnHotspotAndLinkAndroid()
        ride.setOnCall(true); drain()
        assertEquals(listOf(PeerControl.ON_CALL), android.link.controlsSent)

        safari.linkUp(); drain()
        assertEquals(listOf(PeerControl.ON_CALL), safari.link.controlsSent)

        ride.setOnCall(false); drain()
        assertEquals(listOf(PeerControl.ON_CALL, PeerControl.CALL_ENDED), android.link.controlsSent)
    }

    // --- Network lost & retry ------------------------------------------------------------

    @Test
    fun hotspotLostWhileRiding_isNetworkLost_andRetryIssuesANewInvitation() {
        hostOnHotspotAndLinkAndroid()

        hotspot.lose(); drain()

        assertTrue(state.phase is RidePhase.NetworkLost)
        assertTrue(state.links.isEmpty())
        assertFalse(android.isOpen)
        assertFalse(safari.isOpen)

        ride.retry(); drain()
        hotspot.ready(ssid = "DIRECT-new"); drain()
        assertEquals(2222, state.invitation!!.sessionToken)
        assertEquals("DIRECT-new", state.invitation!!.wifi!!.ssid)
    }

    @Test
    fun sharedWifiRetry_keepsTheSessionToken() {
        android.failOpen = true
        ride.host(NetworkKind.SHARED_WIFI); drain()
        assertTrue(state.phase is RidePhase.Failed)

        android.failOpen = false
        ride.retry(); drain()
        assertEquals(1111, state.invitation!!.sessionToken)
    }

    @Test
    fun hostingTheSameKindAfterAFailure_retries() {
        android.failOpen = true
        ride.host(NetworkKind.SHARED_WIFI); drain()
        assertTrue(state.phase is RidePhase.Failed)

        android.failOpen = false
        ride.host(NetworkKind.SHARED_WIFI); drain()
        assertEquals(RidePhase.WaitingForPeers, state.phase)
        assertEquals(1111, state.invitation!!.sessionToken)
    }

    // --- Joining -------------------------------------------------------------------------

    @Test
    fun joinViaInvitationWifi_dialsTheGatewayOnTheJoinedNetwork() {
        ride.join(hotspotInvitation, JoinVia.INVITATION_WIFI); drain()
        assertEquals(RidePhase.JoiningNetwork(1), state.phase)
        assertEquals(RideRole.PEER, state.role)
        assertEquals(listOf(WifiCredentials("DIRECT-rl", "secret12")), joiner.joined)

        joiner.events!!.onJoined(JoinedNetwork("192.168.49.254", FakeBoundNetwork)); drain()

        assertEquals(RidePhase.Linking, state.phase)
        assertEquals(FakeDialer.Dial("192.168.49.254", 49152, 1234, FakeBoundNetwork), dialer.dials.single())

        dialer.linkUp(); drain()
        assertEquals(RidePhase.Riding, state.phase)
        assertEquals(listOf(RideLink(LinkKind.ANDROID, LinkStatus.LINKED)), state.links)
    }

    @Test
    fun joinedNetworkWithoutGateway_dialsTheInvitationAddress() {
        ride.join(hotspotInvitation, JoinVia.INVITATION_WIFI); drain()
        joiner.events!!.onJoined(JoinedNetwork(null, FakeBoundNetwork)); drain()
        assertEquals("192.168.49.1", dialer.dials.single().hostIp)
    }

    @Test
    fun joinHotspotInvitationOnCurrentWifi_dialsTheCurrentGateway() {
        sharedWifi.current = JoinedNetwork("192.168.49.254", FakeBoundNetwork)
        ride.join(hotspotInvitation, JoinVia.CURRENT_WIFI); drain()

        assertTrue(joiner.joined.isEmpty())
        assertEquals("192.168.49.254", dialer.dials.single().hostIp)
    }

    @Test
    fun joinSharedWifiInvitation_dialsTheInvitationAddressNotTheRouter() {
        val shared = PairingInvitation(null, "192.168.1.20", 49152, 5678)
        ride.join(shared, JoinVia.CURRENT_WIFI); drain()

        assertEquals(FakeDialer.Dial("192.168.1.20", 49152, 5678, FakeBoundNetwork), dialer.dials.single())
    }

    @Test
    fun joinSharedWifiInvitationViaInvitationWifi_staysOnCurrentWifi() {
        val shared = PairingInvitation(null, "192.168.1.20", 49152, 5678)
        ride.join(shared, JoinVia.INVITATION_WIFI); drain()

        assertTrue(joiner.joined.isEmpty())
        assertEquals("192.168.1.20", dialer.dials.single().hostIp)
    }

    @Test
    fun joinRetrying_reportsTheAttempt_andFailureCanBeRetried() {
        ride.join(hotspotInvitation, JoinVia.INVITATION_WIFI); drain()
        joiner.events!!.onRetrying(2); drain()
        assertEquals(RidePhase.JoiningNetwork(2), state.phase)

        joiner.events!!.onFailed("Could not connect"); drain()
        assertEquals(RidePhase.Failed("Could not connect"), state.phase)

        ride.retry(); drain()
        assertEquals(RidePhase.JoiningNetwork(1), state.phase)
        assertEquals(2, joiner.joined.size)
    }

    @Test
    fun dialFailure_fails() {
        dialer.failDial = true
        ride.join(hotspotInvitation, JoinVia.CURRENT_WIFI); drain()
        assertTrue(state.phase is RidePhase.Failed)
    }

    @Test
    fun backPressedWhileJoining_endsAtOnce_andLateWifiCallbacksAreIgnored() {
        ride.join(hotspotInvitation, JoinVia.INVITATION_WIFI); drain()
        val staleEvents = joiner.events!!

        ride.end(); drain()
        assertEquals(RidePhase.Idle, state.phase)
        assertEquals(RideOutcome.EndedByYou, state.lastOutcome)
        assertTrue(joiner.stopCount >= 1)

        staleEvents.onJoined(JoinedNetwork("192.168.49.254", FakeBoundNetwork)); drain()
        assertTrue(dialer.dials.isEmpty())
        assertEquals(RidePhase.Idle, state.phase)
    }

    @Test
    fun peerWifiLostWhileRiding_isNetworkLost_andRetryRejoins() {
        ride.join(hotspotInvitation, JoinVia.INVITATION_WIFI); drain()
        joiner.events!!.onJoined(JoinedNetwork("192.168.49.254", FakeBoundNetwork)); drain()
        dialer.linkUp(); drain()

        joiner.events!!.onLost(); drain()
        assertTrue(state.phase is RidePhase.NetworkLost)
        assertTrue(dialer.hangUpCount >= 1)
        assertTrue(state.links.isEmpty())

        ride.retry(); drain()
        assertEquals(2, joiner.joined.size)
    }

    @Test
    fun hostGoodbyeAtPeer_endsTheRideWithoutSayingGoodbyeBack() {
        ride.join(hotspotInvitation, JoinVia.CURRENT_WIFI); drain()
        dialer.linkUp(); drain()
        val link = dialer.link

        dialer.goodbye(); drain()

        assertEquals(RidePhase.Idle, state.phase)
        assertEquals(RideOutcome.EndedByHost, state.lastOutcome)
        assertEquals(0, link.goodbyes)
        assertTrue(dialer.hangUpCount >= 1)
    }

    @Test
    fun joiningWhileHosting_endsTheHostRideFirst() {
        hostOnHotspotAndLinkAndroid()
        ride.join(hotspotInvitation, JoinVia.CURRENT_WIFI); drain()
        assertEquals(RidePhase.Ending, state.phase)
        assertTrue(dialer.dials.isEmpty())

        scheduler.advanceBy(Ride.GOODBYE_GRACE_MS)
        assertEquals(RideRole.PEER, state.role)
        assertEquals(1, dialer.dials.size)
    }

    // --- Ending --------------------------------------------------------------------------

    @Test
    fun endWhileRiding_saysGoodbyeToEveryLink_thenReleasesEverything() {
        hostOnHotspotAndLinkAndroid()
        safari.linkUp(); drain()

        ride.end(); drain()
        assertEquals(RidePhase.Ending, state.phase)
        assertEquals(1, android.link.goodbyes)
        assertEquals(1, safari.link.goodbyes)
        assertTrue(android.isOpen)

        scheduler.advanceBy(Ride.GOODBYE_GRACE_MS)
        assertEquals(RidePhase.Idle, state.phase)
        assertEquals(RideOutcome.EndedByYou, state.lastOutcome)
        assertNull(state.role)
        assertTrue(state.links.isEmpty())
        assertFalse(android.isOpen)
        assertFalse(safari.isOpen)
        assertTrue(hotspot.stopCount >= 1)
    }

    @Test
    fun hostAgainDuringEnding_waitsForTeardown() {
        hostOnHotspotAndLinkAndroid()
        ride.end(); drain()
        ride.host(NetworkKind.HOTSPOT); drain()

        assertEquals(RidePhase.Ending, state.phase)
        assertEquals(1, hotspot.startCount)

        scheduler.advanceBy(Ride.GOODBYE_GRACE_MS)
        assertEquals(RidePhase.FormingNetwork, state.phase)
        assertEquals(2, hotspot.startCount)
        assertNull(state.lastOutcome)
    }

    @Test
    fun endAfterAFailure_recordsTheFailureAsTheOutcome() {
        ride.host(NetworkKind.HOTSPOT); drain()
        hotspot.fail("Hotspot disallowed"); drain()

        ride.end(); drain()
        assertEquals(RidePhase.Idle, state.phase)
        assertEquals(RideOutcome.Failed("Hotspot disallowed"), state.lastOutcome)
    }

    @Test
    fun retryWhileHealthy_doesNothing() {
        hostOnHotspotAndLinkAndroid()
        ride.retry(); drain()
        assertEquals(RidePhase.Riding, state.phase)
        assertEquals(1, hotspot.startCount)
    }

    @Test
    fun endWhenIdle_doesNothing() {
        ride.end(); drain()
        assertEquals(RideState(), state)
    }

    @Test
    fun voiceSentAfterTheRideEnds_goesNowhere() {
        hostOnHotspotAndLinkAndroid()
        ride.end(); drain()
        scheduler.advanceBy(Ride.GOODBYE_GRACE_MS)

        ride.sendVoice(byteArrayOf(1), 0, 1)
        assertTrue(android.link.voiceSent.isEmpty())
    }

    // --- Stats ---------------------------------------------------------------------------

    @Test
    fun statsAreSampledFromEveryLinkOnceASecond() {
        hostOnHotspotAndLinkAndroid()
        android.link.linkStats = LinkStats(rttMs = 42, address = "192.168.49.7:40000")

        scheduler.advanceBy(Ride.STATS_INTERVAL_MS)

        assertEquals(
            RideStats(listOf(RideLinkStats(LinkKind.ANDROID, LinkStats(rttMs = 42, address = "192.168.49.7:40000")))),
            ride.stats.value
        )
    }
}
