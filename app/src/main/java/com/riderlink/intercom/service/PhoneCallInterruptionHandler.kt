package com.riderlink.intercom.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Detects incoming/active cellular phone calls so RiderLink pauses cleanly
 * and auto-resumes when the call ends.
 */
class PhoneCallInterruptionHandler(
    private val context: Context,
    private val onCallActiveChanged: (Boolean) -> Unit
) {
    private val telephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var started = false
    private var lastCallActive = false
    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    fun start() {
        if (started) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        started = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerApi31()
            } else {
                registerLegacy()
            }
        } catch (_: SecurityException) {
            started = false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            unregisterApi31()
        } else {
            unregisterLegacy()
        }
        lastCallActive = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31() {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        telephonyCallback = cb
        telephonyManager.registerTelephonyCallback(context.mainExecutor, cb)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterApi31() {
        val cb = telephonyCallback as? TelephonyCallback ?: return
        telephonyCallback = null
        try {
            telephonyManager.unregisterTelephonyCallback(cb)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state)
            }
        }
        legacyListener = listener
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacy() {
        val listener = legacyListener ?: return
        legacyListener = null
        try {
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {
        }
    }

    private fun handleCallState(state: Int) {
        val isActive = state == TelephonyManager.CALL_STATE_RINGING ||
            state == TelephonyManager.CALL_STATE_OFFHOOK
        if (isActive != lastCallActive) {
            lastCallActive = isActive
            onCallActiveChanged(isActive)
        }
    }
}
