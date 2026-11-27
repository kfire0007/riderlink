package com.riderlink.intercom.ride

import com.riderlink.intercom.protocol.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One Ride: pairing, the local network, the links to other phones, and ending.
 *
 * Commands may be called from any thread; they run in order on [scheduler], which is the only
 * place ride state changes. Adapter events hop onto [scheduler] too, and events from anything
 * this Ride has already let go of are dropped. Voice is the exception: [sendVoice] and incoming
 * frames never touch ride state and never wait for the scheduler.
 *
 * See docs/adr/0001 for why this lives at app scope rather than inside the foreground service.
 */
class Ride(
    private val scheduler: RideScheduler,
    private val hotspotFormer: HotspotFormer,
    private val wifiJoiner: WifiJoiner,
    private val sharedWifi: SharedWifi,
    private val androidGateway: PeerGateway,
    private val safariGateway: PeerGateway?,
    private val hostDialer: HostDialer,
    private val voiceReceiver: VoiceReceiver,
    private val newSessionToken: () -> Int = SessionToken::generate,
    private val log: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RideStats())
    val stats: StateFlow<RideStats> = _stats.asStateFlow()

    private sealed interface Command {
        /** [sessionToken] null means "issue a new one". */
        data class Host(val kind: NetworkKind, val sessionToken: Int? = null) : Command
        data class Join(val invitation: PairingInvitation, val via: JoinVia) : Command
    }

    // Everything below is touched only on the scheduler, except where marked @Volatile.

    /** Bumped whenever the Ride lets go of its adapters; callbacks from an older epoch are dropped. */
    private var epoch = 0L
    private var currentCommand: Command? = null
    private var queuedCommand: Command? = null
    private val links = LinkedHashMap<PeerLink, RideLink>()
    private var localOnCall = false
    private var goodbyeTimer: Cancellable? = null
    private var statsTimer: Cancellable? = null

    /** Links that may send and receive voice. Read on audio threads. */
    @Volatile
    private var voiceLinks: List<PeerLink> = emptyList()

    // --- Commands --------------------------------------------------------------------------

    /** Hosts on [kind]. Does nothing if already hosting on [kind]; retries if that ride failed. */
    fun host(kind: NetworkKind) = scheduler.post { onHost(kind) }

    fun join(invitation: PairingInvitation, via: JoinVia) =
        scheduler.post { submit(Command.Join(invitation, via)) }

    /** Repeats the command behind a Failed or Network lost ride. Does nothing otherwise. */
    fun retry() = scheduler.post { onRetry() }

    fun end() = scheduler.post { onEnd() }

    /** Tells every link (and every link that arrives later) whether this phone is on a call. */
    fun setOnCall(onCall: Boolean) = scheduler.post { onSetOnCall(onCall) }

    /** Hot path, any thread: sends one voice frame to every link. Never blocks. */
    fun sendVoice(bytes: ByteArray, offset: Int, length: Int) {
        for (link in voiceLinks) link.sendVoice(bytes, offset, length)
    }

    // --- Command handling ------------------------------------------------------------------

    private fun onHost(kind: NetworkKind) {
        val s = _state.value
        if (s.role == RideRole.HOST && s.networkKind == kind) {
            when (s.phase) {
                RidePhase.FormingNetwork, RidePhase.WaitingForPeers, RidePhase.Riding -> return
                is RidePhase.Failed, is RidePhase.NetworkLost -> {
                    onRetry()
                    return
                }
                else -> Unit
            }
        }
        submit(Command.Host(kind))
    }

    private fun submit(command: Command) {
        when (_state.value.phase) {
            RidePhase.Idle -> start(command)
            RidePhase.Ending -> queuedCommand = command
            else -> {
                queuedCommand = command
                beginEnding(RideOutcome.EndedByYou)
            }
        }
    }

    private fun onRetry() {
        val phase = _state.value.phase
        if (phase !is RidePhase.Failed && phase !is RidePhase.NetworkLost) return
        val command = currentCommand ?: return
        val again = when (command) {
            // Android hands out a new SSID and passphrase for every Hotspot, so the old token is no use.
            is Command.Host -> if (command.kind == NetworkKind.HOTSPOT) command.copy(sessionToken = null) else command
            is Command.Join -> command
        }
        start(again)
    }

    private fun onEnd() {
        queuedCommand = null
        when (val phase = _state.value.phase) {
            RidePhase.Idle, RidePhase.Ending -> Unit
            is RidePhase.Failed -> finishIdle(RideOutcome.Failed(phase.message))
            is RidePhase.NetworkLost -> finishIdle(RideOutcome.Failed(phase.message))
            else -> beginEnding(RideOutcome.EndedByYou)
        }
    }

    private fun onSetOnCall(onCall: Boolean) {
        if (localOnCall == onCall) return
        localOnCall = onCall
        val control = if (onCall) PeerControl.ON_CALL else PeerControl.CALL_ENDED
        links.keys.forEach { it.sendControl(control) }
    }

    // --- Starting --------------------------------------------------------------------------

    private fun start(command: Command) {
        releaseAll()
        val myEpoch = epoch
        startStatsTimer(myEpoch)
        when (command) {
            is Command.Host -> {
                val token = command.sessionToken ?: newSessionToken()
                currentCommand = command.copy(sessionToken = token)
                _state.value = RideState(
                    phase = RidePhase.FormingNetwork,
                    role = RideRole.HOST,
                    networkKind = command.kind
                )
                when (command.kind) {
                    NetworkKind.HOTSPOT -> hotspotFormer.start(hotspotEvents(myEpoch, token))
                    NetworkKind.SHARED_WIFI -> {
                        val ip = sharedWifi.localIpv4()
                        if (ip == null) {
                            fail("No active Wi-Fi or Hotspot IP found. Enable your phone Hotspot or connect both phones to the same Wi-Fi.")
                        } else {
                            openGateways(myEpoch, wifi = null, hostIp = ip, token = token)
                        }
                    }
                }
            }
            is Command.Join -> {
                currentCommand = command
                val invitation = command.invitation
                val joinWifi = invitation.wifi
                _state.value = RideState(
                    phase = if (command.via == JoinVia.INVITATION_WIFI && joinWifi != null) {
                        RidePhase.JoiningNetwork(1)
                    } else {
                        RidePhase.Linking
                    },
                    role = RideRole.PEER,
                    networkKind = invitation.networkKind,
                    invitation = invitation
                )
                if (command.via == JoinVia.INVITATION_WIFI && joinWifi != null) {
                    wifiJoiner.join(joinWifi, joinEvents(myEpoch, invitation))
                } else {
                    dialHost(myEpoch, invitation, sharedWifi.currentNetwork())
                }
            }
        }
    }

    private fun openGateways(myEpoch: Long, wifi: WifiCredentials?, hostIp: String, token: Int) {
        val port = try {
            androidGateway.open(token, linkEvents(myEpoch))
        } catch (e: Exception) {
            fail("Failed to listen for riders: ${e.message}")
            return
        }
        safariGateway?.let { gateway ->
            runCatching { gateway.open(token, linkEvents(myEpoch)) }
                .onFailure { log("Safari peers unavailable for this ride: ${it.message}") }
        }
        _state.value = _state.value.copy(
            phase = RidePhase.WaitingForPeers,
            invitation = PairingInvitation(wifi, hostIp, port, token)
        )
    }

    private fun dialHost(myEpoch: Long, invitation: PairingInvitation, network: JoinedNetwork?) {
        // On a Hotspot the Host is the network's gateway, which is more reliable than the address
        // in the invitation. On Shared Wi-Fi the gateway is just the router.
        val target = if (invitation.wifi != null) network?.gatewayIp ?: invitation.hostIp else invitation.hostIp
        _state.value = _state.value.copy(phase = RidePhase.Linking)
        try {
            hostDialer.dial(target, invitation.port, invitation.sessionToken, network?.bound, linkEvents(myEpoch))
        } catch (e: Exception) {
            fail("Failed to start the voice link: ${e.message}")
        }
    }

    // --- Ending and failing ----------------------------------------------------------------

    private fun beginEnding(outcome: RideOutcome) {
        val goodbyeTo = links.keys.toList()
        epoch++ // ignore link events from here on
        voiceLinks = emptyList()
        _state.value = _state.value.copy(phase = RidePhase.Ending)
        if (goodbyeTo.isEmpty()) {
            finishEnding(outcome)
            return
        }
        goodbyeTo.forEach { it.sayGoodbye() }
        goodbyeTimer = scheduler.postDelayed(GOODBYE_GRACE_MS) { finishEnding(outcome) }
    }

    private fun finishEnding(outcome: RideOutcome) {
        goodbyeTimer = null
        val next = queuedCommand
        queuedCommand = null
        if (next != null) start(next) else finishIdle(outcome)
    }

    private fun finishIdle(outcome: RideOutcome) {
        releaseAll()
        currentCommand = null
        _state.value = RideState(lastOutcome = outcome)
    }

    private fun fail(message: String) = stopWith(RidePhase.Failed(message))

    private fun networkLost(message: String) = stopWith(RidePhase.NetworkLost(message))

    /** Lets go of everything but keeps the ride (role, network kind) so it can be retried. */
    private fun stopWith(phase: RidePhase) {
        log("Ride stopped: $phase")
        releaseAll()
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            links = emptyList(),
            invitation = if (s.role == RideRole.HOST) null else s.invitation
        )
    }

    private fun releaseAll() {
        epoch++
        goodbyeTimer?.cancel()
        goodbyeTimer = null
        statsTimer?.cancel()
        statsTimer = null
        hotspotFormer.stop()
        wifiJoiner.stop()
        hostDialer.hangUp()
        androidGateway.close()
        safariGateway?.close()
        links.clear()
        voiceLinks = emptyList()
        _stats.value = RideStats()
    }

    // --- Links -----------------------------------------------------------------------------

    private fun onLinked(link: PeerLink) {
        val existing = links[link]
        log("${link.kind} link ${if (existing == null) "linked" else "recovered"}")
        links[link] = existing?.copy(status = LinkStatus.LINKED) ?: RideLink(link.kind, LinkStatus.LINKED)
        if (existing == null && localOnCall) link.sendControl(PeerControl.ON_CALL)
        publishLinks()
        val phase = _state.value.phase
        if (phase == RidePhase.WaitingForPeers || phase == RidePhase.Linking) {
            _state.value = _state.value.copy(phase = RidePhase.Riding)
        }
    }

    private fun onReconnecting(link: PeerLink) {
        val existing = links[link] ?: return
        log("${link.kind} link reconnecting")
        links[link] = existing.copy(status = LinkStatus.RECONNECTING)
        publishLinks()
    }

    private fun onGoodbye(link: PeerLink) {
        if (link !in links) return
        log("${link.kind} link said goodbye")
        if (_state.value.role == RideRole.PEER) {
            finishIdle(RideOutcome.EndedByHost)
            return
        }
        links.remove(link)
        publishLinks()
        if (links.isEmpty() && _state.value.phase == RidePhase.Riding) {
            _state.value = _state.value.copy(phase = RidePhase.WaitingForPeers)
        }
    }

    private fun onControl(link: PeerLink, control: PeerControl) {
        val existing = links[link] ?: return
        log("${link.kind} link control $control")
        links[link] = when (control) {
            PeerControl.ON_CALL -> existing.copy(onCall = true)
            PeerControl.CALL_ENDED -> existing.copy(onCall = false)
            PeerControl.MUTED -> existing.copy(muted = true)
            PeerControl.UNMUTED -> existing.copy(muted = false)
        }
        publishLinks()
    }

    private fun publishLinks() {
        voiceLinks = links.keys.toList()
        _state.value = _state.value.copy(links = links.values.toList())
    }

    // --- Stats -----------------------------------------------------------------------------

    private fun startStatsTimer(myEpoch: Long) {
        statsTimer = scheduler.postDelayed(STATS_INTERVAL_MS) {
            if (myEpoch != epoch) return@postDelayed
            _stats.value = RideStats(links.keys.map { RideLinkStats(it.kind, it.stats()) })
            startStatsTimer(myEpoch)
        }
    }

    // --- Adapter events, hopped onto the scheduler and dropped once stale --------------------

    private fun onScheduler(myEpoch: Long, block: () -> Unit) = scheduler.post {
        if (myEpoch == epoch) block()
    }

    private fun hotspotEvents(myEpoch: Long, token: Int) = object : HotspotFormer.Events {
        override fun onReady(credentials: HotspotCredentials) = onScheduler(myEpoch) {
            openGateways(myEpoch, credentials.wifi, credentials.hostIp, token)
        }

        override fun onFailed(reason: String) = onScheduler(myEpoch) { fail(reason) }

        override fun onLost() = onScheduler(myEpoch) {
            networkLost("Android stopped the Hotspot.")
        }
    }

    private fun joinEvents(myEpoch: Long, invitation: PairingInvitation) = object : WifiJoiner.Events {
        override fun onJoined(network: JoinedNetwork) = onScheduler(myEpoch) {
            dialHost(myEpoch, invitation, network)
        }

        override fun onRetrying(attempt: Int) = onScheduler(myEpoch) {
            _state.value = _state.value.copy(phase = RidePhase.JoiningNetwork(attempt))
        }

        override fun onFailed(reason: String) = onScheduler(myEpoch) { fail(reason) }

        override fun onLost() = onScheduler(myEpoch) {
            networkLost("Lost the Wi-Fi link to the Host. Stay close to the Host's phone.")
        }
    }

    private fun linkEvents(myEpoch: Long) = object : LinkEvents {
        override fun onLinked(link: PeerLink) = onScheduler(myEpoch) { this@Ride.onLinked(link) }
        override fun onReconnecting(link: PeerLink) = onScheduler(myEpoch) { this@Ride.onReconnecting(link) }
        override fun onGoodbye(link: PeerLink) = onScheduler(myEpoch) { this@Ride.onGoodbye(link) }
        override fun onControl(link: PeerLink, control: PeerControl) =
            onScheduler(myEpoch) { this@Ride.onControl(link, control) }

        override fun onVoice(link: PeerLink, sequence: Long, bytes: ByteArray, offset: Int, length: Int) {
            if (link in voiceLinks) voiceReceiver.onVoice(link, sequence, bytes, offset, length)
        }
    }

    companion object {
        /** How long a ride waits for its goodbyes to go out before releasing the network. */
        const val GOODBYE_GRACE_MS = 250L
        const val STATS_INTERVAL_MS = 1_000L
    }
}
