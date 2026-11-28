package com.riderlink.intercom.music

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler for mono 16-bit audio (e.g. a 44.1 kHz song → the intercom's
 * 16 kHz). When going down, it low-passes below the new Nyquist first, so cymbals and synths above
 * 8 kHz don't fold back as hiss. Feed it chunks of any size; output comes out as soon as enough
 * input has arrived. Not thread safe.
 *
 * Cheap enough for a debug build on an old phone: the filter is precomputed for [PHASES] sub-sample
 * positions, so each output sample is just 2 × [halfTaps] multiply-adds (no trig, no allocation).
 */
class Resampler(
    sourceRateHz: Int,
    targetRateHz: Int = 16_000,
    private val halfTaps: Int = 16
) {
    private val step = sourceRateHz.toDouble() / targetRateHz // input samples per output sample
    private val taps = 2 * halfTaps

    /** Filter weights for each sub-sample phase, each row normalised to sum to 1 (unity gain). */
    private val table = FloatArray(PHASES * taps).also { t ->
        val cutoff = min(1.0, targetRateHz.toDouble() / sourceRateHz) * 0.9 // of the source Nyquist
        for (phase in 0 until PHASES) {
            val frac = phase.toDouble() / PHASES
            var sum = 0.0
            val row = DoubleArray(taps) { j ->
                // Tap j sits at input centre - halfTaps + 1 + j; its distance from the output point:
                val d = frac + halfTaps - 1 - j
                val x = cutoff * d
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.5 * (1 + cos(PI * d / halfTaps)) // Hann over ±halfTaps
                (sinc * window).also { sum += it }
            }
            for (j in 0 until taps) t[phase * taps + j] = (row[j] / sum).toFloat()
        }
    }

    // Starts with halfTaps of silence so the first output samples have a full window behind them.
    private var buffer = FloatArray(4096)
    private var bufferStart = -halfTaps.toLong() // absolute index of buffer[0]
    private var bufferLength = halfTaps
    private var nextOutputAt = 0.0 // absolute input position of the next output sample

    /** Pushes [count] input samples; calls [emit] for each output sample now ready. */
    inline fun process(input: ShortArray, count: Int, emit: (Short) -> Unit) {
        append(input, count)
        drain(emit)
    }

    fun append(input: ShortArray, count: Int) {
        ensureCapacity(bufferLength + count)
        for (i in 0 until count) buffer[bufferLength + i] = input[i].toFloat()
        bufferLength += count
    }

    inline fun drain(emit: (Short) -> Unit) {
        while (hasNext()) emit(next())
        compact()
    }

    /** True when enough input is buffered for the next output sample. */
    fun hasNext(): Boolean = floor(nextOutputAt).toLong() + halfTaps < bufferStart + bufferLength

    /** The next output sample; only call after [hasNext] returned true. */
    fun next(): Short {
        val centre = floor(nextOutputAt).toLong()
        val phase = ((nextOutputAt - centre) * PHASES).toInt().coerceAtMost(PHASES - 1)
        val base = (centre - halfTaps + 1 - bufferStart).toInt()
        val row = phase * taps
        var acc = 0f
        for (j in 0 until taps) acc += buffer[base + j] * table[row + j]
        nextOutputAt += step
        return acc.coerceIn(-32768f, 32767f).toInt().toShort()
    }

    /** Drops input that no future output sample can reach. */
    fun compact() {
        val keepFrom = floor(nextOutputAt).toLong() - halfTaps
        val drop = (keepFrom - bufferStart).toInt()
        if (drop <= 0) return
        val n = min(drop, bufferLength)
        System.arraycopy(buffer, n, buffer, 0, bufferLength - n)
        bufferLength -= n
        bufferStart += n
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    private companion object {
        /** Sub-sample positions in the filter table; 512 keeps timing error under 0.2% of a sample. */
        const val PHASES = 512
    }
}
