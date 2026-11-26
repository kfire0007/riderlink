package com.riderlink.intercom.oem

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Checks Android battery optimization exemption and provides vivo/realme-specific
 * setup guidance so the intercom service is not killed during screen-off rides.
 */
object BatteryOptimizationHelper {

    data class OemBatteryAdvice(
        val manufacturerLabel: String,
        val isExempt: Boolean,
        val steps: List<String>
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getAdvice(context: Context): OemBatteryAdvice {
        val mfg = (Build.MANUFACTURER ?: "").lowercase()
        val exempt = isIgnoringBatteryOptimizations(context)

        return when {
            mfg.contains("vivo") || mfg.contains("iqoo") -> OemBatteryAdvice(
                manufacturerLabel = "vivo / iQOO (FuntouchOS / OriginOS)",
                isExempt = exempt,
                steps = listOf(
                    "Set Battery usage to Unrestricted (or High background power consumption).",
                    "In iManager / App Info -> Battery, enable Allow background activity and Auto-start."
                )
            )
            mfg.contains("realme") || mfg.contains("oppo") || mfg.contains("oneplus") -> OemBatteryAdvice(
                manufacturerLabel = "realme / OPPO (realme UI / ColorOS)",
                isExempt = exempt,
                steps = listOf(
                    "Open App Info -> Battery usage -> enable Allow background activity.",
                    "Enable Allow auto-launch so the intercom stays alive when the screen locks."
                )
            )
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> OemBatteryAdvice(
                manufacturerLabel = "Xiaomi / Redmi / POCO (MIUI / HyperOS)",
                isExempt = exempt,
                steps = listOf(
                    "Open App Info -> Battery saver -> select No restrictions.",
                    "Enable Autostart for RiderLink."
                )
            )
            mfg.contains("samsung") -> OemBatteryAdvice(
                manufacturerLabel = "Samsung (One UI)",
                isExempt = exempt,
                steps = listOf(
                    "Open App Info -> Battery -> select Unrestricted.",
                    "Confirm RiderLink is not in Sleeping or Deep Sleeping apps."
                )
            )
            else -> OemBatteryAdvice(
                manufacturerLabel = Build.MANUFACTURER ?: "Android",
                isExempt = exempt,
                steps = listOf(
                    "Open App Info -> Battery -> select Unrestricted / Don't optimize."
                )
            )
        }
    }

    @SuppressLint("BatteryLife")
    fun createRequestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun createAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
