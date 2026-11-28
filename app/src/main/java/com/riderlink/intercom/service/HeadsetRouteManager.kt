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
    val isSpeakerFallback: Boolean = true,
    /** The mic the intercom is actually recording from, as Android reports it. */
    val micName: String = "Phone mic",
    val micOnHeadset: Boolean = false,
    /** The headset is plain headphones, so the phone's mic is the only one. */
    val headsetHasNoMic: Boolean = false
) {
    /** A headset is in use but the voice still comes from the phone's own mic. */
    val usingPhoneMicWithHeadset: Boolean get() = !isSpeakerFallback && !micOnHeadset
}

/**
 * Automatically routes intercom voice to Bluetooth HFP/SCO or wired/USB-C earphones
 * with live mid-ride hot-swap, falling back to phone speaker only when no headset exists.
 *
 * The mic follows the headset: [preferredMic] is the headset's own mic (the audio engine points
 * its recorder at it and reopens the mic when it changes), and [onMicRouted] reports the mic
 * Android actually used, so the ride screen can say when the phone's mic is still live.
 */
class HeadsetRouteManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _routeState = MutableStateFlow(AudioRouteState())
    val routeState: StateFlow<AudioRouteState> = _routeState.asStateFlow()

    private val _preferredMic = MutableStateFlow<AudioDeviceInfo?>(null)

    /** The headset mic the recorder should use; null lets Android pick (the phone's mic). */
    val preferredMic: StateFlow<AudioDeviceInfo?> = _preferredMic.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var suspendedForCall = false

    /** Android may refuse a route while Bluetooth is still connecting; we try again a few times. */
    private var routeRetries = 0
    private val retryRoute = Runnable { if (started && !suspendedForCall) updateBestRoute(isRetry = true) }

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
        mainHandler.removeCallbacks(retryRoute)
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
        mainHandler.removeCallbacks(retryRoute)
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
        clearActiveCommunicationRoute()
        audioManager.mode = AudioManager.MODE_NORMAL
        _preferredMic.value = null
        _routeState.value = AudioRouteState()
    }

    /** From the audio engine (any thread): the mic its recorder ended up on. */
    fun onMicRouted(mic: AudioDeviceInfo?) {
        mainHandler.post {
            if (!started) return@post
            val onHeadset = mic != null && HeadsetMic.isHeadsetMic(mic.type)
            val name = when {
                mic == null -> "Phone mic"
                onHeadset -> mic.productName?.toString()?.takeIf { it.isNotBlank() }?.let { "$it mic" } ?: "Headset mic"
                else -> "Phone mic"
            }
            _routeState.value = _routeState.value.copy(micName = name, micOnHeadset = onHeadset)
        }
    }

    private fun updateBestRoute(isRetry: Boolean = false) {
        mainHandler.removeCallbacks(retryRoute)
        if (!isRetry) routeRetries = 0
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
            val accepted = try {
                audioManager.setCommunicationDevice(chosen)
            } catch (_: Exception) {
                false
            }
            // Android can say yes and still not switch (Bluetooth not ready yet): check, and retry.
            if ((!accepted || audioManager.communicationDevice?.id != chosen.id) && routeRetries < MAX_ROUTE_RETRIES) {
                routeRetries++
                mainHandler.postDelayed(retryRoute, ROUTE_RETRY_MS)
            }
        }

        val headset = btDevice ?: wiredDevice
        publishRoute(headset)
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
                // SCO connects asynchronously; look again until its mic shows up.
                if (!audioManager.isBluetoothScoOn && routeRetries < MAX_ROUTE_RETRIES) {
                    routeRetries++
                    mainHandler.postDelayed(retryRoute, ROUTE_RETRY_MS)
                }
            }
            wiredDevice != null -> {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = false
            }
            else -> {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = true
            }
        }
        publishRoute(btDevice ?: wiredDevice)
    }

    /** Publishes the route for [headset] (null: speaker) and picks the mic that goes with it. */
    private fun publishRoute(headset: AudioDeviceInfo?) {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val pick = if (headset == null) {
            HeadsetMic.Pick.PhoneDefault
        } else {
            HeadsetMic.pick(
                headset.type,
                headset.address.orEmpty(),
                inputs.map { MicCandidate(it.id, it.type, it.address.orEmpty()) }
            )
        }
        val mic = (pick as? HeadsetMic.Pick.Use)?.let { use -> inputs.firstOrNull { it.id == use.mic.id } }
        if (_preferredMic.value?.id != mic?.id) _preferredMic.value = mic
        if (headset != null && pick == HeadsetMic.Pick.PhoneDefault && routeRetries < MAX_ROUTE_RETRIES) {
            // A Bluetooth headset's mic is often listed a moment after its speaker.
            routeRetries++
            mainHandler.postDelayed(retryRoute, ROUTE_RETRY_MS)
        }

        val previous = _routeState.value
        val isBt = headset != null && (
            headset.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || headset.type == HeadsetMic.TYPE_BLE_HEADSET
            )
        _routeState.value = when {
            headset == null -> AudioRouteState(micName = previous.micName, micOnHeadset = previous.micOnHeadset)
            else -> AudioRouteState(
                routeType = if (isBt) AudioRouteType.BLUETOOTH_HEADSET else AudioRouteType.WIRED_HEADSET,
                deviceName = headset.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: if (isBt) "Bluetooth Headset" else "Wired Earphones",
                isSpeakerFallback = false,
                micName = previous.micName,
                micOnHeadset = previous.micOnHeadset,
                headsetHasNoMic = pick == HeadsetMic.Pick.HeadsetHasNoMic
            )
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

    private companion object {
        const val MAX_ROUTE_RETRIES = 5
        const val ROUTE_RETRY_MS = 600L
    }
}
