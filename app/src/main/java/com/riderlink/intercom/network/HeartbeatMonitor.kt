package com.riderlink.intercom.network

enum class LinkState {
    IDLE,
    WAITING_FOR_PEER,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

data class TransportStats(
    val linkState: LinkState = LinkState.IDLE,
    val rttMs: Long = 0L,
    val packetLossPercent: Float = 0f,
    val audioPacketsReceived: Long = 0L,
    val peerAddress: String? = null
)

/**
 * Tracks 1 s PING/PONG heartbeats, 3 s dropout transitions to RECONNECTING,
 * silent recovery on packet arrival, rolling RTT, and packet loss percentage.
 */
class HeartbeatMonitor(
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val dropoutTimeoutMs: Long = DEFAULT_DROPOUT_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onStateChanged: ((LinkState) -> Unit)? = null
) {
    private val lock = Any()

    private var state: LinkState = LinkState.IDLE
    private var lastPingSentMs: Long = 0L
    private var lastPacketReceivedMs: Long = 0L
    private var rttMs: Long = 0L
    private var peerAddress: String? = null

    private var pingsSent: Long = 0L
    private var pongsReceived: Long = 0L

    private var audioExpectedCount: Long = 0L
    private var audioReceivedCount: Long = 0L
    private var highestAudioSeq: Long = -1L

    fun start(initialPeerAddress: String? = null) {
        synchronized(lock) {
            peerAddress = initialPeerAddress
            lastPingSentMs = 0L
            lastPacketReceivedMs = 0L
            rttMs = 0L
            pingsSent = 0L
            pongsReceived = 0L
            audioExpectedCount = 0L
            audioReceivedCount = 0L
            highestAudioSeq = -1L
            transitionToLocked(LinkState.WAITING_FOR_PEER)
        }
    }

    fun stop() {
        synchronized(lock) {
            transitionToLocked(LinkState.DISCONNECTED)
        }
    }

    fun setPeerAddress(address: String?) {
        synchronized(lock) {
            peerAddress = address
        }
    }

    fun recordPingSent(now: Long = nowMs()) {
        synchronized(lock) {
            lastPingSentMs = now
            pingsSent++
        }
    }

    fun recordPacketReceived(now: Long = nowMs()) {
        synchronized(lock) {
            lastPacketReceivedMs = now
            if (state == LinkState.WAITING_FOR_PEER || state == LinkState.RECONNECTING) {
                transitionToLocked(LinkState.CONNECTED)
            }
        }
    }

    fun recordPongReceived(echoedTimestampMs: Long, now: Long = nowMs()) {
        synchronized(lock) {
            pongsReceived++
            val sampleRtt = (now - echoedTimestampMs).coerceAtLeast(0L)
            rttMs = if (rttMs == 0L) sampleRtt else ((rttMs * 3L + sampleRtt) / 4L)
            recordPacketReceived(now)
        }
    }

    fun recordAudioSequence(sequenceNumber: Long) {
        synchronized(lock) {
            audioReceivedCount++
            if (highestAudioSeq < 0L) {
                highestAudioSeq = sequenceNumber
                audioExpectedCount = 1L
            } else if (sequenceNumber > highestAudioSeq) {
                val delta = sequenceNumber - highestAudioSeq
                audioExpectedCount += delta
                highestAudioSeq = sequenceNumber
            }
        }
    }

    /**
     * Evaluates dropout timeout and whether a PING should be sent now.
     * Returns true if a PING is due.
     */
    fun tick(now: Long = nowMs()): Boolean {
        synchronized(lock) {
            if (state == LinkState.IDLE || state == LinkState.DISCONNECTED) {
                return false
            }

            if (state == LinkState.CONNECTED &&
                lastPacketReceivedMs > 0L &&
                (now - lastPacketReceivedMs) >= dropoutTimeoutMs
            ) {
                transitionToLocked(LinkState.RECONNECTING)
            }

            return lastPingSentMs == 0L || (now - lastPingSentMs) >= pingIntervalMs
        }
    }

    fun snapshot(): TransportStats = synchronized(lock) {
        val lossPercent = when {
            audioExpectedCount >= 5L -> {
                val lost = (audioExpectedCount - audioReceivedCount).coerceAtLeast(0L)
                ((lost.toFloat() / audioExpectedCount.toFloat()) * 100f).coerceIn(0f, 100f)
            }
            pingsSent > 1L -> {
                val lost = (pingsSent - pongsReceived - 1L).coerceAtLeast(0L)
                ((lost.toFloat() / pingsSent.toFloat()) * 100f).coerceIn(0f, 100f)
            }
            else -> 0f
        }
        TransportStats(
            linkState = state,
            rttMs = rttMs,
            packetLossPercent = lossPercent,
            audioPacketsReceived = audioReceivedCount,
            peerAddress = peerAddress
        )
    }

    private fun transitionToLocked(newState: LinkState) {
        if (state != newState) {
            state = newState
            onStateChanged?.invoke(newState)
        }
    }

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 1000L
        const val DEFAULT_DROPOUT_TIMEOUT_MS = 3000L
    }
}
