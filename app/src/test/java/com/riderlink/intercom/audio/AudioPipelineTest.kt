package com.riderlink.intercom.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {

    @Test
    fun pcm16CodecRoundTripsBoundarySamples() {
        val codec = Pcm16Codec()
        val input = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { idx ->
            when (idx % 4) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                2 -> 0
                else -> -1
            }
        }
        val encoded = ByteArray(AudioConfig.BYTES_PER_FRAME)
        val byteLen = codec.encode(input, AudioConfig.SAMPLES_PER_FRAME, encoded)
        assertEquals(AudioConfig.BYTES_PER_FRAME, byteLen)

        val decoded = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
        val decodedCount = codec.decode(encoded, 0, byteLen, decoded)
        assertEquals(AudioConfig.SAMPLES_PER_FRAME, decodedCount)
        assertArrayEquals(input, decoded)
    }

    @Test
    fun noiseGateGatesSilenceAndHoldsAfterSpeech() {
        val gate = NoiseGateProcessor(thresholdRms = 350, holdFrames = 2, enabled = true)
        val quiet = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 50 }
        val loud = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 1200 }

        // Initially closed on quiet input
        val q1 = quiet.copyOf()
        assertFalse(gate.process(q1, q1.size))
        assertTrue(q1.all { it == 0.toShort() })

        // Opens on loud speech
        val l1 = loud.copyOf()
        assertTrue(gate.process(l1, l1.size))

        // Hold window keeps gate open for 2 quiet frames
        assertTrue(gate.process(quiet.copyOf(), quiet.size))
        assertTrue(gate.process(quiet.copyOf(), quiet.size))

        // Next quiet frame performs smooth fade-out close, then subsequent frames are gated
        assertTrue(gate.process(quiet.copyOf(), quiet.size))
        val gatedAgain = quiet.copyOf()
        assertFalse(gate.process(gatedAgain, gatedAgain.size))
        assertTrue(gatedAgain.all { it == 0.toShort() })
    }

    @Test
    fun jitterBufferReordersDropsLateAndConcealsLoss() {
        val jb = JitterBuffer(initialTargetDelayFrames = 3, maxDelayFrames = 8)
        val out = ShortArray(AudioConfig.SAMPLES_PER_FRAME)

        fun frame(value: Short) = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { value }

        // Push out-of-order: 1, 3, 2 -> primes at depth 3
        jb.push(1L, frame(100), AudioConfig.SAMPLES_PER_FRAME)
        assertFalse(jb.pop(out)) // still priming (depth 1)

        jb.push(3L, frame(300), AudioConfig.SAMPLES_PER_FRAME)
        jb.push(2L, frame(200), AudioConfig.SAMPLES_PER_FRAME)

        assertTrue(jb.pop(out))
        assertEquals(100.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(200.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(300.toShort(), out[0])

        // Late packet (seq 2 again) should be dropped
        jb.push(2L, frame(999), AudioConfig.SAMPLES_PER_FRAME)
        assertEquals(1L, jb.packetsDroppedLate)

        // Missing seq 4 triggers PLC fade-to-silence (0.5 * 300 = 150)
        assertTrue(jb.pop(out))
        assertEquals(150.toShort(), out[0])
        assertEquals(1L, jb.packetsLostPlc)
    }

    @Test
    fun jitterBufferCatchesUpOnOverflow() {
        val jb = JitterBuffer(initialTargetDelayFrames = 2, maxDelayFrames = 4)
        val out = ShortArray(AudioConfig.SAMPLES_PER_FRAME)

        for (seq in 1L..10L) {
            jb.push(seq, ShortArray(AudioConfig.SAMPLES_PER_FRAME) { (seq * 10).toInt().toShort() }, AudioConfig.SAMPLES_PER_FRAME)
        }

        assertTrue(jb.pop(out))
        // When maxDelayFrames=4 and 1..10 are pushed, overflow catch-up keeps depth <= 4 (nextPlaySeq = 7)
        assertEquals(70.toShort(), out[0])
    }

    @Test
    fun jitterBufferResumesCleanlyAfterSilenceOrPttPause() {
        val jb = JitterBuffer(initialTargetDelayFrames = 2, maxDelayFrames = 8)
        val out = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
        fun frame(v: Short) = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { v }

        // First talkspurt: seq 1, 2, 3
        jb.push(1L, frame(100), AudioConfig.SAMPLES_PER_FRAME)
        jb.push(2L, frame(200), AudioConfig.SAMPLES_PER_FRAME)
        jb.push(3L, frame(300), AudioConfig.SAMPLES_PER_FRAME)
        assertTrue(jb.pop(out))
        assertEquals(100.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(200.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(300.toShort(), out[0])

        // Simulate 2 seconds (100 frames) of silence / PTT released
        repeat(100) { jb.pop(out) }

        // Second talkspurt: seq 4, 5, 6 must re-sync with zero late drops
        jb.push(4L, frame(400), AudioConfig.SAMPLES_PER_FRAME)
        jb.push(5L, frame(500), AudioConfig.SAMPLES_PER_FRAME)
        jb.push(6L, frame(600), AudioConfig.SAMPLES_PER_FRAME)

        assertEquals(0L, jb.packetsDroppedLate)
        assertTrue(jb.pop(out))
        assertEquals(400.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(500.toShort(), out[0])
        assertTrue(jb.pop(out))
        assertEquals(600.toShort(), out[0])
    }
}
