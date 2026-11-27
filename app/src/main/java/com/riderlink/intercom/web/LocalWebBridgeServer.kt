package com.riderlink.intercom.web

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Single-port HTTPS + WSS server hosted on Android so an iPhone OBR can join
 * via Safari with zero app install.
 */
class LocalWebBridgeServer(
    private val openKeystore: () -> InputStream,
    private val keystorePassword: String,
    private val loadHtmlBytes: () -> ByteArray,
    private val listener: Listener,
    private val wsReadTimeoutMs: Int = DEFAULT_WS_READ_TIMEOUT_MS,
    private val authLockoutMs: Long = DEFAULT_AUTH_LOCKOUT_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    interface Listener {
        fun onClientConnected(clientIp: String)
        fun onAudioFrame(pcmBytes: ByteArray, offset: Int, length: Int)
        fun onHeartbeat(rttMs: Long?)
        fun onClientControl(message: String)
        fun onClientDisconnected()
    }

    private val running = AtomicBoolean(false)
    private val txLock = Any()
    private val audioQueueLock = Object()

    @Volatile
    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var activeWsSocket: Socket? = null

    @Volatile
    private var activeWsOut: OutputStream? = null

    @Volatile
    private var sessionToken: Int = 0

    @Volatile
    private var lastHostPingSentMs: Long = 0L

    private val failedAuthAttempts = AtomicInteger(0)
    private val authBlockedUntilMs = AtomicLong(0L)

    // Pre-allocated 4-frame ring buffer so TCP backpressure never blocks RiderLink-Audio-TX.
    private val audioRing = Array(AUDIO_QUEUE_CAPACITY) { ByteArray(MAX_AUDIO_FRAME_BYTES) }
    private val audioRingLengths = IntArray(AUDIO_QUEUE_CAPACITY)
    private var audioReadIdx = 0
    private var audioWriteIdx = 0
    private var audioQueuedCount = 0

    private var acceptThread: Thread? = null
    private var audioWriterThread: Thread? = null
    private var workerPool: ExecutorService? = null
    private var htmlBytes: ByteArray = ByteArray(0)

    val isClientConnected: Boolean
        get() = activeWsOut != null && activeWsSocket?.isClosed == false

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(port: Int = DEFAULT_HTTPS_PORT, sessionToken: Int) {
        if (!running.compareAndSet(false, true)) {
            updateSessionToken(sessionToken)
            return
        }
        updateSessionToken(sessionToken)
        this.htmlBytes = loadHtmlBytes()

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            openKeystore().use { load(it, keystorePassword.toCharArray()) }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, keystorePassword.toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }

        val srv = (sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            reuseAddress = true
        }
        serverSocket = srv

        workerPool = Executors.newFixedThreadPool(MAX_WORKER_THREADS) { r ->
            Thread(r, "RiderLink-WebBridge-Worker").apply { isDaemon = true }
        }
        audioWriterThread = Thread({ runAudioWriterLoop() }, "RiderLink-WebBridge-AudioTX").apply {
            isDaemon = true
            start()
        }
        acceptThread = Thread({ runAcceptLoop(srv) }, "RiderLink-WebBridge-Accept").apply {
            isDaemon = true
            start()
        }
    }

    fun updateSessionToken(sessionToken: Int) {
        this.sessionToken = sessionToken
        failedAuthAttempts.set(0)
        authBlockedUntilMs.set(0L)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeActiveClient()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        synchronized(audioQueueLock) {
            clearAudioQueueLocked()
            audioQueueLock.notifyAll()
        }
        audioWriterThread?.interrupt()
        audioWriterThread = null
        workerPool?.shutdownNow()
        workerPool = null
    }

    fun sendAudio(pcmBytes: ByteArray, offset: Int, length: Int): Boolean {
        if (!running.get() || !isClientConnected) return false
        if (length <= 0 || length > MAX_AUDIO_FRAME_BYTES) return false
        synchronized(audioQueueLock) {
            if (audioQueuedCount == AUDIO_QUEUE_CAPACITY) {
                // Drop oldest frame under TCP backpressure so real-time audio never stalls.
                audioReadIdx = (audioReadIdx + 1) % AUDIO_QUEUE_CAPACITY
                audioQueuedCount--
            }
            System.arraycopy(pcmBytes, offset, audioRing[audioWriteIdx], 0, length)
            audioRingLengths[audioWriteIdx] = length
            audioWriteIdx = (audioWriteIdx + 1) % AUDIO_QUEUE_CAPACITY
            audioQueuedCount++
            audioQueueLock.notify()
            return true
        }
    }

    fun sendTextControl(message: String): Boolean {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        return synchronized(txLock) {
            val out = activeWsOut ?: return@synchronized false
            try {
                WebSocketCodec.writeFrame(out, WebSocketCodec.OPCODE_TEXT, bytes, 0, bytes.size)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun sendPing(nowMs: Long): Boolean {
        lastHostPingSentMs = nowMs
        return sendTextControl("ping:$nowMs")
    }

    private fun runAudioWriterLoop() {
        val scratch = ByteArray(MAX_AUDIO_FRAME_BYTES)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            var frameLen = 0
            synchronized(audioQueueLock) {
                while (running.get() && audioQueuedCount == 0) {
                    try {
                        audioQueueLock.wait()
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (!running.get()) return
                frameLen = audioRingLengths[audioReadIdx]
                System.arraycopy(audioRing[audioReadIdx], 0, scratch, 0, frameLen)
                audioReadIdx = (audioReadIdx + 1) % AUDIO_QUEUE_CAPACITY
                audioQueuedCount--
            }
            if (frameLen > 0) {
                synchronized(txLock) {
                    val out = activeWsOut ?: return@synchronized
                    try {
                        WebSocketCodec.writeFrame(out, WebSocketCodec.OPCODE_BINARY, scratch, 0, frameLen)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun runAcceptLoop(srv: SSLServerSocket) {
        while (running.get() && !srv.isClosed) {
            val client = try {
                srv.accept() as SSLSocket
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            val pool = workerPool
            if (pool == null) {
                runCatching { client.close() }
                break
            }
            try {
                pool.execute { handleConnection(client) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnection(socket: SSLSocket) {
        var isWsOwner = false
        try {
            socket.soTimeout = 15_000
            socket.startHandshake()

            val input = BufferedInputStream(socket.inputStream, 8192)
            val output = socket.outputStream
            val request = readHttpRequest(input) ?: return

            if (request.method != "GET") {
                writeHttpError(output, 405, "Method Not Allowed")
                return
            }

            if (request.path.startsWith("/ws")) {
                val upgrade = request.headers["upgrade"]?.lowercase()
                val wsKey = request.headers["sec-websocket-key"]
                val tokenParam = extractQueryParam(request.path, "token")?.toIntOrNull()

                if (upgrade != "websocket" || wsKey.isNullOrBlank()) {
                    writeHttpError(output, 400, "Bad Request")
                    return
                }

                val now = nowMs()
                if (now < authBlockedUntilMs.get()) {
                    writeHttpError(output, 429, "Too Many Requests")
                    return
                }

                if (tokenParam != sessionToken) {
                    val failures = failedAuthAttempts.incrementAndGet()
                    if (failures >= MAX_FAILED_AUTH_ATTEMPTS) {
                        authBlockedUntilMs.set(now + authLockoutMs)
                        writeHttpError(output, 429, "Too Many Requests")
                    } else {
                        writeHttpError(output, 403, "Forbidden")
                    }
                    return
                }

                failedAuthAttempts.set(0)
                authBlockedUntilMs.set(0L)

                val acceptKey = WebSocketCodec.computeAcceptKey(wsKey)
                val handshakeResp = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n\r\n")
                }
                output.write(handshakeResp.toByteArray(StandardCharsets.US_ASCII))
                output.flush()

                // Enforce silent-dropout detection (3.5 s) for active WebSocket sessions.
                socket.soTimeout = wsReadTimeoutMs
                closeActiveClient()
                synchronized(audioQueueLock) {
                    clearAudioQueueLocked()
                }
                synchronized(txLock) {
                    activeWsSocket = socket
                    activeWsOut = output
                }
                isWsOwner = true
                val clientIp = socket.inetAddress.hostAddress ?: "iPhone"
                listener.onClientConnected(clientIp)

                runWebSocketLoop(DataInputStream(input), output)
            } else {
                writeHtmlResponse(output, htmlBytes)
            }
        } catch (_: Exception) {
        } finally {
            if (isWsOwner) {
                var notified = false
                synchronized(txLock) {
                    if (activeWsSocket === socket) {
                        activeWsSocket = null
                        activeWsOut = null
                        notified = true
                    }
                }
                if (notified) {
                    synchronized(audioQueueLock) {
                        clearAudioQueueLocked()
                    }
                    listener.onClientDisconnected()
                }
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun runWebSocketLoop(dataIn: DataInputStream, output: OutputStream) {
        while (running.get()) {
            val frame = WebSocketCodec.readFrame(dataIn) ?: break
            when (frame.opcode) {
                WebSocketCodec.OPCODE_BINARY -> {
                    if (frame.payload.size >= MAX_AUDIO_FRAME_BYTES) {
                        listener.onAudioFrame(frame.payload, 0, frame.payload.size)
                    }
                }
                WebSocketCodec.OPCODE_TEXT -> {
                    val msg = String(frame.payload, StandardCharsets.UTF_8)
                    when {
                        msg.startsWith("ping:") -> {
                            val ts = msg.removePrefix("ping:")
                            sendTextControl("pong:$ts")
                            listener.onHeartbeat(null)
                        }
                        msg.startsWith("pong:") -> {
                            val echoed = msg.removePrefix("pong:").toLongOrNull()
                            val rtt = if (echoed != null && lastHostPingSentMs >= echoed) {
                                (nowMs() - echoed).coerceAtLeast(0L)
                            } else {
                                null
                            }
                            listener.onHeartbeat(rtt)
                        }
                        else -> {
                            listener.onClientControl(msg)
                            if (msg == "ctrl:goodbye") break
                        }
                    }
                }
                WebSocketCodec.OPCODE_PING -> {
                    synchronized(txLock) {
                        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_PONG, frame.payload, 0, frame.payload.size)
                    }
                }
                WebSocketCodec.OPCODE_CLOSE -> break
            }
        }
    }

    private fun closeActiveClient() {
        val oldSock: Socket?
        synchronized(txLock) {
            oldSock = activeWsSocket
            activeWsSocket = null
            activeWsOut = null
        }
        synchronized(audioQueueLock) {
            clearAudioQueueLocked()
        }
        try {
            oldSock?.close()
        } catch (_: Exception) {
        }
    }

    private fun clearAudioQueueLocked() {
        audioReadIdx = 0
        audioWriteIdx = 0
        audioQueuedCount = 0
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val headerBytes = ArrayList<Byte>(512)
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        var headerComplete = false
        while (headerBytes.size < MAX_HTTP_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.add(b.toByte())
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                headerComplete = true
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        if (!headerComplete) return null

        val raw = String(headerBytes.toByteArray(), StandardCharsets.US_ASCII)
        val lines = raw.split("\r\n")
        if (lines.isEmpty()) return null
        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return null

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        return HttpRequest(reqParts[0].uppercase(), reqParts[1], headers)
    }

    private fun extractQueryParam(path: String, key: String): String? {
        val qIdx = path.indexOf('?')
        if (qIdx < 0 || qIdx + 1 >= path.length) return null
        val pairs = path.substring(qIdx + 1).split('&')
        for (pair in pairs) {
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == key) {
                return pair.substring(eq + 1)
            }
        }
        return null
    }

    private fun writeHtmlResponse(output: OutputStream, body: ByteArray) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun writeHttpError(output: OutputStream, code: Int, reason: String) {
        val body = reason.toByteArray(StandardCharsets.UTF_8)
        val resp = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(resp.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    companion object {
        const val DEFAULT_HTTPS_PORT = 8443
        const val DEFAULT_WS_READ_TIMEOUT_MS = 3_500
        const val DEFAULT_AUTH_LOCKOUT_MS = 10_000L
        const val MAX_FAILED_AUTH_ATTEMPTS = 5
        private const val MAX_WORKER_THREADS = 4
        private const val AUDIO_QUEUE_CAPACITY = 4
        private const val MAX_AUDIO_FRAME_BYTES = 640
        private const val MAX_HTTP_HEADER_BYTES = 8192
    }
}
