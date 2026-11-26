package com.riderlink.intercom.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.sin

enum class ChimeType {
    CONNECTED,
    LINK_LOST,
    RECONNECTED,
    MUTED,
    UNMUTED,
    PTT_DOWN,
    PTT_UP,
    PEER_ON_CALL
}

/**
 * Synthesizes short, click-free 16 kHz mono PCM status tones with 5 ms envelopes.
 */
object ChimeSynthesizer {
    private const val AMPLITUDE = 9_000.0 // ~0.275 full-scale
    private const val ENVELOPE_MS = 5

    fun generate(type: ChimeType): ShortArray = when (type) {
        ChimeType.CONNECTED -> concat(tone(660.0, 70), tone(880.0, 90))
        ChimeType.RECONNECTED -> concat(tone(740.0, 55), tone(988.0, 70))
        ChimeType.LINK_LOST -> concat(tone(660.0, 90), silence(40), tone(440.0, 120))
        ChimeType.MUTED -> tone(420.0, 45)
        ChimeType.UNMUTED -> tone(780.0, 45)
        ChimeType.PTT_DOWN -> tone(920.0, 35)
        ChimeType.PTT_UP -> tone(580.0, 35)
        ChimeType.PEER_ON_CALL -> concat(
            tone(520.0, 40),
            silence(40),
            tone(520.0, 40),
            silence(40),
            tone(520.0, 40)
        )
    }

    private fun tone(freqHz: Double, durationMs: Int): ShortArray {
        val count = (AudioConfig.SAMPLE_RATE_HZ * durationMs) / 1000
        val envSamples = ((AudioConfig.SAMPLE_RATE_HZ * ENVELOPE_MS) / 1000).coerceAtLeast(1)
        val out = ShortArray(count)
        for (i in 0 until count) {
            val env = when {
                i < envSamples -> i.toDouble() / envSamples.toDouble()
                i >= count - envSamples -> (count - 1 - i).toDouble() / envSamples.toDouble()
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val sample = sin(2.0 * PI * freqHz * i / AudioConfig.SAMPLE_RATE_HZ) * AMPLITUDE * env
            out[i] = sample.toInt().toShort()
        }
        return out
    }

    private fun silence(durationMs: Int): ShortArray =
        ShortArray((AudioConfig.SAMPLE_RATE_HZ * durationMs) / 1000)

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, offset, p.size)
            offset += p.size
        }
        return out
    }
}

/**
 * Thread-safe queue that mixes synthesized status chimes into the 20 ms playback buffer.
 */
class InEarChimePlayer {
    private val clips = ChimeType.entries.associateWith { ChimeSynthesizer.generate(it) }
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    private var activeClip: ShortArray? = null
    private var activeCursor: Int = 0

    fun enqueue(type: ChimeType) {
        val clip = clips[type] ?: return
        if (queue.size < 4) {
            queue.offer(clip)
        }
    }

    fun mixInto(targetPcm: ShortArray, sampleCount: Int) {
        var idx = 0
        while (idx < sampleCount) {
            val clip = activeClip ?: queue.poll()?.also {
                activeClip = it
                activeCursor = 0
            } ?: break

            val remaining = clip.size - activeCursor
            val toCopy = minOf(remaining, sampleCount - idx)
            for (i in 0 until toCopy) {
                val mixed = targetPcm[idx + i].toInt() + clip[activeCursor + i].toInt()
                targetPcm[idx + i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            idx += toCopy
            activeCursor += toCopy
            if (activeCursor >= clip.size) {
                activeClip = null
                activeCursor = 0
            }
        }
    }

    fun clear() {
        queue.clear()
        activeClip = null
        activeCursor = 0
    }
}
