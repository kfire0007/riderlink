package com.riderlink.intercom.voice

import android.content.Context
import com.riderlink.intercom.audio.ChimeType
import com.riderlink.intercom.audio.IntercomAudioEngine
import com.riderlink.intercom.ride.PeerLink
import com.riderlink.intercom.ride.Ride
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideState
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.HardwareButtonController
import com.riderlink.intercom.service.HeadsetRouteManager
import com.riderlink.intercom.service.PhoneCallInterruptionHandler
import com.riderlink.intercom.service.TalkControlState
import com.riderlink.intercom.service.TalkMode
import com.riderlink.intercom.service.TalkModeController
import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope
) {
    private val talk = TalkModeController()
    val talkControlState: StateFlow<TalkControlState> = talk.state

    private val engine = IntercomAudioEngine(
        shouldTransmit = { talk.shouldTransmitMic() },
        isBypassNoiseGate = {
            val s = talk.state.value
            s.mode == TalkMode.PUSH_TO_TALK && s.isPttTransmitting
        },
        onEncodedFrame = { bytes, offset, length, _ -> ride.sendVoice(bytes, offset, length) }
    )

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

    val jitterDepthFrames: Int
        get() = engine.jitterBuffer.currentDepthFrames

    init {
        scope.launch { ride.state.collect { onRideState(it) } }
    }

    /** Hot path from the Ride: one incoming voice frame. */
    fun onVoice(@Suppress("UNUSED_PARAMETER") link: PeerLink, sequence: Long, bytes: ByteArray, offset: Int, length: Int) {
        engine.onIncomingAudio(sequence, bytes, offset, length)
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
            stopPipeline()
            hasLinkedOnce = false
            wasLinked = false
            wasPeerOnCall = false
            if (state.isIdle) talk.reset()
            return
        }

        val linked = state.anyLinked
        if (linked && !wasLinked) {
            startPipeline()
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

    private fun startPipeline() {
        if (pipelineStarted) return
        pipelineStarted = true
        headset.start()
        engine.setNoiseGateThreshold(talk.state.value.micGateThresholdRms)
        engine.start()
        callHandler.start()
        buttons.start()
    }

    private fun stopPipeline() {
        if (!pipelineStarted) return
        pipelineStarted = false
        if (talk.state.value.isLocalCallPaused) ride.setOnCall(false)
        buttons.stop()
        callHandler.stop()
        engine.stop()
        headset.stop()
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
}
