package com.riderlink.intercom.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.databinding.ViewDiagnosticsDrawerBinding
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStats
import com.riderlink.intercom.ride.NetworkKind
import com.riderlink.intercom.ride.RideState
import com.riderlink.intercom.ride.RideStats
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.AudioRouteType
import java.util.Locale

class LiveDiagnosticsDrawer(
    private val context: Context,
    private val binding: ViewDiagnosticsDrawerBinding
) {
    data class DeviceStatus(
        val wakeLockHeld: Boolean,
        val wifiLockHeld: Boolean,
        val batteryExempt: Boolean,
        val jitterDepthFrames: Int,
        /** Host only: cost of the last mix tick; 0 on a Peer. */
        val mixTickMicros: Long = 0L
    )

    fun render(ride: RideState, stats: RideStats, route: AudioRouteState, device: DeviceStatus) {
        val primary = stats.links.firstOrNull()?.stats ?: LinkStats()
        binding.txtDiagRtt.text = String.format(Locale.US, "%d ms", primary.rttMs)
        binding.txtDiagLoss.text = String.format(Locale.US, "%.1f%%", primary.packetLossPercent)

        binding.txtDiagRoute.text = when (route.routeType) {
            AudioRouteType.BLUETOOTH_HEADSET -> "BT: ${route.deviceName}"
            AudioRouteType.WIRED_HEADSET -> "Wired: ${route.deviceName}"
            AudioRouteType.PHONE_SPEAKER -> "Phone Speaker"
        }

        val networkLabel = when (ride.networkKind) {
            NetworkKind.HOTSPOT -> "Hotspot"
            NetworkKind.SHARED_WIFI -> "Shared Wi-Fi"
            null -> "Idle"
        }
        val linkLabels = stats.links.joinToString { link ->
            val kind = if (link.kind == LinkKind.SAFARI) "iPhone Safari" else "Android"
            "$kind ${link.stats.address ?: "?"}"
        }.ifEmpty { "no peers" }
        binding.txtDiagTransport.text = "$networkLabel ($linkLabels)"

        binding.txtDiagJitter.text = if (device.mixTickMicros > 0) {
            String.format(Locale.US, "Mixing %.1f ms / 20 ms", device.mixTickMicros / 1000.0)
        } else {
            "${device.jitterDepthFrames} frames"
        }

        val wake = if (device.wakeLockHeld) "On" else "Off"
        val wifi = if (device.wifiLockHeld) "On" else "Off"
        binding.txtDiagLocks.text = "Wake $wake / Wi-Fi $wifi"

        if (device.batteryExempt) {
            binding.txtDiagBattery.text = "Battery: Unrestricted (ready)"
            binding.txtDiagBattery.setTextColor(ContextCompat.getColor(context, R.color.live_emerald))
        } else {
            binding.txtDiagBattery.text = "Battery: Restricted (tap Home bar to exempt)"
            binding.txtDiagBattery.setTextColor(ContextCompat.getColor(context, R.color.signal_amber))
        }
    }
}
