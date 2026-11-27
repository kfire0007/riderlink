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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var resolveJob: Job? = null

    @SuppressLint("MissingPermission")
    fun start(
        onStarted: (HotspotCredentials) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val preExistingIps = NetworkAddressResolver.listFilteredInterfaceIpv4s().map { it.second }.toSet()
        Log.i(TAG, "Starting LocalOnlyHotspot (pre-existing IPs: $preExistingIps)")
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        resolveJob?.cancel()
                        reservation = res
                        val (ssid, pass) = extractCredentials(res)
                        if (ssid.isNullOrEmpty() || pass.isNullOrEmpty()) {
                            stop()
                            onError("Hotspot started without readable SSID/passphrase")
                            return
                        }
                        resolveJob = scope.launch(Dispatchers.IO) {
                            val hostIp = resolveHotspotIpWithRetries(preExistingIps)
                            if (!isActive || reservation !== res) return@launch
                            withContext(Dispatchers.Main.immediate) {
                                if (reservation !== res) return@withContext
                                if (hostIp == null) {
                                    stop()
                                    onError("Could not resolve local hotspot IP address")
                                } else {
                                    Log.i(TAG, "LocalOnlyHotspot ready: ssid=$ssid, hostIp=$hostIp")
                                    onStarted(HotspotCredentials(ssid, pass, hostIp))
                                }
                            }
                        }
                    }

                    override fun onStopped() {
                        resolveJob?.cancel()
                        resolveJob = null
                        reservation = null
                    }

                    override fun onFailed(reason: Int) {
                        resolveJob?.cancel()
                        resolveJob = null
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
        resolveJob?.cancel()
        resolveJob = null
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
            val candidates = NetworkAddressResolver.listFilteredInterfaceIpv4s()
            val ip = NetworkAddressResolver.selectSoftApIpv4(candidates, preExistingIps)
            if (ip != null) return ip
            delay(200L)
        }
        val fallback = LanFallbackConnector.findLocalIpv4()
        Log.w(TAG, "Falling back to LAN IP after SoftAP poll: $fallback")
        return fallback
    }

    companion object {
        private const val TAG = "RiderLink"
    }
}
