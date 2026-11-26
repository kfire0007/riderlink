package com.riderlink.intercom.network

import com.riderlink.intercom.audio.AudioConfig
import com.riderlink.intercom.protocol.ControlCode
import com.riderlink.intercom.protocol.PacketHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UdpAudioTransportTest {

    @Test
    fun loopbackHostLearnsPeerExchangesAudioAndControlAndRejectsBadToken() {
        val token = 4321
        val hostAudioLatch = CountDownLatch(1)
        val hostControlLatch = CountDownLatch(1)
        val obrConnectedLatch = CountDownLatch(1)

        var receivedAudio: ByteArray? = null
        var receivedControl: Byte = 0

        val host = UdpAudioTransport(
            senderId = 0,
            sessionToken = token,
            listener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {
                    receivedAudio = payload.copyOfRange(offset, offset + length)
                    hostAudioLatch.countDown()
                }

                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {
                    receivedControl = controlCode
                    hostControlLatch.countDown()
                }

                override fun onLinkStateChanged(state: LinkState) {}
            }
        )

        val obr = UdpAudioTransport(
            senderId = 1,
            sessionToken = token,
            listener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {}
                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {}
                override fun onLinkStateChanged(state: LinkState) {
                    if (state == LinkState.CONNECTED) obrConnectedLatch.countDown()
                }
            }
        )

        val rogue = UdpAudioTransport(
            senderId = 1,
            sessionToken = 9999,
            listener = object : UdpAudioTransport.Listener {
                override fun onAudioPacket(header: PacketHeader, payload: ByteArray, offset: Int, length: Int) {}
                override fun onControlPacket(header: PacketHeader, controlCode: Byte) {}
                override fun onLinkStateChanged(state: LinkState) {}
            }
        )

        try {
            host.start(localPort = 0, initialPeer = null, boundNetwork = null)
            val hostPort = host.boundPort
            assertTrue(hostPort > 0)

            obr.start(
                localPort = 0,
                initialPeer = InetSocketAddress("127.0.0.1", hostPort),
                boundNetwork = null
            )

            // Wait for PING/PONG handshake
            assertTrue("OBR should reach CONNECTED via loopback", obrConnectedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(LinkState.CONNECTED, host.currentStats().linkState)

            // Rogue with wrong token sends control — must be ignored by Host
            rogue.start(localPort = 0, initialPeer = InetSocketAddress("127.0.0.1", hostPort))
            rogue.sendControl(ControlCode.GOODBYE)

            // OBR sends 640-byte audio frame + PEER_ON_CALL control
            val frame = ByteArray(AudioConfig.BYTES_PER_FRAME) { (it and 0x7F).toByte() }
            assertTrue(obr.sendAudio(frame, 0, frame.size))
            assertTrue(obr.sendControl(ControlCode.PEER_ON_CALL))

            assertTrue("Host should receive audio frame", hostAudioLatch.await(2, TimeUnit.SECONDS))
            assertTrue("Host should receive control packet", hostControlLatch.await(2, TimeUnit.SECONDS))
            assertArrayEquals(frame, receivedAudio)
            assertEquals(ControlCode.PEER_ON_CALL, receivedControl)
        } finally {
            rogue.stop()
            obr.stop()
            host.stop()
        }
    }
}
