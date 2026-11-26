package com.riderlink.intercom.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRouteType {
    BLUETOOTH_HEADSET,
    WIRED_HEADSET,
    PHONE_SPEAKER
}

data class AudioRouteState(
    val routeType: AudioRouteType = AudioRouteType.PHONE_SPEAKER,
    val deviceName: String = "Phone Speaker",
    val isSpeakerFallback: Boolean = true
)

/**
 * Automatically routes intercom voice to Bluetooth HFP/SCO or wired/USB-C earphones
 * with live mid-ride hot-swap, falling back to phone speaker only when no headset exists.
 */
class HeadsetRouteManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _routeState = MutableStateFlow(AudioRouteState())
    val routeState: StateFlow<AudioRouteState> = _routeState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var suspendedForCall = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (started && !suspendedForCall) updateBestRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (started && !suspendedForCall) updateBestRoute()
        }
    }

    fun start() {
        if (started) return
        started = true
        suspendedForCall = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        updateBestRoute()
    }

    fun suspendForCellularCall() {
        if (!started || suspendedForCall) return
        suspendedForCall = true
        clearActiveCommunicationRoute()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    fun resumeAfterCellularCall() {
        if (!started || !suspendedForCall) return
        suspendedForCall = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        updateBestRoute()
    }

    fun stop() {
        if (!started) return
        started = false
        suspendedForCall = false
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        clearActiveCommunicationRoute()
        audioManager.mode = AudioManager.MODE_NORMAL
        _routeState.value = AudioRouteState()
    }

    private fun updateBestRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateRouteApi31()
        } else {
            updateRouteLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun updateRouteApi31() {
        val devices = audioManager.availableCommunicationDevices
        val btDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        val wiredDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val speakerDevice = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        val chosen = btDevice ?: wiredDevice ?: speakerDevice
        if (chosen != null) {
            try {
                audioManager.setCommunicationDevice(chosen)
            } catch (_: Exception) {
            }
        }

        _routeState.value = when {
            btDevice != null -> AudioRouteState(
                routeType = AudioRouteType.BLUETOOTH_HEADSET,
                deviceName = btDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Headset",
                isSpeakerFallback = false
            )
            wiredDevice != null -> AudioRouteState(
                routeType = AudioRouteType.WIRED_HEADSET,
                deviceName = wiredDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Wired Earphones",
                isSpeakerFallback = false
            )
            else -> AudioRouteState(
                routeType = AudioRouteType.PHONE_SPEAKER,
                deviceName = "Phone Speaker",
                isSpeakerFallback = true
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun updateRouteLegacy() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wiredDevice = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        when {
            btDevice != null -> {
                audioManager.isSpeakerphoneOn = false
                try {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } catch (_: Exception) {
                }
                _routeState.value = AudioRouteState(
                    routeType = AudioRouteType.BLUETOOTH_HEADSET,
                    deviceName = btDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Headset",
                    isSpeakerFallback = false
                )
            }
            wiredDevice != null -> {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = false
                _routeState.value = AudioRouteState(
                    routeType = AudioRouteType.WIRED_HEADSET,
                    deviceName = wiredDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Wired Earphones",
                    isSpeakerFallback = false
                )
            }
            else -> {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = true
                _routeState.value = AudioRouteState(
                    routeType = AudioRouteType.PHONE_SPEAKER,
                    deviceName = "Phone Speaker",
                    isSpeakerFallback = true
                )
            }
        }
    }

    private fun clearActiveCommunicationRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        } else {
            stopLegacySco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacySco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
            }
            audioManager.stopBluetoothSco()
        } catch (_: Exception) {
        }
    }
}
