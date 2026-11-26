package com.riderlink.intercom.audio

import com.riderlink.intercom.protocol.CodecId

/**
 * Zero-allocation little-endian 16-bit PCM codec for 16 kHz mono frames.
 */
class Pcm16Codec : AudioCodec {
    override val codecId: Byte = CodecId.PCM16_16K

    override fun encode(
        pcm: ShortArray,
        sampleCount: Int,
        outBytes: ByteArray,
        outOffset: Int
    ): Int {
        val byteLen = sampleCount * 2
        require(pcm.size >= sampleCount) { "pcm too small: ${pcm.size} < $sampleCount" }
        require(outBytes.size >= outOffset + byteLen) {
            "outBytes too small: ${outBytes.size} < ${outOffset + byteLen}"
        }

        var outIdx = outOffset
        for (i in 0 until sampleCount) {
            val s = pcm[i].toInt()
            outBytes[outIdx++] = (s and 0xFF).toByte()
            outBytes[outIdx++] = ((s ushr 8) and 0xFF).toByte()
        }
        return byteLen
    }

    override fun decode(
        inBytes: ByteArray,
        inOffset: Int,
        length: Int,
        outPcm: ShortArray
    ): Int {
        val sampleCount = length / 2
        require(inBytes.size >= inOffset + sampleCount * 2) { "inBytes too small" }
        require(outPcm.size >= sampleCount) { "outPcm too small" }

        var inIdx = inOffset
        for (i in 0 until sampleCount) {
            val lo = inBytes[inIdx++].toInt() and 0xFF
            val hi = inBytes[inIdx++].toInt()
            outPcm[i] = ((hi shl 8) or lo).toShort()
        }
        return sampleCount
    }
}
