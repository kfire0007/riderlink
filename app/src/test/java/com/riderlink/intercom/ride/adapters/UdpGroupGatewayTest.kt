package com.riderlink.intercom.ride.adapters

import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.UdpAudioTransport
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.JoinAck
import com.riderlink.intercom.protocol.JoinRequest
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.SenderId
import com.riderlink.intercom.ride.LinkEvents
import com.riderlink.intercom.ride.PeerControl
import com.riderlink.intercom.ride.PeerLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class UdpGroupGatewayTest {

    private val token = 4242
    private val gateway = UdpGroupGateway(io = { it.run() }, port = 0)
    private val events = RecordingEvents()
    private val peers = mutableListOf<FakePeer>()

    @After
    fun tearDown() {
        peers.forEach { it.udp.stop() }
        gateway.close()
    }

    private fun open(g: UdpGroupGateway = gateway): Int = g.open(token, events)

    private fun peer(port: Int): FakePeer = FakePeer(port, token).also { peers += it }

    @Test
    fun twoPeersJoinWithTheirOwnIdsAndLinks() {
        val port = open()
        val a = peer(port).also { it.join("Ana", key = 1) }
        val b = peer(port).also { it.join("Ben", key = 2) }

        eventually { a.ack != null && b.ack != null && events.linked.size == 2 }
        assertEquals(setOf(1, 2), setOf(a.ack!!.assignedSenderId, b.ack!!.assignedSenderId))
        assertNotSame(events.linked[0], events.linked[1])
        assertEquals(2, gateway.memberCount())
    }

    @Test
    fun voiceArrivesOnTheSendersLink_andTheHostCanAnswerOneRider() {
        val port = open()
        val a = peer(port).also { it.join("Ana", key = 1) }
        val b = peer(port).also { it.join("Ben", key = 2) }
        eventually { events.linked.size == 2 && a.ack != null && b.ack != null }

        a.udp.sendAudio(ByteArray(640) { 7 }, 0, 640)
        eventually { events.voice.isNotEmpty() }
        val aLink = events.voice.first().first
        assertEquals(7.toByte(), events.voice.first().second[0])

        aLink.sendVoice(ByteArray(640) { 9 }, 0, 640)
        eventually { a.audio.isNotEmpty() }
        Thread.sleep(200)
        assertTrue("Ben must not get Ana's private mix", b.audio.isEmpty())
        assertEquals(9.toByte(), a.audio.first()[0])
    }

    @Test
    fun aPhase1PeerWithoutJoinIsAdmittedAsALegacyRider() {
        val port = open()
        val legacy = peer(port)
        legacy.udp.sendAudio(ByteArray(640), 0, 640)

        eventually { events.linked.size == 1 }
        assertEquals(1, gateway.memberCount())
        assertTrue(events.linked.single().stats().address!!.startsWith(UdpGroupGateway.DEFAULT_NAME))
    }

    @Test
    fun theSameRiderRejoiningFromANewAddressKeepsItsLink() {
        val port = open()
        val first = peer(port).also { it.join("Ana", key = 77) }
        eventually { events.linked.size == 1 && first.ack != null }
        val link = events.linked.single()
        first.udp.stop()

        val again = peer(port).also { it.join("Ana", key = 77) }
        eventually { again.ack != null }
        assertEquals(first.ack!!.assignedSenderId, again.ack!!.assignedSenderId)
        assertEquals(1, gateway.memberCount())

        again.udp.sendAudio(ByteArray(640), 0, 640)
        eventually { events.voice.isNotEmpty() }
        assertSame(link, events.voice.last().first)
    }

    @Test
    fun goodbyeRemovesTheRider_andItsLastHeartbeatsDoNotBringItBack() {
        val port = open()
        val a = peer(port).also { it.join("Ana", key = 1) }
        eventually { events.linked.size == 1 }

        a.udp.sendControl(ControlCode.GOODBYE)
        eventually { events.goodbyes.size == 1 }
        assertEquals(0, gateway.memberCount())

        a.udp.sendAudio(ByteArray(640), 0, 640)
        Thread.sleep(400)
        assertEquals(0, gateway.memberCount())
        assertEquals(1, events.linked.size)
    }

    @Test
    fun callControlsReachTheRidersLink() {
        val port = open()
        val a = peer(port).also { it.join("Ana", key = 1) }
        eventually { events.linked.size == 1 }

        a.udp.sendControl(ControlCode.PEER_ON_CALL)
        eventually { events.controls.isNotEmpty() }
        assertEquals(PeerControl.ON_CALL, events.controls.single().second)
    }

    @Test
    fun aFullGroupRefusesTheNextRider() {
        val small = UdpGroupGateway(io = { it.run() }, port = 0, maxPeers = 1)
        try {
            val port = small.open(token, events)
            val a = peer(port).also { it.join("Ana", key = 1) }
            eventually { a.ack != null }
            val b = peer(port).also { it.join("Ben", key = 2) }
            eventually { b.ack != null }

            assertTrue(b.ack!!.isRefused)
            assertEquals(1, small.memberCount())
        } finally {
            small.close()
        }
    }

    // --- Helpers ---------------------------------------------------------------------------

    private class FakePeer(hostPort: Int, token: Int) {
        @Volatile
        var ack: JoinAck? = null
        val audio = CopyOnWriteArrayList<ByteArray>()

        val udp = UdpAudioTransport(
            senderId = SenderId.OBR,
            sessionToken = token,
            listener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {
                    audio += payload.copyOfRange(offset, offset + length)
                }

                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {}

                override fun onControlPacket(header: PacketHeader, controlCode: Byte, payload: ByteArray, offset: Int, length: Int) {
                    if (controlCode == ControlCode.JOIN_ACK) ack = JoinAck.decode(payload, offset, length)
                }

                override fun onLinkStateChanged(state: LinkState) {}
            }
        ).also { it.start(localPort = 0, initialPeer = InetSocketAddress("127.0.0.1", hostPort)) }

        fun join(name: String, key: Int) {
            udp.sendControl(ControlCode.JOIN_REQ, JoinRequest(name, JoinRequest.FLAG_ANDROID, key).encode())
        }
    }

    private class RecordingEvents : LinkEvents {
        val linked = CopyOnWriteArrayList<PeerLink>()
        val goodbyes = CopyOnWriteArrayList<PeerLink>()
        val controls = CopyOnWriteArrayList<Pair<PeerLink, PeerControl>>()
        val voice = CopyOnWriteArrayList<Pair<PeerLink, ByteArray>>()

        override fun onLinked(link: PeerLink) {
            if (link !in linked) linked += link
        }

        override fun onReconnecting(link: PeerLink) {}
        override fun onGoodbye(link: PeerLink) {
            goodbyes += link
        }

        override fun onControl(link: PeerLink, control: PeerControl) {
            controls += link to control
        }

        override fun onVoice(link: PeerLink, sequence: Long, bytes: ByteArray, offset: Int, length: Int) {
            voice += link to bytes.copyOfRange(offset, offset + length)
        }
    }

    private fun eventually(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met within $timeoutMs ms", condition())
    }
}
