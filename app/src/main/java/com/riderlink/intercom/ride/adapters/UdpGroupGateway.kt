package com.riderlink.intercom.ride.adapters

import com.riderlink.intercom.network.HeartbeatMonitor
import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.UdpAudioTransport
import com.riderlink.intercom.protocol.CodecId
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.JoinAck
import com.riderlink.intercom.protocol.JoinRequest
import com.riderlink.intercom.protocol.PacketCodec
import com.riderlink.intercom.protocol.PacketHeader
import com.riderlink.intercom.protocol.PacketType
import com.riderlink.intercom.protocol.SenderId
import com.riderlink.intercom.ride.LinkEvents
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStats
import com.riderlink.intercom.ride.PeerControl
import com.riderlink.intercom.ride.PeerGateway
import com.riderlink.intercom.ride.PeerLink
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * Host side of a group ride: one UDP socket, up to [maxPeers] Android Peers, each its own [PeerLink]
 * with its own sender id, heartbeat and sequence numbers (docs/adr/0002).
 *
 * Joining:
 *  - A Phase 2 Peer sends JOIN_REQ (name, flags, rider key) and gets JOIN_ACK with its sender id.
 *    A JOIN_REQ carrying the rider key of an existing member is that rider coming back from a new
 *    address, reported as the same link recovering.
 *  - A Phase 1 Peer never sends JOIN_REQ; its first packet makes it a legacy member named "Rider".
 *    A legacy packet from a new address takes over a Reconnecting legacy member (the Phase 1 slot rule).
 *  - When all [maxPeers] ids are taken, JOIN_REQ gets a refusing JOIN_ACK and legacy packets are ignored.
 *
 * Packets from a member that just said goodbye are ignored for [DEPARTED_QUIET_MS], so its last
 * heartbeats don't bring it straight back as a legacy member.
 */
class UdpGroupGateway(
    private val io: Executor,
    private val port: Int = UdpAudioTransport.DEFAULT_UDP_PORT,
    private val maxPeers: Int = MAX_PEERS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit = {}
) : PeerGateway {
    override val kind = LinkKind.ANDROID

    @Volatile
    private var session: Session? = null

    override fun open(sessionToken: Int, events: LinkEvents): Int {
        close()
        val socket = DatagramSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            try {
                trafficClass = 0xB8
            } catch (_: SocketException) {
            }
            bind(InetSocketAddress(this@UdpGroupGateway.port))
        }
        val s = Session(socket, sessionToken, events)
        session = s
        s.start()
        return socket.localPort
    }

    override fun close() {
        session?.stop()
        session = null
    }

    /** Current members, for tests and diagnostics. */
    internal fun memberCount(): Int = session?.members?.size ?: 0

    private inner class Member(
        @Volatile var address: InetSocketAddress,
        val senderId: Int,
        @Volatile var riderKey: Int,
        @Volatile var name: String,
        @Volatile var legacy: Boolean,
        private val owner: Session
    ) : PeerLink {
        override val kind = LinkKind.ANDROID
        val audioSeq = AtomicLong(0L)

        val heartbeat = HeartbeatMonitor(
            nowMs = nowMs,
            onStateChanged = { state ->
                when (state) {
                    LinkState.CONNECTED -> owner.events.onLinked(this)
                    LinkState.RECONNECTING -> owner.events.onReconnecting(this)
                    else -> Unit
                }
            }
        )

        override fun sendVoice(bytes: ByteArray, offset: Int, length: Int) {
            owner.sendAudio(this, bytes, offset, length)
        }

        override fun sendControl(control: PeerControl) {
            val code = when (control) {
                PeerControl.ON_CALL -> ControlCode.PEER_ON_CALL
                PeerControl.CALL_ENDED -> ControlCode.PEER_CALL_ENDED
                PeerControl.MUTED, PeerControl.UNMUTED -> return
            }
            io.execute { owner.sendControl(this, code, null) }
        }

        override fun sayGoodbye() {
            io.execute { owner.sendControl(this, ControlCode.GOODBYE, null) }
        }

        override fun stats(): LinkStats {
            val s = heartbeat.snapshot()
            return LinkStats(s.rttMs, s.packetLossPercent, s.audioPacketsReceived, "$name ${address.address.hostAddress}")
        }

        override fun toString(): String = "Member($senderId $name${if (legacy) " legacy" else ""})"
    }

    private inner class Session(
        private val socket: DatagramSocket,
        private val sessionToken: Int,
        val events: LinkEvents
    ) {
        @Volatile
        private var running = true
        val members = ConcurrentHashMap<InetSocketAddress, Member>()
        private val departedUntil = ConcurrentHashMap<InetSocketAddress, Long>()
        private val ctrlSeq = AtomicLong(0L)
        private val txLock = Any()
        private val txBuffer = ByteArray(PacketHeader.MAX_DATAGRAM_SIZE)
        private val txPacket = DatagramPacket(txBuffer, 0)
        private val rxBuffer = ByteArray(PacketHeader.MAX_DATAGRAM_SIZE + 64)
        private val rxHeader = PacketHeader()
        private var rxThread: Thread? = null
        private var hbThread: Thread? = null

        fun start() {
            rxThread = Thread({ runRx() }, "RiderLink-Group-RX").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            hbThread = Thread({ runHeartbeat() }, "RiderLink-Group-HB").apply { start() }
            log("Group gateway listening on ${socket.localPort}")
        }

        fun stop() {
            running = false
            runCatching { socket.close() }
            rxThread?.interrupt()
            hbThread?.interrupt()
            members.values.forEach { it.heartbeat.stop() }
            members.clear()
        }

        // --- Receiving ------------------------------------------------------------------------

        private fun runRx() {
            val packet = DatagramPacket(rxBuffer, rxBuffer.size)
            while (running && !socket.isClosed) {
                try {
                    packet.length = rxBuffer.size
                    socket.receive(packet)
                    val payloadLen = PacketCodec.decodeHeader(rxBuffer, packet.length, sessionToken, rxHeader)
                    if (payloadLen < 0) continue
                    val from = packet.socketAddress as? InetSocketAddress ?: continue
                    handle(from, payloadLen)
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    if (!running) break
                }
            }
        }

        private fun handle(from: InetSocketAddress, payloadLen: Int) {
            val now = nowMs()
            val isJoin = rxHeader.packetType == PacketType.CONTROL && payloadLen >= 1 &&
                rxBuffer[PacketHeader.HEADER_SIZE] == ControlCode.JOIN_REQ
            var member = members[from]

            if (isJoin) {
                val req = JoinRequest.decode(rxBuffer, PacketHeader.HEADER_SIZE + 1, payloadLen - 1) ?: return
                departedUntil.remove(from)
                member = member?.also { upgrade(it, req) } ?: rejoinByKey(from, req) ?: admit(from, req)
                if (member == null) {
                    sendTo(from, ControlCode.JOIN_ACK, JoinAck(JoinAck.GROUP_FULL, CodecId.PCM16_16K, 0).encode())
                    return
                }
                sendTo(from, ControlCode.JOIN_ACK, JoinAck(member.senderId, CodecId.PCM16_16K, 0).encode())
                member.heartbeat.recordPacketReceived(now)
                return
            }

            if (member == null) {
                if (rxHeader.packetType == PacketType.PONG) return
                val quietUntil = departedUntil[from]
                if (quietUntil != null) {
                    if (now < quietUntil) return
                    departedUntil.remove(from)
                }
                member = takeOverLegacy(from) ?: admitLegacy(from) ?: return
            }

            when (rxHeader.packetType) {
                PacketType.AUDIO -> {
                    member.heartbeat.recordPacketReceived(now)
                    member.heartbeat.recordAudioSequence(rxHeader.sequenceNumber)
                    events.onVoice(member, rxHeader.sequenceNumber, rxBuffer, PacketHeader.HEADER_SIZE, payloadLen)
                }
                PacketType.PING -> {
                    member.heartbeat.recordPacketReceived(now)
                    sendPong(member.address, rxHeader.timestampMs)
                }
                PacketType.PONG -> member.heartbeat.recordPongReceived(rxHeader.timestampMs, now)
                PacketType.CONTROL -> {
                    member.heartbeat.recordPacketReceived(now)
                    if (payloadLen < 1) return
                    when (rxBuffer[PacketHeader.HEADER_SIZE]) {
                        ControlCode.PEER_ON_CALL -> events.onControl(member, PeerControl.ON_CALL)
                        ControlCode.PEER_CALL_ENDED -> events.onControl(member, PeerControl.CALL_ENDED)
                        ControlCode.GOODBYE -> depart(member, now)
                    }
                }
            }
        }

        private fun upgrade(member: Member, req: JoinRequest) {
            member.name = req.name
            member.riderKey = req.riderKey
            member.legacy = false
        }

        /** The same rider (same key) joining again from a new address: move the member over. */
        private fun rejoinByKey(from: InetSocketAddress, req: JoinRequest): Member? {
            if (req.riderKey == 0) return null
            val existing = members.values.firstOrNull { !it.legacy && it.riderKey == req.riderKey } ?: return null
            moveMember(existing, from)
            existing.name = req.name
            log("Rider ${existing.senderId} '${req.name}' rejoined from $from")
            return existing
        }

        private fun admit(from: InetSocketAddress, req: JoinRequest): Member? {
            val id = freeSenderId() ?: return null
            val member = Member(from, id, req.riderKey, req.name.ifBlank { DEFAULT_NAME }, legacy = false, owner = this)
            members[from] = member
            member.heartbeat.start("${from.address.hostAddress}:${from.port}")
            log("Rider $id '${member.name}' joined from $from")
            return member
        }

        private fun takeOverLegacy(from: InetSocketAddress): Member? {
            val stale = members.values.firstOrNull {
                it.legacy && it.heartbeat.snapshot().linkState == LinkState.RECONNECTING
            } ?: return null
            moveMember(stale, from)
            return stale
        }

        private fun admitLegacy(from: InetSocketAddress): Member? {
            val id = freeSenderId() ?: return null
            val member = Member(from, id, 0, DEFAULT_NAME, legacy = true, owner = this)
            members[from] = member
            member.heartbeat.start("${from.address.hostAddress}:${from.port}")
            log("Legacy rider $id joined from $from")
            return member
        }

        private fun moveMember(member: Member, to: InetSocketAddress) {
            members.remove(member.address)
            member.address = to
            members[to] = member
            member.heartbeat.setPeerAddress("${to.address.hostAddress}:${to.port}")
        }

        private fun depart(member: Member, now: Long) {
            members.remove(member.address)
            departedUntil[member.address] = now + DEPARTED_QUIET_MS
            member.heartbeat.stop()
            log("Rider ${member.senderId} '${member.name}' said goodbye")
            events.onGoodbye(member)
        }

        private fun freeSenderId(): Int? {
            val taken = members.values.mapTo(HashSet()) { it.senderId }
            return (1..maxPeers).firstOrNull { it !in taken }
        }

        // --- Heartbeat ------------------------------------------------------------------------

        private fun runHeartbeat() {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_TICK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val now = nowMs()
                for (member in members.values) {
                    if (member.heartbeat.tick(now)) {
                        sendPing(member.address, now)
                        member.heartbeat.recordPingSent(now)
                    }
                }
                departedUntil.entries.removeIf { now >= it.value }
            }
        }

        // --- Sending --------------------------------------------------------------------------

        fun sendAudio(member: Member, bytes: ByteArray, offset: Int, length: Int) {
            if (!running || members[member.address] !== member) return
            val seq = member.audioSeq.incrementAndGet()
            send(member.address, PacketType.AUDIO, CodecId.PCM16_16K, seq, nowMs() and 0xFFFFFFFFL, bytes, offset, length)
        }

        fun sendControl(member: Member, code: Byte, extra: ByteArray?) {
            sendTo(member.address, code, extra)
        }

        private fun sendTo(to: InetSocketAddress, code: Byte, extra: ByteArray?) {
            val payload = ByteArray(1 + (extra?.size ?: 0))
            payload[0] = code
            extra?.copyInto(payload, 1)
            send(to, PacketType.CONTROL, CodecId.PCM16_16K, ctrlSeq.incrementAndGet(), nowMs() and 0xFFFFFFFFL, payload, 0, payload.size)
        }

        private fun sendPing(to: InetSocketAddress, now: Long) =
            send(to, PacketType.PING, CodecId.PCM16_16K, ctrlSeq.incrementAndGet(), now and 0xFFFFFFFFL, null, 0, 0)

        private fun sendPong(to: InetSocketAddress, echoedTs: Long) =
            send(to, PacketType.PONG, CodecId.PCM16_16K, ctrlSeq.incrementAndGet(), echoedTs, null, 0, 0)

        private fun send(
            to: InetSocketAddress,
            type: Byte,
            codec: Byte,
            seq: Long,
            ts: Long,
            payload: ByteArray?,
            offset: Int,
            length: Int
        ) {
            if (!running) return
            synchronized(txLock) {
                try {
                    val total = PacketCodec.encode(
                        packetType = type,
                        codecId = codec,
                        senderId = SenderId.HOST_RIDER,
                        sessionToken = sessionToken,
                        sequenceNumber = seq,
                        timestampMs = ts,
                        payload = payload,
                        payloadOffset = offset,
                        payloadLength = length,
                        outBuffer = txBuffer
                    )
                    txPacket.setData(txBuffer, 0, total)
                    txPacket.socketAddress = to
                    socket.send(txPacket)
                } catch (_: IOException) {
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    companion object {
        const val MAX_PEERS = 9
        const val DEFAULT_NAME = "Rider"
        const val DEPARTED_QUIET_MS = 2_000L
        private const val HEARTBEAT_TICK_MS = 250L
    }
}
