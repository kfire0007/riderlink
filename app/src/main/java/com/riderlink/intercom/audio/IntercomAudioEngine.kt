package com.riderlink.intercom.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import com.riderlink.intercom.protocol.CodecId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex 16 kHz mono 16-bit PCM engine with hardware AEC/AGC, noise gate,
 * adaptive jitter buffer, in-ear chimes, and polite Google Maps/Waze coexistence.
 */
/** How a Host's engine reaches its riders when it mixes for a group (docs/adr/0002). */
interface GroupMixing {
    /** The riders to mix for right now (hot path, playback thread). */
    fun riders(): List<Any>

    /** The codec [rider] expects ([CodecId]). */
    fun codecFor(rider: Any): Byte

    /** The Host muted [rider]: nobody hears it (hot path, playback thread). */
    fun isMuted(rider: Any): Boolean = false

    /** Sends one encoded mix-minus frame to [rider] (hot path, playback thread; never blocks). */
    fun send(rider: Any, bytes: ByteArray, offset: Int, length: Int, codecId: Byte)
}

class IntercomAudioEngine(
    /** The codec a Peer's mic is sent in (its Link's agreed codec). Unused while hosting. */
    private val txCodecId: () -> Byte = { CodecId.PCM16_16K },
    private val shouldTransmit: () -> Boolean,
    private val isBypassNoiseGate: () -> Boolean,
    private val onEncodedFrame: (bytes: ByteArray, offset: Int, length: Int, codecId: Byte) -> Unit
) {
    private val codec: AudioCodec = Pcm16Codec()
    val noiseGate = NoiseGateProcessor()
    val jitterBuffer = JitterBuffer()
    val chimePlayer = InEarChimePlayer()

    private val running = AtomicBoolean(false)
    private val pausedForCall = AtomicBoolean(false)

    @Volatile
    var remoteAudioMuted: Boolean = false

    /**
     * Set while hosting: frames from each rider go to their own buffer in [groupMixer], and every
     * playback tick sends each rider its mix-minus. Null on a Peer, which plays the Host's single
     * stream from [jitterBuffer]. Change it only while the engine is stopped.
     */
    @Volatile
    var groupMixing: GroupMixing? = null
        set(value) {
            field = value
            groupMixer.reset()
        }

    private val groupMixer = GroupAudioMixer<Any>()
    private val mixTxBytes = ByteArray(AudioConfig.BYTES_PER_FRAME)

    // Opus keeps state per stream (docs/adr/0002):
    private val micOpus = OpusCodec() // capture thread: a Peer's mic
    private val opusDecoders = HashMap<Any, OpusCodec>() // rxDecodeLock: one per source
    private val riderEncoders = HashMap<Any, OpusCodec>() // playback thread: a talker's own mix
    private val listenerOpus = OpusCodec() // playback thread: the shared listener mix
    private val sharedOpusBytes = ByteArray(OpusCodec.MAX_PACKET_BYTES)
    private val sharedPcmBytes = ByteArray(AudioConfig.BYTES_PER_FRAME)
    private var sharedOpusLen = NOT_ENCODED
    private var sharedPcmLen = NOT_ENCODED

    /** Rolling mix-tick cost while hosting, for diagnostics and field checks. */
    @Volatile
    var lastMixTickMicros: Long = 0L
        private set

    private val txCapturePcm = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
    private val txEncodedBytes = ByteArray(AudioConfig.BYTES_PER_FRAME)
    private val rxDecodePcm = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
    private val rxPlayPcm = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
    private val rxDecodeLock = Any()
    private val recorderLock = Any()
    private val playerLock = Any()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        pausedForCall.set(false)
        jitterBuffer.reset()
        noiseGate.reset()
        startAudioThreads()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopAudioThreads()
        chimePlayer.clear()
        jitterBuffer.reset()
        groupMixer.reset()
        synchronized(rxDecodeLock) { opusDecoders.clear() }
        riderEncoders.clear()
    }

    fun setPausedForCall(paused: Boolean) {
        if (!running.get()) return
        if (pausedForCall.compareAndSet(!paused, paused)) {
            if (paused) {
                stopAudioThreads()
            } else {
                jitterBuffer.reset()
                groupMixer.reset()
                startAudioThreads()
            }
        }
    }

    /** While hosting: whether the Host and which riders spoke in the last ~300 ms. */
    fun groupSpeaking(): Pair<Boolean, Set<Any>> = groupMixer.recentTalkers()

    fun resetJitterBuffer() {
        jitterBuffer.reset()
        groupMixer.reset()
    }

    /** One frame from [source] (a rider when hosting, the Host's mix on a Peer). */
    fun onIncomingAudio(source: Any, codecId: Byte, sequenceNumber: Long, payload: ByteArray, offset: Int, length: Int) {
        if (!running.get() || pausedForCall.get() || length <= 0) return
        if (codecId != CodecId.OPUS && length < AudioConfig.BYTES_PER_FRAME) return
        synchronized(rxDecodeLock) {
            val samples = if (codecId == CodecId.OPUS) {
                opusDecoders.getOrPut(source) { OpusCodec() }.decode(payload, offset, length, rxDecodePcm)
            } else {
                codec.decode(payload, offset, AudioConfig.BYTES_PER_FRAME, rxDecodePcm)
            }
            if (groupMixing != null) {
                groupMixer.onIncoming(source, sequenceNumber, rxDecodePcm, samples)
            } else {
                jitterBuffer.push(sequenceNumber, rxDecodePcm, samples)
            }
        }
    }

    fun playChime(type: ChimeType) {
        chimePlayer.enqueue(type)
    }

    fun setNoiseGateThreshold(rms: Int) {
        noiseGate.thresholdRms = rms.coerceAtLeast(0)
    }

    private fun startAudioThreads() {
        if (captureThread != null || playbackThread != null) return
        captureThread = Thread({ runCaptureLoop() }, "RiderLink-Audio-TX").apply {
            start()
        }
        playbackThread = Thread({ runPlaybackLoop() }, "RiderLink-Audio-RX").apply {
            start()
        }
    }

    private fun stopAudioThreads() {
        val cap = captureThread
        val play = playbackThread
        captureThread = null
        playbackThread = null

        cap?.interrupt()
        play?.interrupt()

        // Unblock native read()/write() calls first; let each thread's finally release handles,
        // then run idempotent fallback release only after join() completes.
        unblockRecorder()
        unblockPlayer()

        try {
            cap?.join(500L)
        } catch (_: InterruptedException) {
        }
        try {
            play?.join(500L)
        } catch (_: InterruptedException) {
        }

        releaseRecorder()
        releasePlayer()
    }

    @SuppressLint("MissingPermission")
    private fun runCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(AudioConfig.BYTES_PER_FRAME * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        } catch (_: Exception) {
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }

        synchronized(recorderLock) {
            if (!running.get() || pausedForCall.get()) {
                recorder.release()
                return
            }
            audioRecord = recorder
            attachEffectsLocked(recorder.audioSessionId)
        }

        try {
            recorder.startRecording()
            while (running.get() && !pausedForCall.get() && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(txCapturePcm, 0, AudioConfig.SAMPLES_PER_FRAME)
                if (read != AudioConfig.SAMPLES_PER_FRAME) {
                    if (read < 0) break
                    continue
                }

                val pass = shouldTransmit() && (
                    isBypassNoiseGate() || noiseGate.process(txCapturePcm, AudioConfig.SAMPLES_PER_FRAME)
                    )

                if (groupMixing != null) {
                    // Hosting: the mixer sends the Host's voice inside each rider's mix-minus.
                    groupMixer.submitHostMic(if (pass) txCapturePcm else null)
                } else if (pass) {
                    val tx = if (txCodecId() == CodecId.OPUS) micOpus else codec
                    val encodedLen = tx.encode(txCapturePcm, AudioConfig.SAMPLES_PER_FRAME, txEncodedBytes, 0)
                    if (encodedLen > 0) onEncodedFrame(txEncodedBytes, 0, encodedLen, tx.codecId)
                }
            }
        } catch (_: Exception) {
        } finally {
            releaseRecorder()
        }
    }

    private fun runPlaybackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(AudioConfig.BYTES_PER_FRAME * 4)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Exception) {
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }

        synchronized(playerLock) {
            if (!running.get() || pausedForCall.get()) {
                track.release()
                return
            }
            audioTrack = track
        }

        try {
            track.play()
            while (running.get() && !pausedForCall.get() && !Thread.currentThread().isInterrupted) {
                val mixing = groupMixing
                if (mixing != null) {
                    val tickStart = System.nanoTime()
                    sharedOpusLen = NOT_ENCODED
                    sharedPcmLen = NOT_ENCODED
                    groupMixer.tick(mixing.riders(), rxPlayPcm, mixing::isMuted) { rider, pcm, shared ->
                        sendMix(mixing, rider, pcm, shared)
                    }
                    lastMixTickMicros = (System.nanoTime() - tickStart) / 1_000L
                    if (remoteAudioMuted) rxPlayPcm.fill(0)
                } else if (remoteAudioMuted) {
                    rxPlayPcm.fill(0)
                } else {
                    jitterBuffer.pop(rxPlayPcm)
                }
                chimePlayer.mixInto(rxPlayPcm, AudioConfig.SAMPLES_PER_FRAME)
                val written = track.write(rxPlayPcm, 0, AudioConfig.SAMPLES_PER_FRAME)
                if (written < 0) break
            }
        } catch (_: Exception) {
        } finally {
            releasePlayer()
        }
    }

    /** Encodes one rider's mix in its codec (the shared listener mix once per tick) and sends it. */
    private fun sendMix(mixing: GroupMixing, rider: Any, pcm: ShortArray, shared: Boolean) {
        val n = AudioConfig.SAMPLES_PER_FRAME
        if (mixing.codecFor(rider) == CodecId.OPUS) {
            if (shared) {
                if (sharedOpusLen == NOT_ENCODED) sharedOpusLen = listenerOpus.encode(pcm, n, sharedOpusBytes, 0)
                if (sharedOpusLen > 0) mixing.send(rider, sharedOpusBytes, 0, sharedOpusLen, CodecId.OPUS)
            } else {
                val len = riderEncoders.getOrPut(rider) { OpusCodec() }.encode(pcm, n, mixTxBytes, 0)
                if (len > 0) mixing.send(rider, mixTxBytes, 0, len, CodecId.OPUS)
            }
        } else if (shared) {
            if (sharedPcmLen == NOT_ENCODED) sharedPcmLen = codec.encode(pcm, n, sharedPcmBytes, 0)
            mixing.send(rider, sharedPcmBytes, 0, sharedPcmLen, CodecId.PCM16_16K)
        } else {
            val len = codec.encode(pcm, n, mixTxBytes, 0)
            mixing.send(rider, mixTxBytes, 0, len, CodecId.PCM16_16K)
        }
    }

    private fun attachEffectsLocked(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    private fun unblockRecorder() {
        synchronized(recorderLock) {
            val rec = audioRecord ?: return
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseRecorder() {
        val rec: AudioRecord?
        val aecLocal: AcousticEchoCanceler?
        val agcLocal: AutomaticGainControl?
        synchronized(recorderLock) {
            aecLocal = aec
            aec = null
            agcLocal = agc
            agc = null
            rec = audioRecord
            audioRecord = null
        }

        try {
            aecLocal?.release()
        } catch (_: Exception) {
        }
        try {
            agcLocal?.release()
        } catch (_: Exception) {
        }
        if (rec == null) return
        try {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
    }

    private fun unblockPlayer() {
        synchronized(playerLock) {
            val track = audioTrack ?: return
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun releasePlayer() {
        val track: AudioTrack?
        synchronized(playerLock) {
            track = audioTrack
            audioTrack = null
        }
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val NOT_ENCODED = -1
    }
}
