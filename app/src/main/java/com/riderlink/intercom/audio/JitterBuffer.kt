package com.riderlink.intercom.audio

/**
 * Pre-allocated slot-based adaptive jitter buffer for 20 ms mono PCM frames.
 * Zero per-frame heap allocations on the push/pop audio path.
 */
class JitterBuffer(
    initialTargetDelayFrames: Int = DEFAULT_TARGET_DELAY_FRAMES,
    val maxDelayFrames: Int = DEFAULT_MAX_DELAY_FRAMES,
    val frameSamples: Int = AudioConfig.SAMPLES_PER_FRAME
) {
    private val capacitySlots = maxDelayFrames * 2
    private val slots = Array(capacitySlots) { ShortArray(frameSamples) }
    private val slotSeqs = LongArray(capacitySlots) { -1L }
    private val slotOccupied = BooleanArray(capacitySlots)

    private val lastGoodFrame = ShortArray(frameSamples)
    private var hasLastGoodFrame = false
    private var consecutivePlcFrames = 0

    var targetDelayFrames: Int = initialTargetDelayFrames.coerceIn(MIN_TARGET_DELAY_FRAMES, 4)
        private set

    private var primed = false
    private var initializedSeq = false
    private var nextPlaySeq = 0L
    private var highestReceivedSeq = -1L

    var packetsReceived: Long = 0L
        private set
    var packetsDroppedLate: Long = 0L
        private set
    var packetsLostPlc: Long = 0L
        private set

    val currentDepthFrames: Int
        get() {
            var count = 0
            for (i in 0 until capacitySlots) {
                if (slotOccupied[i]) count++
            }
            return count
        }

    fun push(sequenceNumber: Long, samples: ShortArray, sampleCount: Int) {
        packetsReceived++
        val copyCount = minOf(sampleCount, frameSamples)

        if (!initializedSeq) {
            initializedSeq = true
            nextPlaySeq = sequenceNumber
            highestReceivedSeq = sequenceNumber
        } else if (sequenceNumber < nextPlaySeq) {
            packetsDroppedLate++
            return
        }

        if (sequenceNumber > highestReceivedSeq) {
            highestReceivedSeq = sequenceNumber
        }

        val slotIdx = (sequenceNumber % capacitySlots).toInt().let { if (it < 0) it + capacitySlots else it }
        System.arraycopy(samples, 0, slots[slotIdx], 0, copyCount)
        if (copyCount < frameSamples) {
            slots[slotIdx].fill(0, copyCount, frameSamples)
        }
        slotSeqs[slotIdx] = sequenceNumber
        slotOccupied[slotIdx] = true

        if (!primed && currentDepthFrames >= targetDelayFrames) {
            primed = true
        }

        // Prevent latency drift if packets pile up beyond maxDelayFrames
        val bufferedSpan = (highestReceivedSeq - nextPlaySeq + 1).toInt()
        if (bufferedSpan > maxDelayFrames) {
            val dropTarget = highestReceivedSeq - targetDelayFrames + 1
            while (nextPlaySeq < dropTarget) {
                val idx = (nextPlaySeq % capacitySlots).toInt().let { if (it < 0) it + capacitySlots else it }
                if (slotOccupied[idx] && slotSeqs[idx] == nextPlaySeq) {
                    slotOccupied[idx] = false
                }
                nextPlaySeq++
            }
        }
    }

    /**
     * Pops the next 20 ms frame into [outSamples].
     * Returns true if audio (live or PLC fade) was written, false if still priming or silent.
     */
    fun pop(outSamples: ShortArray): Boolean {
        if (!primed) {
            outSamples.fill(0, 0, minOf(outSamples.size, frameSamples))
            return false
        }

        val slotIdx = (nextPlaySeq % capacitySlots).toInt().let { if (it < 0) it + capacitySlots else it }
        if (slotOccupied[slotIdx] && slotSeqs[slotIdx] == nextPlaySeq) {
            System.arraycopy(slots[slotIdx], 0, outSamples, 0, frameSamples)
            System.arraycopy(slots[slotIdx], 0, lastGoodFrame, 0, frameSamples)
            slotOccupied[slotIdx] = false
            hasLastGoodFrame = true
            consecutivePlcFrames = 0
            nextPlaySeq++
            return true
        }

        // Missing packet: Packet Loss Concealment (fade-to-silence over 3 frames)
        packetsLostPlc++
        nextPlaySeq++
        if (hasLastGoodFrame && consecutivePlcFrames < MAX_PLC_FADE_FRAMES) {
            consecutivePlcFrames++
            val decay = 1.0f / (1 shl consecutivePlcFrames).toFloat() // 0.5, 0.25, 0.125
            for (i in 0 until frameSamples) {
                outSamples[i] = (lastGoodFrame[i] * decay).toInt().toShort()
            }
            return true
        }

        outSamples.fill(0, 0, minOf(outSamples.size, frameSamples))
        return false
    }

    fun reset() {
        slotOccupied.fill(false)
        slotSeqs.fill(-1L)
        lastGoodFrame.fill(0)
        hasLastGoodFrame = false
        consecutivePlcFrames = 0
        primed = false
        initializedSeq = false
        nextPlaySeq = 0L
        highestReceivedSeq = -1L
        packetsReceived = 0L
        packetsDroppedLate = 0L
        packetsLostPlc = 0L
    }

    companion object {
        const val MIN_TARGET_DELAY_FRAMES = 2   // 40 ms
        const val DEFAULT_TARGET_DELAY_FRAMES = 3 // 60 ms
        const val DEFAULT_MAX_DELAY_FRAMES = 8  // 160 ms
        const val MAX_PLC_FADE_FRAMES = 3
    }
}
