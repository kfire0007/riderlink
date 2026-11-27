package com.riderlink.intercom.ride.adapters

import com.riderlink.intercom.protocol.CodecId
import com.riderlink.intercom.ride.LinkEvents
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStats
import com.riderlink.intercom.ride.PeerControl
import com.riderlink.intercom.ride.PeerGateway
import com.riderlink.intercom.ride.PeerLink
import com.riderlink.intercom.ride.SAFARI_RIDER_ID
import com.riderlink.intercom.web.LocalWebBridgeServer
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom

/**
 * Host side: accepts one Safari peer through [LocalWebBridgeServer] (HTTPS page + WSS voice).
 *
 * Owns the Safari dialect: `ping:`/`pong:` heartbeats (sent every second here), `ctrl:` controls,
 * and a per-link voice sequence counter. A dropped WebSocket is Reconnecting; Safari's page
 * reconnects by itself and is reported as the same link recovering.
 */
class SafariPeerGateway(
    openKeystore: () -> InputStream,
    keystorePassword: String,
    loadHtmlBytes: () -> ByteArray,
    private val port: Int = LocalWebBridgeServer.DEFAULT_HTTPS_PORT,
    private val log: (String) -> Unit = {}
) : PeerGateway {
    override val kind = LinkKind.SAFARI

    /** Start, stop and every text send run here, in order, off the main thread. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RiderLink-Safari-IO").apply { isDaemon = true }
    }
    private val pingTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RiderLink-Safari-Ping").apply { isDaemon = true }
    }
    private var pingTask: ScheduledFuture<*>? = null

    @Volatile
    private var session: Session? = null

    private inner class Session(val events: LinkEvents) : PeerLink {
        override val kind = LinkKind.SAFARI
        override val riderId: Int = SAFARI_RIDER_ID
        override val riderName: String = "iPhone"

        /** Set when the Host removed this iPhone; its page can't reconnect for the rest of the ride. */
        val removed = AtomicBoolean(false)
        val sequence = AtomicLong(0L)
        val received = AtomicLong(0L)
        val saidGoodbye = AtomicBoolean(false)

        @Volatile
        var rttMs = 0L

        @Volatile
        var address: String? = null

        override fun sendVoice(bytes: ByteArray, offset: Int, length: Int) {
            server.sendAudio(bytes, offset, length)
        }

        override fun sendControl(control: PeerControl) {
            val text = when (control) {
                PeerControl.ON_CALL -> "ctrl:peer_on_call"
                PeerControl.CALL_ENDED -> "ctrl:peer_call_ended"
                PeerControl.MUTED, PeerControl.UNMUTED -> return // the page does not show the Host's mute
            }
            io.execute { server.sendTextControl(text) }
        }

        override fun sayGoodbye() {
            io.execute { server.sendTextControl("ctrl:goodbye") }
        }

        override fun stats() = LinkStats(rttMs = rttMs, packetsReceived = received.get(), address = address)

        override fun remove() {
            if (!removed.compareAndSet(false, true)) return
            io.execute {
                server.sendTextControl("ctrl:goodbye")
                // A token nobody holds: the page's automatic reconnect is refused until the ride ends.
                server.updateSessionToken(10_000 + SecureRandom().nextInt(50_000))
            }
        }
    }

    private val server = LocalWebBridgeServer(
        openKeystore = openKeystore,
        keystorePassword = keystorePassword,
        loadHtmlBytes = loadHtmlBytes,
        listener = object : LocalWebBridgeServer.Listener {
            override fun onClientConnected(clientIp: String) {
                val s = session?.takeUnless { it.removed.get() } ?: return
                s.sequence.set(0L)
                s.saidGoodbye.set(false)
                s.address = clientIp
                s.events.onLinked(s)
            }

            override fun onAudioFrame(pcmBytes: ByteArray, offset: Int, length: Int) {
                val s = session?.takeUnless { it.removed.get() } ?: return
                s.received.incrementAndGet()
                s.events.onVoice(s, CodecId.PCM16_16K, s.sequence.incrementAndGet(), pcmBytes, offset, length)
            }

            override fun onHeartbeat(rttMs: Long?) {
                val s = session ?: return
                if (rttMs != null) s.rttMs = rttMs
            }

            override fun onClientControl(message: String) {
                val s = session?.takeUnless { it.removed.get() } ?: return
                when (message) {
                    "ctrl:goodbye" -> {
                        s.saidGoodbye.set(true)
                        s.events.onGoodbye(s)
                    }
                    "ctrl:muted" -> s.events.onControl(s, PeerControl.MUTED)
                    "ctrl:unmuted" -> s.events.onControl(s, PeerControl.UNMUTED)
                }
            }

            override fun onClientDisconnected() {
                val s = session?.takeUnless { it.removed.get() } ?: return
                // A goodbye already removed the link; the socket closing after it is not a dropout.
                if (!s.saidGoodbye.get()) s.events.onReconnecting(s)
            }
        }
    )

    override fun open(sessionToken: Int, events: LinkEvents): Int {
        session = Session(events)
        io.execute {
            runCatching { server.start(port = port, sessionToken = sessionToken) }
                .onFailure { log("Safari bridge failed to start: ${it.message}") }
        }
        pingTask?.cancel(false)
        pingTask = pingTimer.scheduleAtFixedRate({
            if (server.isClientConnected) {
                io.execute { server.sendPing(System.nanoTime() / 1_000_000L) }
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        return port
    }

    override fun close() {
        session = null
        pingTask?.cancel(false)
        pingTask = null
        io.execute { server.stop() }
    }

    private companion object {
        const val PING_INTERVAL_MS = 1_000L
    }
}
