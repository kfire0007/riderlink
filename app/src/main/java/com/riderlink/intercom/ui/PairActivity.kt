package com.riderlink.intercom.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.riderlink.intercom.R
import com.riderlink.intercom.databinding.ActivityPairBinding
import com.riderlink.intercom.network.ConnectionState
import com.riderlink.intercom.network.LanFallbackConnector
import com.riderlink.intercom.network.Role
import com.riderlink.intercom.network.TransportMode
import com.riderlink.intercom.protocol.QrPayload
import com.riderlink.intercom.protocol.SessionToken
import com.riderlink.intercom.service.IntercomForegroundService
import kotlinx.coroutines.launch

class PairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairBinding
    private lateinit var role: Role
    private var selectedMode: TransportMode = TransportMode.LOCAL_ONLY_HOTSPOT
    private var isIphoneHostTab = false
    private var iphoneStepSafari = false
    private var lastHostQrPayload: QrPayload? = null

    private var service: IntercomForegroundService? = null
    private var bound = false
    private var navigatedToRide = false

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePreFlightAndStartIfReady()
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val parsed = QrPayload.parse(raw)
        if (parsed == null) {
            showError("Invalid RiderLink QR code. Make sure you scan the rider's screen.")
            return@registerForActivityResult
        }
        hideError()
        ensureServiceStartedAndJoinQr(parsed)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as IntercomForegroundService.LocalBinder).getService()
            service = s
            bound = true
            observeServiceState(s)
            if (role == Role.HOST_RIDER) {
                updatePreFlightAndStartIfReady()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val roleName = intent.getStringExtra(EXTRA_ROLE) ?: Role.HOST_RIDER.name
        role = Role.valueOf(roleName)

        binding.txtPairTitle.setText(
            if (role == Role.HOST_RIDER) R.string.pair_host_title else R.string.pair_join_title
        )
        binding.btnTabIphone.visibility = if (role == Role.HOST_RIDER) View.VISIBLE else View.GONE

        binding.btnBack.setOnClickListener {
            service?.stopSession()
            finish()
        }

        binding.edtHostIp.setText(prefs.getString(KEY_LAST_IP, "192.168.43.1"))
        binding.edtPort.setText(prefs.getInt(KEY_LAST_PORT, 49152).toString())

        binding.toggleTransportMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTabQr -> {
                    isIphoneHostTab = false
                    selectedMode = TransportMode.LOCAL_ONLY_HOTSPOT
                }
                R.id.btnTabIphone -> {
                    isIphoneHostTab = true
                    selectedMode = TransportMode.LOCAL_ONLY_HOTSPOT
                }
                else -> {
                    isIphoneHostTab = false
                    selectedMode = TransportMode.MANUAL_LAN
                }
            }
            hideError()
            renderRolePanels()
            if (role == Role.HOST_RIDER) {
                updatePreFlightAndStartIfReady()
            }
        }

        binding.toggleIphoneStep.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            iphoneStepSafari = (checkedId == R.id.btnIphoneStepSafari)
            renderHostQr()
        }

        binding.btnScanQr.setOnClickListener {
            val missing = PermissionHelper.missingPermissions(this, includeCamera = true)
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }
            launchQrScanner()
        }

        binding.btnManualConnect.setOnClickListener {
            val missing = PermissionHelper.missingPermissions(this, includeCamera = false)
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }
            val parsed = LanFallbackConnector.validateJoinInputs(
                binding.edtHostIp.text.toString(),
                binding.edtPort.text.toString(),
                binding.edtSessionCode.text.toString()
            )
            if (parsed == null) {
                showError("Enter a valid Host IP, port, and 4-digit session code.")
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_LAST_IP, parsed.hostIp)
                .putInt(KEY_LAST_PORT, parsed.udpPort)
                .apply()
            hideError()
            ensureServiceStarted()
            service?.joinViaManualLan(parsed.hostIp, parsed.udpPort, parsed.sessionToken)
        }

        binding.btnRetry.setOnClickListener {
            hideError()
            if (role == Role.HOST_RIDER) {
                updatePreFlightAndStartIfReady()
            }
        }

        renderRolePanels()
        bindService(
            Intent(this, IntercomForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        if (role == Role.HOST_RIDER && bound) {
            updatePreFlightAndStartIfReady()
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        if (!navigatedToRide && isFinishing) {
            service?.stopSession()
        }
        super.onDestroy()
    }

    private fun renderRolePanels() {
        when (role) {
            Role.HOST_RIDER -> {
                binding.panelHost.visibility = View.VISIBLE
                binding.toggleIphoneStep.visibility = if (isIphoneHostTab) View.VISIBLE else View.GONE
                binding.panelJoinQr.visibility = View.GONE
                binding.panelJoinManual.visibility = View.GONE
                renderHostQr()
            }
            Role.JOIN_OBR -> {
                binding.panelHost.visibility = View.GONE
                binding.toggleIphoneStep.visibility = View.GONE
                binding.panelJoinQr.visibility =
                    if (selectedMode == TransportMode.LOCAL_ONLY_HOTSPOT) View.VISIBLE else View.GONE
                binding.panelJoinManual.visibility =
                    if (selectedMode == TransportMode.MANUAL_LAN) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderHostQr() {
        val qr = lastHostQrPayload ?: return
        if (isIphoneHostTab) {
            if (!iphoneStepSafari) {
                binding.imgQrCode.setImageBitmap(QrBitmapGenerator.generate(qr.toStandardWifiQr()))
                binding.txtHostStatus.setText(R.string.iphone_hint_wifi)
                binding.txtHostAddress.text = "Wi-Fi: ${qr.ssid}"
            } else {
                val url = qr.toIosWebUrl()
                binding.imgQrCode.setImageBitmap(QrBitmapGenerator.generate(url))
                binding.txtHostStatus.setText(R.string.iphone_hint_safari)
                binding.txtHostAddress.text = url
            }
        } else {
            binding.imgQrCode.setImageBitmap(QrBitmapGenerator.generate(qr.encode()))
            binding.txtHostStatus.setText(R.string.host_waiting_scan)
            binding.txtHostAddress.text = "${qr.hostIp}:${qr.udpPort}"
        }
        binding.txtHostSessionCode.text =
            "${getString(R.string.host_code_label)}: ${SessionToken.format(qr.sessionToken)}"
    }

    private fun updatePreFlightAndStartIfReady() {
        val missing = PermissionHelper.missingPermissions(
            this,
            includeCamera = role == Role.JOIN_OBR && selectedMode == TransportMode.LOCAL_ONLY_HOTSPOT
        )
        if (missing.isNotEmpty()) {
            binding.bannerPreFlight.visibility = View.VISIBLE
            binding.txtPreFlightMsg.text = "Grant audio, Wi-Fi, and phone-call permissions to continue."
            binding.btnPreFlightAction.setText(R.string.grant_permissions)
            binding.btnPreFlightAction.setOnClickListener {
                permissionLauncher.launch(missing.toTypedArray())
            }
            return
        }

        if (role == Role.HOST_RIDER &&
            selectedMode == TransportMode.LOCAL_ONLY_HOTSPOT &&
            !PermissionHelper.isLocationEnabledForHotspot(this)
        ) {
            binding.bannerPreFlight.visibility = View.VISIBLE
            binding.txtPreFlightMsg.setText(R.string.location_required_banner)
            binding.btnPreFlightAction.setText(R.string.open_location_settings)
            binding.btnPreFlightAction.setOnClickListener {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            return
        }

        binding.bannerPreFlight.visibility = View.GONE

        if (role == Role.HOST_RIDER) {
            val s = service ?: return
            val current = s.connectionState.value
            val alreadyMatches = current is ConnectionState.HostReady && current.mode == selectedMode
            if (!alreadyMatches && current !is ConnectionState.StartingHotspot) {
                ensureServiceStarted()
                if (selectedMode == TransportMode.LOCAL_ONLY_HOTSPOT) {
                    s.startHostHotspot()
                } else {
                    s.startHostManualLan()
                }
            }
        }
    }

    private fun ensureServiceStarted() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, IntercomForegroundService::class.java)
        )
    }

    private fun ensureServiceStartedAndJoinQr(qrPayload: QrPayload) {
        ensureServiceStarted()
        service?.joinViaQr(qrPayload)
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point camera at the rider's QR code")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        qrScanLauncher.launch(options)
    }

    private fun observeServiceState(s: IntercomForegroundService) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                s.connectionState.collect { state ->
                    when (state) {
                        ConnectionState.Idle -> Unit
                        ConnectionState.StartingHotspot -> {
                            binding.txtHostStatus.setText(R.string.host_starting_hotspot)
                        }
                        is ConnectionState.HostReady -> {
                            lastHostQrPayload = state.qrPayload
                            renderHostQr()
                        }
                        is ConnectionState.JoiningWifi -> {
                            binding.txtJoinStatus.setText(R.string.join_connecting_wifi)
                        }
                        is ConnectionState.ConnectingUdp -> {
                            binding.txtJoinStatus.setText(R.string.join_connecting_udp)
                        }
                        is ConnectionState.Connected -> {
                            if (!navigatedToRide) {
                                navigatedToRide = true
                                startActivity(Intent(this@PairActivity, RideActivity::class.java))
                                finish()
                            }
                        }
                        is ConnectionState.Error -> {
                            showError(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun showError(msg: String) {
        binding.bannerError.visibility = View.VISIBLE
        binding.txtErrorMsg.text = msg
    }

    private fun hideError() {
        binding.bannerError.visibility = View.GONE
    }

    companion object {
        const val EXTRA_ROLE = "extra_role"
        private const val PREFS_NAME = "riderlink_prefs"
        private const val KEY_LAST_IP = "last_host_ip"
        private const val KEY_LAST_PORT = "last_host_port"
    }
}
