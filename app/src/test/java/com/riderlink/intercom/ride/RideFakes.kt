package com.riderlink.intercom.ride

/** Single-threaded scheduler with a virtual clock. Nothing runs until [runUntilIdle] or [advanceBy]. */
class FakeScheduler : RideScheduler {
    private class Task(val dueMs: Long, val order: Long, val block: () -> Unit) {
        var cancelled = false
    }

    var nowMs = 0L
        private set
    private var nextOrder = 0L
    private val tasks = mutableListOf<Task>()

    override fun post(block: () -> Unit) {
        tasks += Task(nowMs, nextOrder++, block)
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit): Cancellable {
        val task = Task(nowMs + delayMs, nextOrder++, block)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    fun runUntilIdle() {
        while (true) {
            val next = tasks
                .filter { it.dueMs <= nowMs }
                .minWithOrNull(compareBy<Task>({ it.dueMs }, { it.order }))
                ?: return
            tasks.remove(next)
            if (!next.cancelled) next.block()
        }
    }

    fun advanceBy(ms: Long) {
        val target = nowMs + ms
        while (true) {
            runUntilIdle()
            val next = tasks.filter { !it.cancelled && it.dueMs <= target }.minOfOrNull { it.dueMs } ?: break
            nowMs = next
        }
        nowMs = target
        runUntilIdle()
    }
}

class FakeHotspotFormer : HotspotFormer {
    var startCount = 0
    var stopCount = 0
    var events: HotspotFormer.Events? = null

    override fun start(events: HotspotFormer.Events) {
        startCount++
        this.events = events
    }

    override fun stop() {
        stopCount++
    }

    fun ready(ssid: String = "DIRECT-rl", pass: String = "secret12", hostIp: String = "192.168.49.1") =
        events!!.onReady(HotspotCredentials(WifiCredentials(ssid, pass), hostIp))

    fun fail(reason: String = "No Wi-Fi channel") = events!!.onFailed(reason)
    fun lose() = events!!.onLost()
}

class FakeWifiJoiner : WifiJoiner {
    val joined = mutableListOf<WifiCredentials>()
    var stopCount = 0
    var events: WifiJoiner.Events? = null

    override fun join(wifi: WifiCredentials, events: WifiJoiner.Events) {
        joined += wifi
        this.events = events
    }

    override fun stop() {
        stopCount++
    }
}

object FakeBoundNetwork : BoundNetwork

class FakeSharedWifi(
    var localIp: String? = "192.168.1.20",
    var current: JoinedNetwork? = JoinedNetwork(gatewayIp = "192.168.1.1", bound = FakeBoundNetwork)
) : SharedWifi {
    override fun localIpv4(): String? = localIp
    override fun currentNetwork(): JoinedNetwork? = current
}

class FakeLink(
    override val kind: LinkKind,
    override val riderId: Int = -1,
    override val riderName: String = ""
) : PeerLink {
    val rostersSent = mutableListOf<com.riderlink.intercom.protocol.RosterSync>()
    var removedCount = 0
    val musicPrefsSent = mutableListOf<com.riderlink.intercom.music.MusicLevel>()
    val voiceSent = mutableListOf<ByteArray>()
    val controlsSent = mutableListOf<PeerControl>()
    var goodbyes = 0
    var linkStats = LinkStats()

    override fun sendVoice(bytes: ByteArray, offset: Int, length: Int) {
        voiceSent += bytes.copyOfRange(offset, offset + length)
    }

    override fun sendControl(control: PeerControl) {
        controlsSent += control
    }

    override fun sayGoodbye() {
        goodbyes++
    }

    override fun stats(): LinkStats = linkStats

    override fun sendRoster(roster: com.riderlink.intercom.protocol.RosterSync) {
        rostersSent += roster
    }

    override fun remove() {
        removedCount++
    }

    override fun sendMusicPref(level: com.riderlink.intercom.music.MusicLevel) {
        musicPrefsSent += level
    }
}

class FakeGateway(
    override val kind: LinkKind,
    private val port: Int
) : PeerGateway {
    val link = FakeLink(kind)
    val openedWithTokens = mutableListOf<Int>()
    var closeCount = 0
    var isOpen = false
    var failOpen = false
    private var events: LinkEvents? = null

    override fun open(sessionToken: Int, events: LinkEvents): Int {
        if (failOpen) throw java.net.BindException("Address already in use")
        openedWithTokens += sessionToken
        this.events = events
        isOpen = true
        return port
    }

    override fun close() {
        closeCount++
        isOpen = false
    }

    fun linkUp() = events!!.onLinked(link)
    fun reconnecting() = events!!.onReconnecting(link)
    fun goodbye() = events!!.onGoodbye(link)
    fun control(c: PeerControl) = events!!.onControl(link, c)
    fun voice(seq: Long, bytes: ByteArray) = events!!.onVoice(link, link.codecId, seq, bytes, 0, bytes.size)

    /** A group Host: another rider joins this gateway as its own link. */
    fun riderJoins(id: Int, name: String): FakeLink = FakeLink(kind, id, name).also { events!!.onLinked(it) }
    fun riderLeaves(rider: FakeLink) = events!!.onGoodbye(rider)
    fun musicPref(rider: FakeLink, level: com.riderlink.intercom.music.MusicLevel) = events!!.onMusicPref(rider, level)
}

class FakeDialer : HostDialer {
    data class Dial(val hostIp: String, val port: Int, val token: Int, val network: BoundNetwork?)

    val dials = mutableListOf<Dial>()
    var hangUpCount = 0
    var failDial = false
    var link = FakeLink(LinkKind.ANDROID)
        private set
    private var events: LinkEvents? = null

    override fun dial(
        hostIp: String,
        port: Int,
        sessionToken: Int,
        network: BoundNetwork?,
        events: LinkEvents
    ): PeerLink {
        if (failDial) throw java.net.SocketException("Network unreachable")
        dials += Dial(hostIp, port, sessionToken, network)
        this.events = events
        link = FakeLink(LinkKind.ANDROID)
        return link
    }

    override fun hangUp() {
        hangUpCount++
    }

    fun linkUp() = events!!.onLinked(link)
    fun reconnecting() = events!!.onReconnecting(link)
    fun goodbye() = events!!.onGoodbye(link)
    fun control(c: PeerControl) = events!!.onControl(link, c)
    fun roster(sync: com.riderlink.intercom.protocol.RosterSync, yourId: Int) = events!!.onRoster(link, sync, yourId)
    fun removed() = events!!.onRemoved(link)
}

class RecordingVoiceReceiver : VoiceReceiver {
    data class Frame(val link: PeerLink, val sequence: Long, val bytes: List<Byte>)

    val frames = mutableListOf<Frame>()

    override fun onVoice(link: PeerLink, codecId: Byte, sequence: Long, bytes: ByteArray, offset: Int, length: Int) {
        frames += Frame(link, sequence, bytes.copyOfRange(offset, offset + length).toList())
    }
}
