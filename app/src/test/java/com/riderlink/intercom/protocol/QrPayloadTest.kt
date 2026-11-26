package com.riderlink.intercom.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

    @Test
    fun roundTripStandardAndEscapedCharacters() {
        val payload = QrPayload(
            ssid = "AndroidShare|4912%Test",
            passphrase = "pass|word%100",
            hostIp = "192.168.49.1",
            udpPort = 49152,
            sessionToken = 3847
        )
        val encoded = payload.encode()
        assertEquals("RL1|AndroidShare%7C4912%25Test|pass%7Cword%25100|192.168.49.1|49152|3847", encoded)

        val parsed = QrPayload.parse(encoded)
        assertEquals(payload, parsed)
    }

    @Test
    fun rejectsMalformedQrPayloads() {
        assertNull(QrPayload.parse("RL2|ssid|pass|192.168.49.1|49152|1234"))
        assertNull(QrPayload.parse("RL1|ssid|pass|192.168.49.1|49152"))
        assertNull(QrPayload.parse("RL1||pass|192.168.49.1|49152|1234"))
        assertNull(QrPayload.parse("RL1|ssid|pass|999.168.49.1|49152|1234"))
        assertNull(QrPayload.parse("RL1|ssid|pass|192.168.49.1|70000|1234"))
        assertNull(QrPayload.parse("RL1|ssid|pass|192.168.49.1|49152|99999"))
        assertNull(QrPayload.parse("RL1|ssid%ZZ|pass|192.168.49.1|49152|1234"))
    }
}
