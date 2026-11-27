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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-duplex 16 kHz mono 16-bit PCM engine with hardware AEC/AGC, noise gate,
 * adaptive jitter buffer, in-ear chimes, and polite Google Maps/Waze coexistence.
 */
class IntercomAudioEngine(
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
    }

    fun setPausedForCall(paused: Boolean) {
        if (!running.get()) return
        if (pausedForCall.compareAndSet(!paused, paused)) {
            if (paused) {
                stopAudioThreads()
            } else {
                jitterBuffer.reset()
                startAudioThreads()
            }
        }
    }

    fun resetJitterBuffer() {
        jitterBuffer.reset()
    }

    fun onIncomingAudio(sequenceNumber: Long, payload: ByteArray, offset: Int, length: Int) {
        if (!running.get() || pausedForCall.get()) return
        if (length < AudioConfig.BYTES_PER_FRAME) return
        synchronized(rxDecodeLock) {
            val samples = codec.decode(payload, offset, AudioConfig.BYTES_PER_FRAME, rxDecodePcm)
            jitterBuffer.push(sequenceNumber, rxDecodePcm, samples)
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

                if (!shouldTransmit()) {
                    continue
                }

                val pass = if (isBypassNoiseGate()) {
                    true
                } else {
                    noiseGate.process(txCapturePcm, AudioConfig.SAMPLES_PER_FRAME)
                }

                if (pass) {
                    val encodedLen = codec.encode(txCapturePcm, AudioConfig.SAMPLES_PER_FRAME, txEncodedBytes, 0)
                    onEncodedFrame(txEncodedBytes, 0, encodedLen, codec.codecId)
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
                if (remoteAudioMuted) {
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
}
