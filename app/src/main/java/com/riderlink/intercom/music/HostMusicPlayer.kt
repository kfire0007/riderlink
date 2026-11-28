package com.riderlink.intercom.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.riderlink.intercom.audio.AudioConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteOrder

/** Pull-only music for the Host's mixer: one 16 kHz mono 20 ms frame per call, never blocking. */
fun interface MusicFrameSource {
    /** Fills [out] and returns true when music is playing and a frame is ready; false otherwise. */
    fun readFrame(out: ShortArray): Boolean
}

data class MusicTrack(val uri: Uri, val title: String)

data class MusicState(
    val queue: List<MusicTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false
) {
    val current: MusicTrack? get() = queue.getOrNull(index)

    /** What the group should see as playing (null when paused or empty). */
    val nowPlaying: String? get() = if (playing) current?.title else null
}

/**
 * RiderLink's own mini music player for group rides (docs/adr/0003). The Host picks songs from its
 * phone; this decodes them itself (any format Android's MediaCodec plays: MP3, AAC, FLAC, …), turns
 * them into the intercom's 16 kHz mono, and hands the mixer one frame per tick.
 *
 * Memory stays small: a song is streamed, never loaded whole, through a ½-second [PcmRingBuffer].
 * The decoder thread blocks when the buffer is full, so it only works as fast as the group listens.
 */
class HostMusicPlayer(
    private val context: Context,
    private val log: (String) -> Unit = {}
) : MusicFrameSource {

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    /** One buffer per track, so a stopping decoder can never write into the next song. */
    @Volatile
    private var ring = newRing()
    private val lock = Any()
    private var decoder: Thread? = null

    /** Bumped on every track change; a decoder thread from an older generation stops. */
    @Volatile
    private var generation = 0

    // --- Queue and transport ---------------------------------------------------------------

    /** Adds [uris] to the end of the queue; starts playing if nothing was. */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            val s = _state.value
            val tracks = uris.map { MusicTrack(it, titleOf(it)) }
            val startNow = s.current == null || (!s.playing && s.index >= s.queue.size - 1 && ring.available == 0)
            _state.value = s.copy(queue = s.queue + tracks)
            if (startNow) playIndexLocked(s.queue.size)
        }
    }

    fun togglePlay() = synchronized(lock) {
        val s = _state.value
        when {
            s.current == null -> if (s.queue.isNotEmpty()) playIndexLocked(0)
            s.playing -> _state.value = s.copy(playing = false)
            decoder == null -> playIndexLocked(s.index) // finished or never started: replay
            else -> _state.value = s.copy(playing = true)
        }
    }

    fun next() = synchronized(lock) {
        val s = _state.value
        if (s.index + 1 < s.queue.size) playIndexLocked(s.index + 1) else stopLocked()
    }

    fun previous() = synchronized(lock) {
        val s = _state.value
        if (s.queue.isNotEmpty()) playIndexLocked((s.index - 1).coerceAtLeast(0))
    }

    /** Pauses without losing the queue (e.g. the ride ended). */
    fun pause() = synchronized(lock) {
        _state.value = _state.value.copy(playing = false)
    }

    fun clear() = synchronized(lock) {
        stopDecoderLocked()
        _state.value = MusicState()
    }

    override fun readFrame(out: ShortArray): Boolean =
        _state.value.playing && ring.readFrame(out)

    // --- Decoding --------------------------------------------------------------------------

    private fun playIndexLocked(index: Int) {
        val s = _state.value
        val track = s.queue.getOrNull(index) ?: return stopLocked()
        stopDecoderLocked()
        val trackRing = newRing().also { ring = it }
        val myGeneration = ++generation
        _state.value = s.copy(index = index, playing = true)
        decoder = Thread({ decodeAndAdvance(track, trackRing, myGeneration) }, "RiderLink-Music").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLocked() {
        stopDecoderLocked()
        _state.value = _state.value.copy(playing = false)
    }

    private fun stopDecoderLocked() {
        generation++
        ring.close()
        decoder?.interrupt()
        decoder = null
    }

    private fun decodeAndAdvance(track: MusicTrack, ring: PcmRingBuffer, myGeneration: Int) {
        val finished = try {
            decode(track, ring, myGeneration)
        } catch (e: Exception) {
            log("Music: couldn't play '${track.title}': ${e.message}")
            true // skip a broken file rather than stall the queue
        }
        if (!finished) return
        // Let the buffered tail play out, then move on.
        while (generation == myGeneration && ring.available >= AudioConfig.SAMPLES_PER_FRAME) {
            try {
                Thread.sleep(AudioConfig.FRAME_DURATION_MS.toLong())
            } catch (_: InterruptedException) {
                return
            }
        }
        synchronized(lock) {
            if (generation != myGeneration) return
            decoder = null
            val s = _state.value
            if (s.index + 1 < s.queue.size) playIndexLocked(s.index + 1) else _state.value = s.copy(playing = false)
        }
    }

    /** Streams [track] into the ring. Returns true at the end of the song, false if stopped early. */
    private fun decode(track: MusicTrack, ring: PcmRingBuffer, myGeneration: Int): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("no audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var resampler = Resampler(sampleRate, AudioConfig.SAMPLE_RATE_HZ)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var mono = ShortArray(4096)
            var shorts = ShortArray(8192)
            var floats = FloatArray(0)
            val out = ShortArray(4096)

            while (generation == myGeneration) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate, AudioConfig.SAMPLE_RATE_HZ)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        val samples = frames * channels
                        if (mono.size < frames) mono = ShortArray(frames)
                        // Bulk-copy out of the codec's buffer (per-sample ByteBuffer reads are slow), then downmix.
                        if (floatPcm) {
                            if (floats.size < samples) floats = FloatArray(samples)
                            buf.asFloatBuffer().get(floats, 0, samples)
                            downmix(frames, channels, mono) { floats[it] * 32767f }
                        } else {
                            if (shorts.size < samples) shorts = ShortArray(samples)
                            buf.asShortBuffer().get(shorts, 0, samples)
                            if (channels == 1) {
                                System.arraycopy(shorts, 0, mono, 0, frames)
                            } else {
                                downmix(frames, channels, mono) { shorts[it].toFloat() }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)

                        var n = 0
                        var ok = true
                        resampler.process(mono, frames) { sample ->
                            out[n++] = sample
                            if (n == out.size) {
                                ok = ok && ring.write(out, n)
                                n = 0
                            }
                        }
                        if (ok && n > 0) ok = ring.write(out, n)
                        if (!ok) return false // stopped while waiting for room
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                    }
                }
            }
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Averages interleaved channels into [mono]; [sample] reads one interleaved sample as 16-bit scale. */
    private inline fun downmix(frames: Int, channels: Int, mono: ShortArray, sample: (Int) -> Float) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += sample(i++)
            mono[f] = (sum / channels).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun newRing() =PcmRingBuffer(capacitySamples = AudioConfig.SAMPLE_RATE_HZ / 2)

    private fun titleOf(uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Song"
        return name.substringBeforeLast('.').ifBlank { name }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
