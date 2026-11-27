package com.riderlink.intercom.service

import com.riderlink.intercom.audio.NoiseGateProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TalkMode {
    HANDS_FREE,
    PUSH_TO_TALK
}

data class TalkControlState(
    val mode: TalkMode = TalkMode.HANDS_FREE,
    val isMuted: Boolean = false,
    val isPttTransmitting: Boolean = false,
    val micGateThresholdRms: Int = NoiseGateProcessor.DEFAULT_THRESHOLD_RMS,
    val isLocalCallPaused: Boolean = false,
    val isPeerOnCall: Boolean = false
)

/**
 * Pure Kotlin state controller for Hands-Free (default) and Push-to-Talk modes.
 */
class TalkModeController {
    private val _state = MutableStateFlow(TalkControlState())
    val state: StateFlow<TalkControlState> = _state.asStateFlow()

    fun shouldTransmitMic(): Boolean {
        val s = _state.value
        if (s.isLocalCallPaused) return false
        return when (s.mode) {
            TalkMode.HANDS_FREE -> !s.isMuted
            TalkMode.PUSH_TO_TALK -> s.isPttTransmitting
        }
    }

    fun setTalkMode(mode: TalkMode) {
        _state.value = _state.value.copy(
            mode = mode,
            isPttTransmitting = false
        )
    }

    fun toggleMute(): Boolean {
        val next = !_state.value.isMuted
        _state.value = _state.value.copy(isMuted = next)
        return next
    }

    fun setPttPressed(pressed: Boolean) {
        if (_state.value.mode != TalkMode.PUSH_TO_TALK) return
        _state.value = _state.value.copy(isPttTransmitting = pressed)
    }

    fun togglePtt(): Boolean {
        if (_state.value.mode != TalkMode.PUSH_TO_TALK) return false
        val next = !_state.value.isPttTransmitting
        _state.value = _state.value.copy(isPttTransmitting = next)
        return next
    }

    fun setMicGateThreshold(rms: Int) {
        _state.value = _state.value.copy(micGateThresholdRms = rms.coerceAtLeast(0))
    }

    fun setLocalCallPaused(paused: Boolean) {
        _state.value = _state.value.copy(isLocalCallPaused = paused)
    }

    fun setPeerOnCall(onCall: Boolean) {
        _state.value = _state.value.copy(isPeerOnCall = onCall)
    }

    fun reset() {
        _state.value = TalkControlState()
    }
}
