package com.riderlink.intercom.ride

import com.riderlink.intercom.protocol.QrPayload

enum class RideRole {
    HOST,
    PEER
}

/** How the Ride's local network came about. */
enum class NetworkKind {
    /** The Host formed a private Hotspot. */
    HOTSPOT,

    /** Everyone is on an existing network (a personal hotspot or a LAN). */
    SHARED_WIFI
}

/** How a Peer gets onto the Host's network. */
enum class JoinVia {
    /** Join the Wi-Fi named in the invitation. */
    INVITATION_WIFI,

    /** Stay on the Wi-Fi this phone is already on. */
    CURRENT_WIFI
}

/**
 * What a Host offers so a Peer can join. [wifi] is present only when the Host formed a Hotspot.
 */
data class PairingInvitation(
    val wifi: WifiCredentials?,
    val hostIp: String,
    val port: Int,
    val sessionToken: Int
) {
    val networkKind: NetworkKind
        get() = if (wifi != null) NetworkKind.HOTSPOT else NetworkKind.SHARED_WIFI

    /** The RL1 QR form. A Shared Wi-Fi invitation carries a placeholder SSID and no passphrase. */
    fun toQrPayload(): QrPayload = QrPayload(
        ssid = wifi?.ssid ?: SHARED_WIFI_SSID,
        passphrase = wifi?.passphrase ?: "",
        hostIp = hostIp,
        udpPort = port,
        sessionToken = sessionToken
    )

    companion object {
        const val SHARED_WIFI_SSID = "Manual LAN"

        fun fromQrPayload(qr: QrPayload): PairingInvitation = PairingInvitation(
            wifi = if (qr.ssid == SHARED_WIFI_SSID && qr.passphrase.isEmpty()) {
                null
            } else {
                WifiCredentials(qr.ssid, qr.passphrase)
            },
            hostIp = qr.hostIp,
            port = qr.udpPort,
            sessionToken = qr.sessionToken
        )
    }
}

sealed interface RidePhase {
    data object Idle : RidePhase
    data object FormingNetwork : RidePhase
    data class JoiningNetwork(val attempt: Int = 1) : RidePhase
    data object WaitingForPeers : RidePhase
    data object Linking : RidePhase
    data object Riding : RidePhase
    data object Ending : RidePhase
    data class NetworkLost(val message: String) : RidePhase
    data class Failed(val message: String) : RidePhase
}

enum class LinkStatus {
    LINKED,
    RECONNECTING
}

/** One other phone or Safari page in the Ride, as seen from this phone. */
data class RideLink(
    val kind: LinkKind,
    val status: LinkStatus,
    val onCall: Boolean = false,
    val muted: Boolean = false
)

sealed interface RideOutcome {
    data object EndedByYou : RideOutcome
    data object EndedByHost : RideOutcome
    data object RemovedByHost : RideOutcome
    data class Failed(val message: String) : RideOutcome
}

/** One Rider in the group as this phone shows it. The Host's own entry has [riderId] 0. */
data class RosterRider(
    val riderId: Int,
    val name: String,
    val isHost: Boolean,
    val isYou: Boolean,
    val isSafari: Boolean = false,
    val reconnecting: Boolean = false,
    val speaking: Boolean = false,
    val onCall: Boolean = false,
    val hostMuted: Boolean = false
)

data class RideState(
    val phase: RidePhase = RidePhase.Idle,
    val role: RideRole? = null,
    val networkKind: NetworkKind? = null,
    /** Host: what Peers scan or type. Peer: what this phone joined with. */
    val invitation: PairingInvitation? = null,
    val links: List<RideLink> = emptyList(),
    /** Set when [phase] is Idle after a ride. */
    val lastOutcome: RideOutcome? = null,
    /** Everyone in the ride, this phone included. Built by the Host; a Peer shows the Host's copy. */
    val roster: List<RosterRider> = emptyList(),
    /** The group music playing right now, if any (the Host's, shown to everyone). */
    val nowPlaying: String? = null
) {
    val isIdle: Boolean get() = phase == RidePhase.Idle
    val anyLinked: Boolean get() = links.any { it.status == LinkStatus.LINKED }
    val anyOnCall: Boolean get() = links.any { it.onCall }

    /** Peer side: the Host muted this phone's rider (its voice isn't relayed). */
    val youAreHostMuted: Boolean get() = roster.any { it.isYou && it.hostMuted }
}

data class RideLinkStats(
    val kind: LinkKind,
    val stats: LinkStats
)

data class RideStats(
    val links: List<RideLinkStats> = emptyList()
)
