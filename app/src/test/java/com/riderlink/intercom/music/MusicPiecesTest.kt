package com.riderlink.intercom.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MusicPiecesTest {

    private fun tone(hz: Double, rate: Int, seconds: Double, amplitude: Int = 10_000) =
        ShortArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun resample(input: ShortArray, from: Int, chunk: Int = 1024): ShortArray {
        val r = Resampler(from)
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + n), n) { out += it }
            i += n
        }
        return out.toShortArray()
    }

    private fun rms(x: ShortArray, skip: Int = 200) =
        sqrt(x.drop(skip).dropLast(skip).sumOf { it.toDouble() * it } / (x.size - 2 * skip))

    private fun zeroCrossings(x: ShortArray) = x.toList().zipWithNext().count { (a, b) -> (a < 0) != (b < 0) }

    // --- Resampler -------------------------------------------------------------------------

    @Test
    fun a1kHzSongToneStays1kHzAtTheSameLevel() {
        val input = tone(1000.0, 44_100, 1.0)
        val out = resample(input, 44_100)

        assertTrue("got ${out.size} samples", abs(out.size - 16_000) < 64)
        val hz = zeroCrossings(out) / 2.0 / (out.size / 16_000.0)
        assertTrue("got $hz Hz", abs(hz - 1000) < 15)
        assertTrue(rms(out) / rms(input) in 0.85..1.1)
    }

    @Test
    fun contentAboveTheNewNyquistIsFilteredOut_notFoldedBack() {
        val input = tone(12_000.0, 44_100, 0.5) // a 16 kHz stream can't carry 12 kHz
        val out = resample(input, 44_100)
        assertTrue("aliased level ${rms(out)} vs input ${rms(input)}", rms(out) < rms(input) * 0.1)
    }

    @Test
    fun chunkSizeDoesNotChangeTheResult() {
        val input = tone(440.0, 48_000, 0.3)
        val a = resample(input, 48_000, chunk = 97)
        val b = resample(input, 48_000, chunk = 4800)
        assertEquals(a.size, b.size)
        assertTrue(a.indices.all { abs(a[it] - b[it]) <= 1 })
    }

    // --- Ducker ----------------------------------------------------------------------------

    @Test
    fun musicDipsFastWhenSomeoneTalks_andComesBackSlowly() {
        val ducker = MusicDucker()
        repeat(3) { ducker.next(voiceActive = true) }
        assertEquals(MusicDucker.DUCKED_GAIN, ducker.gain, 0.001f)

        repeat(10) { ducker.next(voiceActive = false) }
        assertTrue("too fast back up: ${ducker.gain}", ducker.gain < 0.5f)
        repeat(50) { ducker.next(voiceActive = false) }
        assertEquals(1f, ducker.gain, 0.001f)
    }

    @Test
    fun musicLevelsMapFromTheWireVolume() {
        assertEquals(MusicLevel.OFF, MusicLevel.fromWire(muted = true, volume = 100))
        assertEquals(MusicLevel.OFF, MusicLevel.fromWire(false, 0))
        assertEquals(MusicLevel.LOW, MusicLevel.fromWire(false, 33))
        assertEquals(MusicLevel.MEDIUM, MusicLevel.fromWire(false, 66))
        assertEquals(MusicLevel.FULL, MusicLevel.fromWire(false, 100))
        MusicLevel.entries.forEach { assertEquals(it, MusicLevel.fromWire(false, it.wireVolume)) }
    }

    // --- Ring buffer -----------------------------------------------------------------------

    @Test
    fun ringBufferHandsOutWholeFramesInOrder_andNeverBlocksTheReader() {
        val ring = PcmRingBuffer(capacitySamples = 8)
        val frame = ShortArray(4)
        assertFalse(ring.readFrame(frame))

        assertTrue(ring.write(ShortArray(6) { it.toShort() }, 6))
        assertTrue(ring.readFrame(frame))
        assertEquals(listOf<Short>(0, 1, 2, 3), frame.toList())
        assertFalse(ring.readFrame(frame)) // only 2 left
    }

    @Test
    fun aFullRingMakesTheWriterWait_untilTheReaderCatchesUp() {
        val ring = PcmRingBuffer(capacitySamples = 4)
        ring.write(ShortArray(4) { 1 }, 4)
        var done = false
        val writer = thread { done = ring.write(ShortArray(4) { 2 }, 4) }
        Thread.sleep(100)
        assertFalse(done)

        ring.readFrame(ShortArray(4))
        writer.join(1000)
        assertTrue(done)
    }

    @Test
    fun closingReleasesABlockedWriter() {
        val ring = PcmRingBuffer(capacitySamples = 4)
        ring.write(ShortArray(4), 4)
        var result = true
        val writer = thread { result = ring.write(ShortArray(4), 4) }
        Thread.sleep(50)
        ring.close()
        writer.join(1000)
        assertFalse(result)
    }
}
