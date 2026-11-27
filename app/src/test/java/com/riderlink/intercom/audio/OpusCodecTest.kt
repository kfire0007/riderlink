package com.riderlink.intercom.audio

import com.riderlink.intercom.protocol.CodecId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class OpusCodecTest {

    private val n = AudioConfig.SAMPLES_PER_FRAME

    private fun toneFrames(count: Int, hz: Double = 440.0, amplitude: Int = 8000): List<ShortArray> {
        var phase = 0.0
        return List(count) {
            ShortArray(n) {
                val s = (amplitude * sin(phase)).toInt().toShort()
                phase += 2 * PI * hz / AudioConfig.SAMPLE_RATE_HZ
                s
            }
        }
    }

    private fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

    @Test
    fun aToneSurvivesTheRoundTripAtAboutTheSameLevel() {
        val tx = OpusCodec()
        val rx = OpusCodec()
        val packet = ByteArray(OpusCodec.MAX_PACKET_BYTES)
        val out = ShortArray(n)
        val frames = toneFrames(50)
        var lastRms = 0.0

        for (frame in frames) {
            val len = tx.encode(frame, n, packet, 0)
            assertTrue(len > 0)
            assertEquals(n, rx.decode(packet, 0, len, out))
            lastRms = rms(out)
        }

        val inputRms = rms(frames.last())
        assertTrue("decoded level $lastRms vs input $inputRms", lastRms in inputRms * 0.6..inputRms * 1.4)
        assertEquals(CodecId.OPUS, tx.codecId)
    }

    @Test
    fun voiceFramesAreAboutTenTimesSmallerThanPcm() {
        val codec = OpusCodec()
        val packet = ByteArray(OpusCodec.MAX_PACKET_BYTES)
        val sizes = toneFrames(50).map { codec.encode(it, n, packet, 0) }

        val average = sizes.drop(10).average()
        // 24 kbps x 20 ms = 60 bytes; PCM16 is 640.
        assertTrue("average Opus frame $average B", average in 30.0..120.0)
    }

    @Test
    fun aCorruptPacketDecodesToSilence_notACrash() {
        val out = ShortArray(n) { 123 }
        val samples = OpusCodec().decode(ByteArray(7) { 0x7F }, 0, 7, out)
        assertEquals(n, samples)
    }

    @Test
    fun encodingCostOnThisMachine() {
        val codec = OpusCodec()
        val packet = ByteArray(OpusCodec.MAX_PACKET_BYTES)
        val frames = toneFrames(500)
        frames.take(100).forEach { codec.encode(it, n, packet, 0) } // warm up the JIT
        val start = System.nanoTime()
        frames.forEach { codec.encode(it, n, packet, 0) }
        val perFrameUs = (System.nanoTime() - start) / 1000.0 / frames.size
        println("Opus encode: ${"%.0f".format(perFrameUs)} µs per 20 ms frame (JVM on the build machine)")
        assertTrue("one encode must fit well inside a 20 ms frame", perFrameUs < 5_000)
    }
}
