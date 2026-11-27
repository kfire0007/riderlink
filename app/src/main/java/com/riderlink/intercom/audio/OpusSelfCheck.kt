package com.riderlink.intercom.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Times Opus on this device, so a Host's per-rider cost can be checked against the 20 ms frame
 * budget in the field (debug builds run it once at start-up and log it).
 */
object OpusSelfCheck {
    data class Result(val encodeMicros: Double, val decodeMicros: Double, val averageBytes: Int) {
        /** Rough worst case for a Host: one shared listener encode plus one per talking rider. */
        fun encodesPerFrameBudget(budgetMicros: Double = 10_000.0): Int = (budgetMicros / encodeMicros).toInt()
    }

    fun run(frames: Int = 300, warmUp: Int = 100, complexity: Int = OpusCodec.DEFAULT_COMPLEXITY): Result {
        val n = AudioConfig.SAMPLES_PER_FRAME
        var phase = 0.0
        val pcm = List(frames) {
            ShortArray(n) {
                val s = (8000 * sin(phase)).toInt().toShort()
                phase += 2 * PI * 440 / AudioConfig.SAMPLE_RATE_HZ
                s
            }
        }
        val encoder = OpusCodec(complexity = complexity)
        val decoder = OpusCodec()
        val packet = ByteArray(OpusCodec.MAX_PACKET_BYTES)
        val out = ShortArray(n)
        pcm.take(warmUp).forEach { decoder.decode(packet, 0, encoder.encode(it, n, packet, 0), out) }

        var encodeNanos = 0L
        var decodeNanos = 0L
        var bytes = 0
        for (frame in pcm) {
            val t0 = System.nanoTime()
            val len = encoder.encode(frame, n, packet, 0)
            val t1 = System.nanoTime()
            decoder.decode(packet, 0, len, out)
            decodeNanos += System.nanoTime() - t1
            encodeNanos += t1 - t0
            bytes += len
        }
        return Result(encodeNanos / 1000.0 / frames, decodeNanos / 1000.0 / frames, bytes / frames)
    }
}
