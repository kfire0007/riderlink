package com.riderlink.intercom.ride.adapters

import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.UdpAudioTransport
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.SenderId
import com.riderlink.intercom.ride.BoundNetwork
import com.riderlink.intercom.ride.HostDialer
import com.riderlink.intercom.ride.LinkEvents
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStats
import com.riderlink.intercom.ride.PeerControl
import com.riderlink.intercom.ride.PeerGateway
import com.riderlink.intercom.ride.PeerLink
import java.net.InetSocketAddress
import java.util.concurrent.Executor

/**
 * The link to an Android phone over [UdpAudioTransport]. Voice goes out on the caller's thread
 * (a single UDP send); controls and goodbye go out on [io] so the main thread never sends.
 *
 * Slot rule (Phase 1, no peer identity yet): while this link is Reconnecting, a sender with the
 * same session token from a new address takes it over and is reported as this link recovering.
 */
private class UdpPeerLink(private val io: Executor) : PeerLink {
    lateinit var transport: UdpAudioTransport

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

/** Host side: listens on [port] for one Android Peer. */
class UdpPeerGateway(
    private val io: Executor,
    private val port: Int = UdpAudioTransport.DEFAULT_UDP_PORT
) : PeerGateway {
    override val kind = LinkKind.ANDROID
    private var transport: UdpAudioTransport? = null

    override fun open(sessionToken: Int, events: LinkEvents): Int {
        close()
        val link = UdpPeerLink(io)
        val udp = UdpAudioTransport(SenderId.HOST_RIDER, sessionToken, link.listener(events))
        link.transport = udp
        try {
            udp.start(localPort = port, initialPeer = null, boundNetwork = null)
        } catch (e: Exception) {
            udp.stop()
            throw e
        }
        transport = udp
        return udp.boundPort
    }

    override fun close() {
        transport?.stop()
        transport = null
    }
}

/** Peer side: dials the Host over UDP, bound to the joined Wi-Fi so mobile data stays untouched. */
class UdpHostDialer(private val io: Executor) : HostDialer {
    private var transport: UdpAudioTransport? = null

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
        return link
    }

    override fun hangUp() {
        transport?.stop()
        transport = null
    }
}
