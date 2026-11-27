package com.riderlink.intercom.ride

import com.riderlink.intercom.protocol.CodecId
import com.riderlink.intercom.protocol.RosterSync

/*
 * The seams the Ride talks through. Each has an Android adapter (ride/android/) and a fake
 * (app/src/test/.../ride/RideFakes.kt). Adapters may call back on any thread: the Ride hops
 * every event onto its own scheduler, except voice frames, which pass straight through.
 */

/** Runs ride work one task at a time, in order. Production: the main looper. */
interface RideScheduler {
    fun post(block: () -> Unit)
    fun postDelayed(delayMs: Long, block: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/** Opaque OS network handle a socket can be bound to (Android: android.net.Network). */
interface BoundNetwork

data class WifiCredentials(
    val ssid: String,
    val passphrase: String
)

data class HotspotCredentials(
    val wifi: WifiCredentials,
    val hostIp: String
)

/** Forms the private Hotspot a Host rides on. */
interface HotspotFormer {
    interface Events {
        fun onReady(credentials: HotspotCredentials)
        fun onFailed(reason: String)
        /** The Hotspot went away after [onReady]. */
        fun onLost()
    }

    fun start(events: Events)
    fun stop()
}

data class JoinedNetwork(
    /** The network's gateway, which is the Host when the Host formed a Hotspot. */
    val gatewayIp: String?,
    val bound: BoundNetwork?
)

/** Puts a Peer onto the Host's Hotspot. */
interface WifiJoiner {
    interface Events {
        fun onJoined(network: JoinedNetwork)
        fun onRetrying(attempt: Int)
        fun onFailed(reason: String)
        /** The joined network went away after [onJoined]. */
        fun onLost()
    }

    fun join(wifi: WifiCredentials, events: Events)
    fun stop()
}

/** The Shared Wi-Fi this phone is already on, if any. */
interface SharedWifi {
    /** This phone's address on Shared Wi-Fi, for hosting. */
    fun localIpv4(): String?

    /** The Wi-Fi network this phone is on right now, for joining. */
    fun currentNetwork(): JoinedNetwork?
}

enum class LinkKind {
    ANDROID,
    SAFARI
}

enum class PeerControl {
    ON_CALL,
    CALL_ENDED,
    MUTED,
    UNMUTED
}

data class LinkStats(
    val rttMs: Long = 0L,
    val packetLossPercent: Float = 0f,
    val packetsReceived: Long = 0L,
    val address: String? = null
)

/**
 * The voice connection to one other phone or Safari page in the Ride.
 * Every send is non-blocking: adapters queue or hand off to their own I/O thread.
 */
interface PeerLink {
    val kind: LinkKind

    /** The voice codec this link sends and expects ([CodecId]); PCM16 until a Host agrees on Opus. */
    val codecId: Byte get() = CodecId.PCM16_16K

    /** Host side: the rider's group id (1..9 Android, [SAFARI_RIDER_ID] Safari); -1 if unknown. */
    val riderId: Int get() = -1

    /** Host side: the Rider name this rider introduced itself with; blank if unknown. */
    val riderName: String get() = ""

    /** Host side: send this rider the group roster (non-blocking). */
    fun sendRoster(roster: RosterSync) {}

    /** Host side: tell this rider it was removed, and keep it out until the ride ends. */
    fun remove() {}
    fun sendVoice(bytes: ByteArray, offset: Int, length: Int)
    fun sendControl(control: PeerControl)
    fun sayGoodbye()
    fun stats(): LinkStats
}

/** What a link reports. Called from any thread. */
interface LinkEvents {
    /** First link-up, and every recovery after [onReconnecting]. */
    fun onLinked(link: PeerLink)
    fun onReconnecting(link: PeerLink)
    fun onGoodbye(link: PeerLink)
    fun onControl(link: PeerLink, control: PeerControl)
    /** Hot path: called on the adapter's receive thread. [sequence] is contiguous per link. */
    fun onVoice(link: PeerLink, codecId: Byte, sequence: Long, bytes: ByteArray, offset: Int, length: Int)

    /** The Host refused this Peer (e.g. the group is full). Peer side only. */
    fun onRejected(link: PeerLink, reason: String) {}

    /** Peer side: the Host's roster; [yourId] is this phone's own rider id in it. */
    fun onRoster(link: PeerLink, roster: RosterSync, yourId: Int) {}

    /** Peer side: the Host removed this phone from the ride. */
    fun onRemoved(link: PeerLink) {}
}

/**
 * Host side: accepts links of one [kind]. In Phase 1 each gateway has one link slot; a later
 * arrival takes over the slot as the same [PeerLink] (it is reported as a recovery, not a new link).
 */
interface PeerGateway {
    val kind: LinkKind

    /** Starts accepting. Returns the bound port. Throws if it cannot listen. */
    fun open(sessionToken: Int, events: LinkEvents): Int
    fun close()
}

/** Peer side: the single link to the Host. */
interface HostDialer {
    /** Throws if it cannot start. The link reports [LinkEvents.onLinked] once the Host answers. */
    fun dial(hostIp: String, port: Int, sessionToken: Int, network: BoundNetwork?, events: LinkEvents): PeerLink
    fun hangUp()
}

/** Receives voice frames from every link, on the link's receive thread. */
fun interface VoiceReceiver {
    fun onVoice(link: PeerLink, codecId: Byte, sequence: Long, bytes: ByteArray, offset: Int, length: Int)
}

/** The Safari peer's fixed rider id in a group roster (after the Android ids 1..9). */
const val SAFARI_RIDER_ID = 10
