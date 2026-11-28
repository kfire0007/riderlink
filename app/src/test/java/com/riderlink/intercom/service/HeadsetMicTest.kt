package com.riderlink.intercom.service

import android.media.AudioDeviceInfo
import com.riderlink.intercom.service.HeadsetMic.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetMicTest {

    private val phoneMic = MicCandidate(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom")
    private val earbuds = MicCandidate(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB")
    private val helmet = MicCandidate(8, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "CC:DD")
    private val wiredMic = MicCandidate(9, AudioDeviceInfo.TYPE_WIRED_HEADSET, "")
    private val usbMic = MicCandidate(10, AudioDeviceInfo.TYPE_USB_DEVICE, "card=1")

    @Test
    fun bluetoothEarbudsUseTheirOwnMic_matchedByAddress() {
        val pick = HeadsetMic.pick(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "CC:DD", listOf(phoneMic, earbuds, helmet))
        assertEquals(Pick.Use(helmet), pick)
    }

    @Test
    fun withoutAnAddressMatch_theFirstMicOfTheRightTypeIsUsed() {
        val pick = HeadsetMic.pick(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "", listOf(phoneMic, earbuds, helmet))
        assertEquals(Pick.Use(earbuds), pick)
    }

    @Test
    fun wiredAndUsbHeadsetsUseTheirMics() {
        assertEquals(Pick.Use(wiredMic), HeadsetMic.pick(AudioDeviceInfo.TYPE_WIRED_HEADSET, "", listOf(phoneMic, wiredMic)))
        assertEquals(Pick.Use(usbMic), HeadsetMic.pick(AudioDeviceInfo.TYPE_USB_HEADSET, "card=1", listOf(phoneMic, usbMic)))
    }

    @Test
    fun plainHeadphonesHaveNoMic() {
        assertEquals(Pick.HeadsetHasNoMic, HeadsetMic.pick(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", listOf(phoneMic)))
    }

    @Test
    fun theSpeaker_orAHeadsetWhoseMicIsNotListedYet_leavesItToAndroid() {
        assertEquals(Pick.PhoneDefault, HeadsetMic.pick(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", listOf(phoneMic)))
        assertEquals(Pick.PhoneDefault, HeadsetMic.pick(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", listOf(phoneMic)))
    }

    @Test
    fun onlyHeadsetMicsCountAsHeadsetMics() {
        assertTrue(HeadsetMic.isHeadsetMic(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(HeadsetMic.isHeadsetMic(HeadsetMic.TYPE_BLE_HEADSET))
        assertFalse(HeadsetMic.isHeadsetMic(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(HeadsetMic.isHeadsetMic(AudioDeviceInfo.TYPE_TELEPHONY))
    }
}
