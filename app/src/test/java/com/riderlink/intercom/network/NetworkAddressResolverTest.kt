package com.riderlink.intercom.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAddressResolverTest {

    @Test
    fun selectSoftApIpv4PrefersNew192168Dot1OverPreExistingWlan() {
        val preExisting = setOf("192.168.0.175")
        val candidates = listOf(
            "wlan0" to "192.168.0.175",
            "ap0" to "192.168.49.1"
        )
        assertEquals("192.168.49.1", NetworkAddressResolver.selectSoftApIpv4(candidates, preExisting))
    }

    @Test
    fun selectSoftApIpv4FallsBackToExistingApInterface() {
        val preExisting = setOf("192.168.0.175", "192.168.43.1")
        val candidates = listOf(
            "wlan0" to "192.168.0.175",
            "swlan0" to "192.168.43.1"
        )
        assertEquals("192.168.43.1", NetworkAddressResolver.selectSoftApIpv4(candidates, preExisting))
    }

    @Test
    fun selectLanIpv4PrefersWifiAndSoftApInterfaces() {
        val candidates = listOf(
            "eth0" to "10.0.0.2",
            "wlan0" to "192.168.1.44"
        )
        assertEquals("192.168.1.44", NetworkAddressResolver.selectLanIpv4(candidates))
    }

    @Test
    fun validateJoinInputsAcceptsValidAndRejectsInvalidValues() {
        val valid = LanFallbackConnector.validateJoinInputs(" 192.168.43.1 ", "49152", "0742")
        assertNotNull(valid)
        assertEquals("192.168.43.1", valid!!.hostIp)
        assertEquals(49152, valid.udpPort)
        assertEquals(742, valid.sessionToken)

        assertNull(LanFallbackConnector.validateJoinInputs("999.168.43.1", "49152", "0742"))
        assertNull(LanFallbackConnector.validateJoinInputs("192.168.43.1", "70000", "0742"))
        assertNull(LanFallbackConnector.validateJoinInputs("192.168.43.1", "49152", "99999"))
        assertNull(LanFallbackConnector.validateJoinInputs("not-an-ip", "49152", "0742"))
    }
}
