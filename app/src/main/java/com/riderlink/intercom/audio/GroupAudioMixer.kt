package com.riderlink.intercom.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp

/**
 * The Host's mix-minus for a group ride (docs/adr/0002). Each rider [K] gets its own jitter buffer.
 * Every 20 ms [tick] pops one frame from each, adds the Host's own mic, and produces:
 *  - what the Host hears: every rider, not itself;
 *  - for each rider, that rider's mix-minus: the Host's mic plus every other rider.
 * A rider whose mix-minus is silence gets no frame, so idle groups send nothing.
 * Riders who aren't talking all get the same mix; [tick] flags it as shared so a caller can
 * encode it once for all of them. A rider the Host muted is heard by nobody, but still hears the
 * group. [recentTalkers] says who spoke lately, for the roster's speaking badges.
 *
 * Threads: [onIncoming] from link receive threads, [submitHostMic] from the capture thread, and
 * [tick] / [reset] from the playback thread. [tick] allocates only when a new rider appears.
 */
class GroupAudioMixer<K : Any>(
    private val frameSamples: Int = AudioConfig.SAMPLES_PER_FRAME,
    private val newBuffer: () -> JitterBuffer = { JitterBuffer(frameSamples = frameSamples) }
) {
    private val buffers = ConcurrentHashMap<K, JitterBuffer>()

    private val micLock = Any()
    private val pendingMic = ShortArray(frameSamples)
    private var pendingMicFresh = false

    // Playback-thread scratch.
    private val mic = ShortArray(frameSamples)
    private val total = IntArray(frameSamples)
    private val everyRider = IntArray(frameSamples)
    private val out = ShortArray(frameSamples)
    private val frames = HashMap<K, ShortArray>()
    private val talking = HashMap<K, Boolean>()

    // Who spoke when, in ticks; read from other threads by [recentTalkers].
    @Volatile
    private var tickCount = 0L
    private val lastTalkTick = ConcurrentHashMap<K, Long>()
    @Volatile
    private var hostLastTalkTick = Long.MIN_VALUE / 2

    /** A decoded frame from rider [key]. */
    fun onIncoming(key: K, sequence: Long, pcm: ShortArray, sampleCount: Int) {
        buffers.computeIfAbsent(key) { newBuffer() }.push(sequence, pcm, sampleCount)
    }

    /** The Host's latest mic frame, or null when the Host isn't talking (muted, gated or PTT up). */
    fun submitHostMic(pcm: ShortArray?, sampleCount: Int = frameSamples) {
        synchronized(micLock) {
            if (pcm == null) {
                pendingMicFresh = false
            } else {
                System.arraycopy(pcm, 0, pendingMic, 0, minOf(sampleCount, frameSamples))
                pendingMicFresh = true
            }
        }
    }

    fun reset() {
        buffers.values.forEach { it.reset() }
        synchronized(micLock) { pendingMicFresh = false }
    }

    /**
     * Mixes one 20 ms frame for [riders]. Writes what the Host hears into [hostOut] and calls [send]
     * once per rider whose mix-minus has audio. Returns true if [hostOut] has audio.
     * The array passed to [send] is reused: copy or encode it before returning. `shared` is true
     * when it's the plain group mix (the rider isn't talking), identical for every such rider
     * in this tick.
     */
    fun tick(
        riders: List<K>,
        hostOut: ShortArray,
        isMuted: (K) -> Boolean = { false },
        send: (rider: K, pcm: ShortArray, shared: Boolean) -> Unit
    ): Boolean {
        dropRidersNotIn(riders)
        val tickNow = ++tickCount

        val micTalking = synchronized(micLock) {
            if (pendingMicFresh) {
                System.arraycopy(pendingMic, 0, mic, 0, frameSamples)
                pendingMicFresh = false
                true
            } else {
                false
            }
        }

        if (micTalking) hostLastTalkTick = tickNow
        for (i in 0 until frameSamples) {
            total[i] = if (micTalking) mic[i].toInt() else 0
            everyRider[i] = 0
        }

        var ridersTalking = 0
        for (rider in riders) {
            val frame = frames.getOrPut(rider) { ShortArray(frameSamples) }
            val popped = buffers[rider]?.pop(frame) ?: false
            if (popped) lastTalkTick[rider] = tickNow
            // A muted rider is still drained (and shown as speaking) but heard by nobody.
            val isTalking = popped && !isMuted(rider)
            talking[rider] = isTalking
            if (!isTalking) continue
            ridersTalking++
            for (i in 0 until frameSamples) {
                total[i] += frame[i]
                everyRider[i] += frame[i]
            }
        }

        for (i in 0 until frameSamples) hostOut[i] = limit(everyRider[i])

        val sources = ridersTalking + if (micTalking) 1 else 0
        for (rider in riders) {
            val own = talking[rider] == true
            if (sources - (if (own) 1 else 0) == 0) continue
            val frame = frames.getValue(rider)
            for (i in 0 until frameSamples) {
                out[i] = limit(if (own) total[i] - frame[i] else total[i])
            }
            send(rider, out, !own)
        }
        return ridersTalking > 0
    }

    /** Any thread: whether the Host and which riders spoke in the last [withinTicks] ticks. */
    fun recentTalkers(withinTicks: Int = SPEAKING_HOLD_TICKS): Pair<Boolean, Set<K>> {
        val now = tickCount
        val riders = lastTalkTick.filterValues { now - it < withinTicks }.keys
        return (now - hostLastTalkTick < withinTicks) to riders
    }

    private fun dropRidersNotIn(riders: List<K>) {
        if (buffers.size <= riders.size && riders.containsAll(buffers.keys)) return
        buffers.keys.retainAll(riders.toSet())
        lastTalkTick.keys.retainAll(riders.toSet())
        frames.keys.retainAll(riders.toSet())
        talking.keys.retainAll(riders.toSet())
    }

    companion object {
        /** A rider counts as speaking for this long after their last frame (15 x 20 ms = 300 ms). */
        const val SPEAKING_HOLD_TICKS = 15

        /** Sums above this are compressed smoothly instead of clipping hard (several riders at once). */
        const val LIMITER_KNEE = 24_000
        private const val HEADROOM = Short.MAX_VALUE - LIMITER_KNEE

        fun limit(sum: Int): Short {
            val magnitude = abs(sum)
            if (magnitude <= LIMITER_KNEE) return sum.toShort()
            val over = (magnitude - LIMITER_KNEE).toDouble()
            val squeezed = LIMITER_KNEE + HEADROOM * (1.0 - exp(-over / HEADROOM))
            val value = squeezed.toInt().coerceAtMost(Short.MAX_VALUE.toInt())
            return (if (sum < 0) -value else value).toShort()
        }
    }
}
