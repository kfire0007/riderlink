package com.riderlink.intercom.audio

import kotlin.math.sqrt

/**
 * Lightweight RMS noise gate with attack/release hold to suppress idle wind hiss
 * without clipping between spoken syllables.
 */
class NoiseGateProcessor(
    @Volatile var thresholdRms: Int = DEFAULT_THRESHOLD_RMS,
    val holdFrames: Int = DEFAULT_HOLD_FRAMES,
    @Volatile var enabled: Boolean = true
) : AudioProcessor {

    private var remainingHoldFrames = 0
    private var wasOpen = false

    override fun process(samples: ShortArray, sampleCount: Int): Boolean {
        if (!enabled || thresholdRms <= 0 || sampleCount <= 0) {
            wasOpen = true
            remainingHoldFrames = holdFrames
            return true
        }

        val rms = computeRms(samples, sampleCount)
        if (rms >= thresholdRms) {
            remainingHoldFrames = holdFrames
            wasOpen = true
            return true
        }

        if (remainingHoldFrames > 0) {
            remainingHoldFrames--
            wasOpen = true
            return true
        }

        if (wasOpen) {
            // Smooth 1-frame linear fade-out on gate close to avoid audible clicks
            for (i in 0 until sampleCount) {
                val gain = (sampleCount - i).toFloat() / sampleCount.toFloat()
                samples[i] = (samples[i] * gain).toInt().toShort()
            }
            wasOpen = false
            return true
        }

        for (i in 0 until sampleCount) {
            samples[i] = 0
        }
        return false
    }

    fun reset() {
        remainingHoldFrames = 0
        wasOpen = false
    }

    companion object {
        const val DEFAULT_THRESHOLD_RMS = 150
        const val DEFAULT_HOLD_FRAMES = 13 // 260 ms at 20 ms/frame

        fun computeRms(samples: ShortArray, sampleCount: Int): Double {
            if (sampleCount <= 0) return 0.0
            var sumSq = 0.0
            for (i in 0 until sampleCount) {
                val v = samples[i].toDouble()
                sumSq += v * v
            }
            return sqrt(sumSq / sampleCount.toDouble())
        }
    }
}
