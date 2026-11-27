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
        val changed = synchronized(lock) {
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
        changed?.let { onStateChanged?.invoke(it) }
    }

    fun stop() {
        val changed = synchronized(lock) {
            transitionToLocked(LinkState.DISCONNECTED)
        }
        changed?.let { onStateChanged?.invoke(it) }
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
        val changed = synchronized(lock) {
            recordPacketReceivedLocked(now)
        }
        changed?.let { onStateChanged?.invoke(it) }
    }

    fun recordPongReceived(echoedTimestampMs: Long, now: Long = nowMs()) {
        val changed = synchronized(lock) {
            pongsReceived++
            val now32 = now and 0xFFFFFFFFL
            val echo32 = echoedTimestampMs and 0xFFFFFFFFL
            val diff32 = (now32 - echo32) and 0xFFFFFFFFL
            val sampleRtt = if (diff32 <= MAX_PLAUSIBLE_RTT_MS) diff32 else 0L
            rttMs = if (pongsReceived == 1L) sampleRtt else ((rttMs * 3L + sampleRtt) / 4L)
            recordPacketReceivedLocked(now)
        }
        changed?.let { onStateChanged?.invoke(it) }
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
        val changed: LinkState?
        val pingDue: Boolean
        synchronized(lock) {
            if (state == LinkState.IDLE || state == LinkState.DISCONNECTED) {
                return false
            }

            changed = if (state == LinkState.CONNECTED &&
                lastPacketReceivedMs > 0L &&
                (now - lastPacketReceivedMs) >= dropoutTimeoutMs
            ) {
                transitionToLocked(LinkState.RECONNECTING)
            } else {
                null
            }

            pingDue = lastPingSentMs == 0L || (now - lastPingSentMs) >= pingIntervalMs
        }
        changed?.let { onStateChanged?.invoke(it) }
        return pingDue
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

    private fun recordPacketReceivedLocked(now: Long): LinkState? {
        lastPacketReceivedMs = now
        return if (state == LinkState.WAITING_FOR_PEER || state == LinkState.RECONNECTING) {
            transitionToLocked(LinkState.CONNECTED)
        } else {
            null
        }
    }

    private fun transitionToLocked(newState: LinkState): LinkState? {
        return if (state != newState) {
            state = newState
            newState
        } else {
            null
        }
    }

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 1000L
        const val DEFAULT_DROPOUT_TIMEOUT_MS = 3000L
        private const val MAX_PLAUSIBLE_RTT_MS = 60_000L
    }
}
