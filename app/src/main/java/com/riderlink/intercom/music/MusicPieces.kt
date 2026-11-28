package com.riderlink.intercom.music

/**
 * How loud the group music is in one rider's ears. Four levels so a Host can share one encode
 * per level instead of one per rider (docs/adr/0003).
 */
enum class MusicLevel(val gain: Float, val wireVolume: Int) {
    OFF(0f, 0),
    LOW(0.3f, 33),
    MEDIUM(0.6f, 66),
    FULL(1f, 100);

    companion object {
        val DEFAULT = MEDIUM

        /** MUSIC_PREF carries a 0..100 volume; map it to the nearest level. */
        fun fromWire(muted: Boolean, volume: Int): MusicLevel = when {
            muted || volume <= 0 -> OFF
            volume <= 45 -> LOW
            volume <= 80 -> MEDIUM
            else -> FULL
        }
    }
}

/**
 * Lowers the music while anyone talks: down to [DUCKED_GAIN] fast (~60 ms) so the first words are
 * clear, back up slowly (~1 s) so it doesn't pump between words. One gain per 20 ms tick.
 */
class MusicDucker {
    var gain: Float = 1f
        private set

    fun next(voiceActive: Boolean): Float {
        gain = if (voiceActive) {
            maxOf(DUCKED_GAIN, gain - ATTACK_PER_TICK)
        } else {
            minOf(1f, gain + RELEASE_PER_TICK)
        }
        return gain
    }

    fun reset() {
        gain = 1f
    }

    companion object {
        const val DUCKED_GAIN = 0.2f
        private const val ATTACK_PER_TICK = 0.3f // 1 → 0.2 in 3 ticks (60 ms)
        private const val RELEASE_PER_TICK = 0.8f / 50f // 0.2 → 1 in 50 ticks (1 s)
    }
}

/**
 * Single-producer / single-consumer buffer of mono PCM: the music decoder writes ahead, the mixer
 * reads one 20 ms frame per tick without ever blocking. [write] blocks while full (that's what keeps
 * the decoder from running ahead and using memory) until there's room or [close] is called.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val data = ShortArray(capacitySamples)
    private val lock = Object()
    private var readPos = 0
    private var size = 0
    private var closed = false

    val available: Int get() = synchronized(lock) { size }

    /** Writes all of [samples][0 until count], waiting for room. Returns false if closed meanwhile. */
    fun write(samples: ShortArray, count: Int): Boolean {
        var written = 0
        synchronized(lock) {
            while (written < count) {
                while (size == data.size && !closed) lock.wait()
                if (closed) return false
                val writePos = (readPos + size) % data.size
                val chunk = minOf(count - written, data.size - size, data.size - writePos)
                System.arraycopy(samples, written, data, writePos, chunk)
                size += chunk
                written += chunk
            }
            return true
        }
    }

    /** Fills [out] with the next [out].size samples if that many are buffered; never blocks. */
    fun readFrame(out: ShortArray): Boolean = synchronized(lock) {
        if (size < out.size) return false
        for (i in out.indices) {
            out[i] = data[readPos]
            readPos = (readPos + 1) % data.size
        }
        size -= out.size
        lock.notifyAll()
        true
    }

    fun clear() = synchronized(lock) {
        readPos = 0
        size = 0
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    fun reopen() = synchronized(lock) {
        closed = false
        readPos = 0
        size = 0
    }
}
