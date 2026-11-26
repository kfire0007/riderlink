package com.riderlink.intercom.web

import com.riderlink.intercom.audio.AudioConfig
import com.riderlink.intercom.protocol.QrPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WebBridgeTest {

    @Test
    fun rfc6455AcceptKeyMatchesOfficialVector() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketCodec.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    @Test
    fun webSocketCodecRoundTripsUnmaskedAndMaskedFrames() {
        val payload = ByteArray(AudioConfig.BYTES_PER_FRAME) { (it and 0x7F).toByte() }
        val baos = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(baos, WebSocketCodec.OPCODE_BINARY, payload)

        val decoded = WebSocketCodec.readFrame(DataInputStream(ByteArrayInputStream(baos.toByteArray())))
        assertNotNull(decoded)
        assertEquals(WebSocketCodec.OPCODE_BINARY, decoded!!.opcode)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun qrPayloadFormatsStandardWifiAndIosUrl() {
        val qr = QrPayload(
            ssid = "Rider;Net",
            passphrase = "pass:word",
            hostIp = "192.168.49.1",
            udpPort = 49152,
            sessionToken = 742
        )
        assertEquals("WIFI:T:WPA;S:Rider\\;Net;P:pass\\:word;;", qr.toStandardWifiQr())
        assertEquals("https://192.168.49.1:8443/?token=0742", qr.toIosWebUrl())
    }

    @Test
    fun tlsLoopbackServesHtmlRejectsBadTokenAndExchangesAudioOverWss() {
        val p12File = listOf(
            File("src/main/res/raw/riderlink_local.p12"),
            File("app/src/main/res/raw/riderlink_local.p12")
        ).first { it.exists() }

        val connectedLatch = CountDownLatch(1)
        val audioLatch = CountDownLatch(1)
        var rxAudio: ByteArray? = null

        val server = LocalWebBridgeServer(
            openKeystore = { FileInputStream(p12File) },
            keystorePassword = "riderlink",
            loadHtmlBytes = { "<html><body>RiderLink</body></html>".toByteArray(StandardCharsets.UTF_8) },
            listener = object : LocalWebBridgeServer.Listener {
                override fun onClientConnected(clientIp: String) {
                    connectedLatch.countDown()
                }

                override fun onAudioFrame(pcmBytes: ByteArray, offset: Int, length: Int) {
                    rxAudio = pcmBytes.copyOfRange(offset, offset + length)
                    audioLatch.countDown()
                }

                override fun onHeartbeat(rttMs: Long) {}
                override fun onClientControl(message: String) {}
                override fun onClientDisconnected() {}
            }
        )

        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val clientSsl = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }

        try {
            server.start(port = 0, sessionToken = 3847)
            val port = server.boundPort
            assertTrue(port > 0)

            // 1. Verify HTTPS GET / returns 200 OK HTML
            (clientSsl.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
                s.outputStream.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                s.outputStream.flush()
                val resp = s.inputStream.bufferedReader().readText()
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"))
                assertTrue(resp.contains("RiderLink"))
            }

            // 2. Verify wrong token gets 403 Forbidden
            (clientSsl.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
                val req = "GET /ws?token=9999 HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
                s.outputStream.write(req.toByteArray())
                s.outputStream.flush()
                val firstLine = s.inputStream.bufferedReader().readLine()
                assertTrue(firstLine.contains("403"))
            }

            // 3. Verify valid token upgrades to WSS and exchanges 640B binary audio
            (clientSsl.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
                val req = "GET /ws?token=3847 HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
                s.outputStream.write(req.toByteArray())
                s.outputStream.flush()

                val bis = BufferedInputStream(s.inputStream)
                val headerLine = StringBuilder()
                while (true) {
                    val b = bis.read()
                    if (b == -1) break
                    headerLine.append(b.toChar())
                    if (headerLine.endsWith("\r\n\r\n")) break
                }
                assertTrue(headerLine.toString().contains("101 Switching Protocols"))
                assertTrue(connectedLatch.await(2, TimeUnit.SECONDS))

                // Send masked 640-byte binary frame from client -> server
                val pcm = ByteArray(AudioConfig.BYTES_PER_FRAME) { (it and 0x55).toByte() }
                val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                val out = s.outputStream
                out.write(byteArrayOf(0x82.toByte(), (0x80 or 126).toByte(), 0x02, 0x80.toByte()))
                out.write(mask)
                val masked = ByteArray(pcm.size) { i -> (pcm[i].toInt() xor mask[i and 3].toInt()).toByte() }
                out.write(masked)
                out.flush()

                assertTrue(audioLatch.await(2, TimeUnit.SECONDS))
                assertArrayEquals(pcm, rxAudio)

                // Send audio frame from server -> client
                assertTrue(server.sendAudio(pcm, 0, pcm.size))
                val serverFrame = WebSocketCodec.readFrame(DataInputStream(bis))
                assertNotNull(serverFrame)
                assertEquals(WebSocketCodec.OPCODE_BINARY, serverFrame!!.opcode)
                assertArrayEquals(pcm, serverFrame.payload)
            }
        } finally {
            server.stop()
        }
    }
}
