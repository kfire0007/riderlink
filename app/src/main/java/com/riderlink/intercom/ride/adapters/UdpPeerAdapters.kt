package com.riderlink.intercom.ride.adapters

import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.UdpAudioTransport
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.JoinAck
import com.riderlink.intercom.protocol.JoinRequest
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.SenderId
import com.riderlink.intercom.ride.BoundNetwork
import com.riderlink.intercom.ride.HostDialer
import com.riderlink.intercom.ride.LinkEvents
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStats
import com.riderlink.intercom.ride.PeerControl
import com.riderlink.intercom.ride.PeerLink
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A Peer's link to its Host over [UdpAudioTransport]. Voice goes out on the caller's thread
 * (a single UDP send); controls and goodbye go out on [io] so the main thread never sends.
 * The Host side of group rides is [UdpGroupGateway].
 */
private class UdpPeerLink(private val io: Executor) : PeerLink {
    lateinit var transport: UdpAudioTransport

    /** Set once a Phase 2 Host answers JOIN_REQ; an old Host never does. */
    val joinAcked = AtomicBoolean(false)

    override val kind = LinkKind.ANDROID

    override fun sendVoice(bytes: ByteArray, offset: Int, length: Int) {
        transport.sendAudio(bytes, offset, length)
    }

    override fun sendControl(control: PeerControl) {
        val code = when (control) {
            PeerControl.ON_CALL -> ControlCode.PEER_ON_CALL
            PeerControl.CALL_ENDED -> ControlCode.PEER_CALL_ENDED
            PeerControl.MUTED, PeerControl.UNMUTED -> return // not part of the UDP protocol yet
        }
        io.execute { runCatching { transport.sendControl(code) } }
    }

    override fun sayGoodbye() {
        io.execute { runCatching { transport.sendControl(ControlCode.GOODBYE) } }
    }

    override fun stats(): LinkStats {
        val s = transport.currentStats()
        return LinkStats(
            rttMs = s.rttMs,
            packetLossPercent = s.packetLossPercent,
            packetsReceived = s.audioPacketsReceived,
            address = s.peerAddress
        )
    }

    fun listener(events: LinkEvents) = object : UdpAudioTransport.Listener {
        override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {
            events.onVoice(this@UdpPeerLink, header.sequenceNumber, payload, offset, length)
        }

        override fun onControlPacket(
            header: PacketHeader,
            controlCode: Byte,
            payload: ByteArray,
            offset: Int,
            length: Int
        ) {
            if (controlCode != ControlCode.JOIN_ACK) return onControlPacket(header, controlCode)
            val ack = JoinAck.decode(payload, offset, length) ?: return
            if (!joinAcked.compareAndSet(false, true)) return
            if (ack.isRefused) {
                events.onRejected(this@UdpPeerLink, "The group is full. Ask the rider to remove someone, then try again.")
            } else {
                transport.senderId = ack.assignedSenderId.toByte()
            }
        }

        override fun onControlPacket(header: PacketHeader, controlCode: Byte) {
            when (controlCode) {
                ControlCode.PEER_ON_CALL -> events.onControl(this@UdpPeerLink, PeerControl.ON_CALL)
                ControlCode.PEER_CALL_ENDED -> events.onControl(this@UdpPeerLink, PeerControl.CALL_ENDED)
                ControlCode.GOODBYE -> events.onGoodbye(this@UdpPeerLink)
            }
        }

        override fun onLinkStateChanged(state: LinkState) {
            when (state) {
                LinkState.CONNECTED -> events.onLinked(this@UdpPeerLink)
                LinkState.RECONNECTING -> events.onReconnecting(this@UdpPeerLink)
                else -> Unit
            }
        }
    }
}

/**
 * Peer side: dials the Host over UDP, bound to the joined Wi-Fi so mobile data stays untouched.
 * Introduces itself with JOIN_REQ ([identity]) until the Host answers; an old (Phase 1) Host never
 * answers, and the link works as before.
 */
class UdpHostDialer(
    private val io: Executor,
    private val identity: () -> JoinRequest
) : HostDialer {
    private var transport: UdpAudioTransport? = null
    private val joinTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RiderLink-Join").apply { isDaemon = true }
    }
    private var joinTask: ScheduledFuture<*>? = null

    override fun dial(
        hostIp: String,
        port: Int,
        sessionToken: Int,
        network: BoundNetwork?,
        events: LinkEvents
    ): PeerLink {
        hangUp()
        val link = UdpPeerLink(io)
        val udp = UdpAudioTransport(SenderId.OBR, sessionToken, link.listener(events))
        link.transport = udp
        try {
            udp.start(
                localPort = 0,
                initialPeer = InetSocketAddress(hostIp, port),
                boundNetwork = (network as? AndroidBoundNetwork)?.network
            )
        } catch (e: Exception) {
            udp.stop()
            throw e
        }
        transport = udp
        startJoinHandshake(link, udp)
        return link
    }

    override fun hangUp() {
        joinTask?.cancel(false)
        joinTask = null
        transport?.stop()
        transport = null
    }

    private fun startJoinHandshake(link: UdpPeerLink, udp: UdpAudioTransport) {
        val request = identity().encode()
        var attempts = 0
        joinTask?.cancel(false)
        joinTask = joinTimer.scheduleWithFixedDelay({
            if (link.joinAcked.get() || attempts >= JOIN_ATTEMPTS) {
                joinTask?.cancel(false)
                return@scheduleWithFixedDelay
            }
            attempts++
            io.execute { runCatching { udp.sendControl(ControlCode.JOIN_REQ, request) } }
        }, 0L, JOIN_RETRY_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val JOIN_RETRY_MS = 700L
        const val JOIN_ATTEMPTS = 12
    }
}
