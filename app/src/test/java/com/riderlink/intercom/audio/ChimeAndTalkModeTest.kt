package com.riderlink.intercom.audio

import com.riderlink.intercom.service.TalkMode
import com.riderlink.intercom.service.TalkModeController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeAndTalkModeTest {

    @Test
    fun chimeSynthesizerGeneratesNonEmptyBoundedWaveforms() {
        for (type in ChimeType.entries) {
            val samples = ChimeSynthesizer.generate(type)
            assertTrue("Chime $type should be non-empty", samples.isNotEmpty())
            assertTrue("Chime $type should contain non-zero audio", samples.any { it != 0.toShort() })
        }
    }

    @Test
    fun inEarChimePlayerMixesAndDrainsBackToSilence() {
        val player = InEarChimePlayer()
        player.enqueue(ChimeType.MUTED) // 45 ms = 720 samples (2+ frames)

        val buf1 = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 1000 }
        player.mixInto(buf1, AudioConfig.SAMPLES_PER_FRAME)
        assertTrue(buf1.any { it != 1000.toShort() })

        // Drain remaining frames
        val drain = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
        repeat(5) { player.mixInto(drain, AudioConfig.SAMPLES_PER_FRAME) }

        val finalSilence = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
        player.mixInto(finalSilence, AudioConfig.SAMPLES_PER_FRAME)
        assertTrue(finalSilence.all { it == 0.toShort() })
    }

    @Test
    fun talkModeControllerHandlesHandsFreePttAndCallPause() {
        val ctrl = TalkModeController()

        // Default Hands-Free transmits when unmuted
        assertTrue(ctrl.shouldTransmitMic())

        // Mute blocks transmission
        ctrl.toggleMute()
        assertFalse(ctrl.shouldTransmitMic())
        ctrl.toggleMute()
        assertTrue(ctrl.shouldTransmitMic())

        // Switch to PTT -> blocked until pressed or toggled
        ctrl.setTalkMode(TalkMode.PUSH_TO_TALK)
        assertFalse(ctrl.shouldTransmitMic())

        ctrl.setPttPressed(true)
        assertTrue(ctrl.shouldTransmitMic())
        ctrl.setPttPressed(false)
        assertFalse(ctrl.shouldTransmitMic())

        // Single-click headset toggle fallback
        assertTrue(ctrl.togglePtt())
        assertTrue(ctrl.shouldTransmitMic())

        // Cellular call pause overrides both modes
        ctrl.setLocalCallPaused(true)
        assertFalse(ctrl.shouldTransmitMic())
        ctrl.setLocalCallPaused(false)
        assertTrue(ctrl.shouldTransmitMic())
    }
}
