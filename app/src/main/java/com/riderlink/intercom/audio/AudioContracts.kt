package com.riderlink.intercom.audio

object AudioConfig {
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val FRAME_DURATION_MS: Int = 20
    const val SAMPLES_PER_FRAME: Int = 320
    const val BYTES_PER_FRAME: Int = 640
}

interface AudioProcessor {
    /** Processes a 20 ms 16-bit mono PCM frame in-place. Returns false if gated to silence. */
    fun process(samples: ShortArray, sampleCount: Int): Boolean
}

interface AudioCodec {
    val codecId: Byte
    fun encode(pcm: ShortArray, sampleCount: Int, outBytes: ByteArray, outOffset: Int = 0): Int
    fun decode(inBytes: ByteArray, inOffset: Int, length: Int, outPcm: ShortArray): Int
}
