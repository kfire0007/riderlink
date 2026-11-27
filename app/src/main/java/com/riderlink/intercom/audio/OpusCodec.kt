package com.riderlink.intercom.audio

import com.riderlink.intercom.protocol.CodecId
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Opus voice for one stream, 16 kHz mono, 20 ms frames, via Concentus (pure Java).
 *
 * Opus keeps state between frames, so use one [OpusCodec] per direction per stream: a Peer has one
 * for its mic and one for the Host's mix; a Host has one per rider for each direction. Not thread
 * safe: confine each instance to the thread that feeds it.
 *
 * [encode] fails soft: on an encoder error it returns 0 and the caller sends nothing for that frame.
 * [decode] fills silence on a bad packet so the jitter buffer keeps its timing.
 */
class OpusCodec(
    bitrateBps: Int = DEFAULT_BITRATE_BPS,
    complexity: Int = DEFAULT_COMPLEXITY
) : AudioCodec {
    override val codecId: Byte = CodecId.OPUS

    private val encoder by lazy {
        OpusEncoder(AudioConfig.SAMPLE_RATE_HZ, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(bitrateBps)
            setComplexity(complexity)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
            // In-band FEC lets a decoder rebuild one lost frame from the next; cheap insurance on bikes.
            setUseInbandFEC(true)
            setPacketLossPercent(EXPECTED_LOSS_PERCENT)
            // We skip silent frames ourselves (noise gate / mix-minus), so no DTX.
            setUseDTX(false)
        }
    }

    private val decoder by lazy { OpusDecoder(AudioConfig.SAMPLE_RATE_HZ, 1) }

    override fun encode(pcm: ShortArray, sampleCount: Int, outBytes: ByteArray, outOffset: Int): Int =
        try {
            encoder.encode(pcm, 0, sampleCount, outBytes, outOffset, outBytes.size - outOffset)
        } catch (_: OpusException) {
            0
        }

    override fun decode(inBytes: ByteArray, inOffset: Int, length: Int, outPcm: ShortArray): Int =
        try {
            decoder.decode(inBytes, inOffset, length, outPcm, 0, AudioConfig.SAMPLES_PER_FRAME, false)
        } catch (_: OpusException) {
            outPcm.fill(0, 0, AudioConfig.SAMPLES_PER_FRAME)
            AudioConfig.SAMPLES_PER_FRAME
        }

    companion object {
        /** Spec: 24–32 kbps for voice. 24 kbps is ~10x less airtime than PCM16's 256 kbps. */
        const val DEFAULT_BITRATE_BPS = 24_000

        /**
         * 0..10. On a realme 5, complexity barely moves Concentus's cost (encode ~2.1 ms at 0/1, ~2.3 ms
         * at 3; same ~43 B/frame), so 1 takes the cheapest end.
         */
        const val DEFAULT_COMPLEXITY = 1

        const val EXPECTED_LOSS_PERCENT = 10

        /** Comfortably above a 24 kbps 20 ms frame (~60 B) and any Opus frame we'd make. */
        const val MAX_PACKET_BYTES = 400
    }
}
