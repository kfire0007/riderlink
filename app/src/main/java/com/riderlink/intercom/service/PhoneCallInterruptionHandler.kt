package com.riderlink.intercom.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Detects incoming/active cellular phone calls so RiderLink pauses cleanly
 * and auto-resumes when the call ends.
 *
 * Listens on every active SIM, not just the default subscription: on a dual-SIM phone a call on
 * the non-default SIM (or with "ask every time" as the voice SIM) is invisible to a listener on
 * the default TelephonyManager. The rider counts as on a call while any SIM is ringing or off-hook.
 */
class PhoneCallInterruptionHandler(
    private val context: Context,
    private val onCallActiveChanged: (Boolean) -> Unit
) {
    private val telephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var started = false
    private var lastCallActive = false

    /** Call activity per listener key (a subscription id, or [DEFAULT_KEY]). Main thread only. */
    private val activeByKey = HashMap<Int, Boolean>()
    private val registrations = mutableListOf<() -> Unit>()

    fun start() {
        if (started) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        started = true
        try {
            for ((key, manager) in managersForActiveSims()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    registerApi31(key, manager)
                } else {
                    registerLegacy(key, manager)
                }
            }
        } catch (_: SecurityException) {
            stop()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        registrations.forEach { unregister -> runCatching { unregister() } }
        registrations.clear()
        activeByKey.clear()
        lastCallActive = false
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked in start()
    private fun managersForActiveSims(): List<Pair<Int, TelephonyManager>> {
        val subscriptions = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        }.getOrNull().orEmpty()
        if (subscriptions.isEmpty()) return listOf(DEFAULT_KEY to telephonyManager)
        return subscriptions.map { it.subscriptionId to telephonyManager.createForSubscriptionId(it.subscriptionId) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31(key: Int, manager: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(key, state)
            }
        }
        manager.registerTelephonyCallback(context.mainExecutor, cb)
        registrations += { manager.unregisterTelephonyCallback(cb) }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(key: Int, manager: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(key, state)
            }
        }
        manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        registrations += { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    private fun handleCallState(key: Int, state: Int) {
        if (!started) return
        activeByKey[key] = state == TelephonyManager.CALL_STATE_RINGING ||
            state == TelephonyManager.CALL_STATE_OFFHOOK
        val isActive = activeByKey.values.any { it }
        if (isActive != lastCallActive) {
            lastCallActive = isActive
            onCallActiveChanged(isActive)
        }
    }

    private companion object {
        const val DEFAULT_KEY = -1
    }
}
