package com.riderlink.intercom.service

import android.media.AudioDeviceInfo

/** An input device as the mic picker sees it (a plain copy of [AudioDeviceInfo]'s fields). */
data class MicCandidate(val id: Int, val type: Int, val address: String)

/**
 * Which mic goes with the headset the intercom routes to. Android's call routing usually moves
 * the mic with the audio, but not reliably on every phone, so RiderLink also points its recorder
 * at this mic directly.
 */
object HeadsetMic {

    /** What a headset output can offer for a mic. */
    sealed interface Pick {
        /** Use this input. */
        data class Use(val mic: MicCandidate) : Pick

        /** The headset has no mic (plain wired headphones): the phone's mic is the only one. */
        data object HeadsetHasNoMic : Pick

        /** Not a headset (speaker), or its mic isn't listed yet: let Android choose. */
        data object PhoneDefault : Pick
    }

    /**
     * Picks the mic for the [outputType] output at [outputAddress] from [inputs]. A matching
     * address wins (two Bluetooth headsets), otherwise the first input of the right type.
     */
    fun pick(outputType: Int, outputAddress: String, inputs: List<MicCandidate>): Pick {
        if (outputType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) return Pick.HeadsetHasNoMic
        val wanted = inputTypesFor(outputType) ?: return Pick.PhoneDefault
        val matching = inputs.filter { it.type in wanted }
        val mic = matching.firstOrNull { outputAddress.isNotEmpty() && it.address == outputAddress }
            ?: matching.firstOrNull()
            ?: return Pick.PhoneDefault
        return Pick.Use(mic)
    }

    /** True when [type] is a mic that belongs to a headset rather than the phone. */
    fun isHeadsetMic(type: Int): Boolean = type in HEADSET_MIC_TYPES

    private fun inputTypesFor(outputType: Int): Set<Int>? = when (outputType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        TYPE_BLE_HEADSET -> setOf(TYPE_BLE_HEADSET)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        AudioDeviceInfo.TYPE_USB_HEADSET -> setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        else -> null
    }

    /** `AudioDeviceInfo.TYPE_BLE_HEADSET` (API 31); a literal so it compiles against minSdk 29. */
    const val TYPE_BLE_HEADSET = 26

    private val HEADSET_MIC_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
}
