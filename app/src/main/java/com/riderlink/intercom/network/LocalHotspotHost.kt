package com.riderlink.intercom.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Starts and manages an Android LocalOnlyHotspot (zero internet) for the Rider (Host).
 * Supports Android 10 (WifiConfiguration) and Android 11+ (SoftApConfiguration).
 */
class LocalHotspotHost(
    context: Context,
    private val scope: CoroutineScope
) {
    data class HotspotCredentials(
        val ssid: String,
        val passphrase: String,
        val hostIp: String
    )

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @SuppressLint("MissingPermission")
    fun start(
        onStarted: (HotspotCredentials) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val preExistingIps = listAllLocalIpv4s()
        Log.i(TAG, "Starting LocalOnlyHotspot (pre-existing IPs: $preExistingIps)")
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        val (ssid, pass) = extractCredentials(res)
                        if (ssid.isNullOrEmpty() || pass.isNullOrEmpty()) {
                            stop()
                            onError("Hotspot started without readable SSID/passphrase")
                            return
                        }
                        scope.launch(Dispatchers.IO) {
                            val hostIp = resolveHotspotIpWithRetries(preExistingIps)
                            if (hostIp == null) {
                                stop()
                                onError("Could not resolve local hotspot IP address")
                            } else {
                                Log.i(TAG, "LocalOnlyHotspot ready: ssid=$ssid, hostIp=$hostIp")
                                onStarted(HotspotCredentials(ssid, pass, hostIp))
                            }
                        }
                    }

                    override fun onStopped() {
                        reservation = null
                    }

                    override fun onFailed(reason: Int) {
                        reservation = null
                        val detail = when (reason) {
                            ERROR_NO_CHANNEL -> "No Wi-Fi channel available (try turning off Wi-Fi or use Manual LAN)"
                            ERROR_TETHERING_DISALLOWED -> "Hotspot disallowed by device policy"
                            ERROR_INCOMPATIBLE_MODE -> "Turn off existing Wi-Fi/Hotspot before starting"
                            else -> "Hotspot failed (code $reason)"
                        }
                        Log.w(TAG, "LocalOnlyHotspot failed: $detail")
                        onError(detail)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (se: SecurityException) {
            onError("Missing Location or Nearby Wi-Fi permission: ${se.message}")
        } catch (e: Exception) {
            onError("Failed to start LocalOnlyHotspot: ${e.message}")
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Exception) {
        }
        reservation = null
    }

    private fun extractCredentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            @Suppress("DEPRECATION")
            val ssid = config.ssid
            val pass = config.passphrase
            ssid to pass
        } else {
            @Suppress("DEPRECATION")
            val config = res.wifiConfiguration
            @Suppress("DEPRECATION")
            val ssid = config?.SSID
            @Suppress("DEPRECATION")
            val pass = config?.preSharedKey
            ssid to pass
        }
    }

    private suspend fun resolveHotspotIpWithRetries(preExistingIps: Set<String>): String? {
        repeat(15) {
            val ip = findSoftApIpv4(preExistingIps)
            if (ip != null) return ip
            delay(200L)
        }
        val fallback = LanFallbackConnector.findLocalIpv4()
        Log.w(TAG, "Falling back to LAN IP after SoftAP poll: $fallback")
        return fallback
    }

    private fun findSoftApIpv4(preExistingIps: Set<String>): String? {
        val candidates = listAllInterfaceAddresses()
        // 1. Prefer any newly created interface IP not present before startLocalOnlyHotspot()
        val newIps = candidates.filter { it.second !in preExistingIps }
        newIps.firstOrNull { it.second.endsWith(".1") }?.let { return it.second }
        newIps.firstOrNull()?.let { return it.second }

        // 2. Otherwise prefer typical SoftAP interface prefixes (ap*, swlan*, softap*, wlan1/2*)
        val apPrefixes = listOf("ap", "swlan", "softap", "wlan1", "wlan2")
        for (prefix in apPrefixes) {
            candidates.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
        }
        return null
    }

    private fun listAllLocalIpv4s(): Set<String> =
        listAllInterfaceAddresses().map { it.second }.toSet()

    private fun listAllInterfaceAddresses(): List<Pair<String, String>> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<Pair<String, String>>()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            for (addr in iface.inetAddresses.toList().filterIsInstance<Inet4Address>()) {
                if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    addr.hostAddress?.let { result.add(name to it) }
                }
            }
        }
        return result
    }

    companion object {
        private const val TAG = "RiderLink"
    }
}
