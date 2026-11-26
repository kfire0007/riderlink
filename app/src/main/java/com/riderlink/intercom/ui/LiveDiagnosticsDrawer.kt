package com.riderlink.intercom.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.databinding.ViewDiagnosticsDrawerBinding
import com.riderlink.intercom.network.TransportMode
import com.riderlink.intercom.network.TransportStats
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.AudioRouteType
import com.riderlink.intercom.service.IntercomForegroundService
import java.util.Locale

class LiveDiagnosticsDrawer(
    private val context: Context,
    private val binding: ViewDiagnosticsDrawerBinding
) {
    fun render(
        stats: TransportStats,
        diag: IntercomForegroundService.ServiceDiagnostics,
        route: AudioRouteState,
        transportMode: TransportMode?
    ) {
        binding.txtDiagRtt.text = String.format(Locale.US, "%d ms", stats.rttMs)
        binding.txtDiagLoss.text = String.format(Locale.US, "%.1f%%", stats.packetLossPercent)

        val routeLabel = when (route.routeType) {
            AudioRouteType.BLUETOOTH_HEADSET -> "BT: ${route.deviceName}"
            AudioRouteType.WIRED_HEADSET -> "Wired: ${route.deviceName}"
            AudioRouteType.PHONE_SPEAKER -> "Phone Speaker"
        }
        binding.txtDiagRoute.text = routeLabel

        val modeLabel = when (transportMode) {
            TransportMode.LOCAL_ONLY_HOTSPOT -> "Hotspot"
            TransportMode.MANUAL_LAN -> "Manual LAN"
            TransportMode.WEB_IOS_BRIDGE -> "iPhone Safari (WSS)"
            null -> "Idle"
        }
        val peer = stats.peerAddress ?: "none"
        binding.txtDiagTransport.text = "$modeLabel ($peer)"

        binding.txtDiagJitter.text = "${diag.jitterDepthFrames} frames"

        val wake = if (diag.wakeLockHeld) "On" else "Off"
        val wifi = if (diag.wifiLockHeld) "On" else "Off"
        binding.txtDiagLocks.text = "Wake $wake / Wi-Fi $wifi"

        if (diag.isBatteryExempt) {
            binding.txtDiagBattery.text = "Battery: Unrestricted (ready)"
            binding.txtDiagBattery.setTextColor(
                ContextCompat.getColor(context, R.color.live_emerald)
            )
        } else {
            binding.txtDiagBattery.text = "Battery: Restricted (tap Home bar to exempt)"
            binding.txtDiagBattery.setTextColor(
                ContextCompat.getColor(context, R.color.signal_amber)
            )
        }
    }
}
