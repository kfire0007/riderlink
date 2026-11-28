package com.riderlink.intercom.voice

import android.content.Context
import com.riderlink.intercom.audio.ChimeType
import com.riderlink.intercom.audio.GroupMixing
import com.riderlink.intercom.audio.IntercomAudioEngine
import com.riderlink.intercom.protocol.CodecId
import com.riderlink.intercom.ride.PeerLink
import com.riderlink.intercom.ride.Ride
import com.riderlink.intercom.ride.RideOutcome
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideRole
import com.riderlink.intercom.ride.RideState
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.HardwareButtonController
import com.riderlink.intercom.service.HeadsetRouteManager
import com.riderlink.intercom.service.PhoneCallInterruptionHandler
import com.riderlink.intercom.service.TalkControlState
import com.riderlink.intercom.service.TalkMode
import com.riderlink.intercom.service.TalkModeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The voice side of a Ride: audio engine, talk control, headset routing, phone-call pause and
 * hardware buttons, all app-scoped (docs/adr/0001).
 *
 * It reacts to the Ride's state: the audio pipeline runs while the ride is live, and link changes
 * play chimes and mute/resync incoming audio. This glue is temporary; architecture candidate #04
 * (a deep voice pipeline) absorbs it, and #06 (talk control) absorbs the button and chime rules.
 *
 * Main thread only, except [onVoice] (link receive threads) and the engine's own audio threads.
 */
class RideVoice(
    context: Context,
    private val ride: Ride,
    private val scope: CoroutineScope
) {
    private val talk = TalkModeController()
    val talkControlState: StateFlow<TalkControlState> = talk.state

    private val engine = IntercomAudioEngine(
        // A Peer has one Link, to its Host; send the mic in whatever codec that Link agreed.
        txCodecId = { ride.voiceTargets.firstOrNull()?.codecId ?: CodecId.PCM16_16K },
        shouldTransmit = { talk.shouldTransmitMic() },
        isBypassNoiseGate = {
            val s = talk.state.value
            s.mode == TalkMode.PUSH_TO_TALK && s.isPttTransmitting
        },
        onEncodedFrame = { bytes, offset, length, _ -> ride.sendVoice(bytes, offset, length) }
    )

    /** While hosting, the engine mixes for every link the Ride currently has. */
    private val hostMixing = object : GroupMixing {
        override fun riders(): List<Any> = ride.voiceTargets

        override fun codecFor(rider: Any): Byte = (rider as PeerLink).codecId

        override fun isMuted(rider: Any): Boolean = ride.isHostMuted(rider as PeerLink)

        override fun send(rider: Any, bytes: ByteArray, offset: Int, length: Int, codecId: Byte) {
            ride.sendVoiceTo(rider as PeerLink, bytes, offset, length)
        }
    }

    private val headset = HeadsetRouteManager(context)
    val audioRouteState: StateFlow<AudioRouteState> = headset.routeState

    private val callHandler = PhoneCallInterruptionHandler(context) { onLocalCallChanged(it) }

    private val buttons = HardwareButtonController(
        context = context,
        getTalkMode = { talk.state.value.mode },
        onPttPressedChanged = { setPttPressed(it) },
        onTogglePtt = { togglePtt() },
        onToggleMute = { toggleMute() }
    )

    private var pipelineStarted = false
    private var hasLinkedOnce = false
    private var wasLinked = false
    private var wasPeerOnCall = false

    /** Stops the pipeline once a goodbye chime has had time to play; cancelled if a ride starts. */
    private var deferredStop: Job? = null

    /** While hosting: reports who's speaking to the Ride a few times a second, for the roster. */
    private var speakingReporter: Job? = null

    val jitterDepthFrames: Int
        get() = engine.jitterBuffer.currentDepthFrames

    /** While hosting: how long the last 20 ms mix tick took (mixing + encoding), in microseconds. */
    val mixTickMicros: Long
        get() = engine.lastMixTickMicros

    init {
        scope.launch { ride.state.collect { onRideState(it) } }
        // The mic follows the headset: the engine records from the headset's own mic and reports
        // back which mic Android really used, for the ride screen.
        engine.onMicRouted = { headset.onMicRouted(it) }
        scope.launch { headset.preferredMic.collect { engine.setPreferredMic(it) } }
    }

    /** Hot path from the Ride: one incoming voice frame. */
    fun onVoice(link: PeerLink, codecId: Byte, sequence: Long, bytes: ByteArray, offset: Int, length: Int) {
        engine.onIncomingAudio(link, codecId, sequence, bytes, offset, length)
    }

    // --- Talk controls ---------------------------------------------------------------------

    fun setTalkMode(mode: TalkMode) = talk.setTalkMode(mode)

    fun toggleMute() {
        val muted = talk.toggleMute()
        if (pipelineStarted) engine.playChime(if (muted) ChimeType.MUTED else ChimeType.UNMUTED)
    }

    fun setPttPressed(pressed: Boolean) {
        val was = talk.state.value.isPttTransmitting
        talk.setPttPressed(pressed)
        if (pipelineStarted && was != pressed) {
            engine.playChime(if (pressed) ChimeType.PTT_DOWN else ChimeType.PTT_UP)
        }
    }

    fun togglePtt() {
        val active = talk.togglePtt()
        if (pipelineStarted) engine.playChime(if (active) ChimeType.PTT_DOWN else ChimeType.PTT_UP)
    }

    fun setMicGateThreshold(rms: Int) {
        talk.setMicGateThreshold(rms)
        engine.setNoiseGateThreshold(rms)
    }

    fun handleHardwareKeyDown(keyCode: Int, repeatCount: Int): Boolean = buttons.onKeyDown(keyCode, repeatCount)

    fun handleHardwareKeyUp(keyCode: Int): Boolean = buttons.onKeyUp(keyCode)

    // --- Reacting to the Ride --------------------------------------------------------------

    private fun onRideState(state: RideState) {
        if (state.isIdle || state.phase == RidePhase.Ending) {
            if (state.isIdle && state.lastOutcome == RideOutcome.EndedByHost && pipelineStarted && deferredStop == null) {
                // The rider may have the phone in a pocket: say the ride is over in-ear, and stop the
                // pipeline only after the chime has played (stopping clears queued chimes).
                engine.remoteAudioMuted = true
                engine.playChime(ChimeType.LINK_LOST)
                deferredStop = scope.launch {
                    delay(GOODBYE_CHIME_MS)
                    deferredStop = null
                    stopPipeline()
                }
            } else if (deferredStop == null) {
                stopPipeline()
            }
            hasLinkedOnce = false
            wasLinked = false
            wasPeerOnCall = false
            // Reset on Ending too: a ride that replaces another (tab switch, join while hosting)
            // goes Ending -> next ride without passing Idle, and must not inherit mute or call state.
            if (talk.state.value.isLocalCallPaused) ride.setOnCall(false)
            talk.reset()
            return
        }
        deferredStop?.let {
            it.cancel()
            deferredStop = null
            stopPipeline() // start the next ride from a clean pipeline
        }

        val linked = state.anyLinked
        if (linked && !wasLinked) {
            startPipeline(state.role)
            engine.remoteAudioMuted = false
            engine.playChime(if (hasLinkedOnce) ChimeType.RECONNECTED else ChimeType.CONNECTED)
            hasLinkedOnce = true
        } else if (!linked && wasLinked && pipelineStarted) {
            // Every link is reconnecting, said goodbye, or the network went away.
            engine.remoteAudioMuted = true
            engine.resetJitterBuffer()
            engine.playChime(ChimeType.LINK_LOST)
        }
        wasLinked = linked

        val peerOnCall = state.anyOnCall
        if (peerOnCall != wasPeerOnCall) {
            wasPeerOnCall = peerOnCall
            talk.setPeerOnCall(peerOnCall)
            if (pipelineStarted) engine.playChime(if (peerOnCall) ChimeType.PEER_ON_CALL else ChimeType.RECONNECTED)
        }
    }

    private fun startPipeline(role: RideRole?) {
        if (pipelineStarted) return
        pipelineStarted = true
        // The Host mixes per rider (mix-minus); a Peer plays the Host's one stream.
        engine.groupMixing = if (role == RideRole.HOST) hostMixing else null
        if (role == RideRole.HOST) startSpeakingReporter()
        headset.start()
        engine.setNoiseGateThreshold(talk.state.value.micGateThresholdRms)
        engine.start()
        callHandler.start()
        buttons.start()
    }

    private fun stopPipeline() {
        if (!pipelineStarted) return
        pipelineStarted = false
        speakingReporter?.cancel()
        speakingReporter = null
        buttons.stop()
        callHandler.stop()
        engine.stop()
        headset.stop()
    }

    private fun startSpeakingReporter() {
        speakingReporter?.cancel()
        speakingReporter = scope.launch {
            var last: Pair<Boolean, Set<Any>>? = null
            while (true) {
                val now = engine.groupSpeaking()
                if (now != last) {
                    last = now
                    @Suppress("UNCHECKED_CAST")
                    ride.reportSpeaking(now.first, now.second as Set<PeerLink>)
                }
                delay(SPEAKING_REPORT_MS)
            }
        }
    }

    private fun onLocalCallChanged(callActive: Boolean) {
        if (!pipelineStarted) return
        if (callActive) {
            talk.setLocalCallPaused(true)
            engine.setPausedForCall(true)
            headset.suspendForCellularCall()
        } else {
            headset.resumeAfterCellularCall()
            engine.setPausedForCall(false)
            talk.setLocalCallPaused(false)
        }
        ride.setOnCall(callActive)
    }

    private companion object {
        /** LINK_LOST is 250 ms; the rest covers AudioTrack buffering. */
        const val GOODBYE_CHIME_MS = 700L

        const val SPEAKING_REPORT_MS = 250L
    }
}
