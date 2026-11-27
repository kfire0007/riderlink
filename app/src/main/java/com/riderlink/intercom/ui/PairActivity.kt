package com.riderlink.intercom.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.riderlink.intercom.R
import com.riderlink.intercom.RiderLinkApp
import com.riderlink.intercom.databinding.ActivityPairBinding
import com.riderlink.intercom.network.LanFallbackConnector
import com.riderlink.intercom.protocol.QrPayload
import com.riderlink.intercom.protocol.SessionToken
import com.riderlink.intercom.ride.JoinVia
import com.riderlink.intercom.ride.NetworkKind
import com.riderlink.intercom.ride.PairingInvitation
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideRole
import com.riderlink.intercom.ride.RideState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pre-flight checks (permissions, Location) and pairing screens. Sends host/join/retry/end
 * commands to the app-scoped Ride and renders its state; it never starts or binds the service.
 */
class PairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairBinding
    private lateinit var role: RideRole
    private var selectedNetwork = NetworkKind.HOTSPOT
    private var isIphoneHostTab = false
    private var iphoneStepSafari = false
    private var lastHostInvitation: PairingInvitation? = null
    private var lastScannedQr: QrPayload? = null

    private val ride by lazy { RiderLinkApp.from(this).ride }
    private var navigatedToRide = false
    private var rideEndedByUser = false

    private val qrBitmapCache = HashMap<String, Bitmap>(4)
    private var qrRenderJob: Job? = null

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
        lastScannedQr = parsed
        showWifiFallbackPanel(parsed)
        joinViaScannedQr(parsed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        val roleName = intent.getStringExtra(EXTRA_ROLE) ?: RideRole.HOST.name
        role = RideRole.valueOf(roleName)

        binding.txtPairTitle.setText(
            if (role == RideRole.HOST) R.string.pair_host_title else R.string.pair_join_title
        )
        binding.btnTabIphone.visibility = if (role == RideRole.HOST) View.VISIBLE else View.GONE

        binding.btnBack.setOnClickListener {
            rideEndedByUser = true
            ride.end()
            finish()
        }

        binding.edtHostIp.setText(prefs.getString(KEY_LAST_IP, "192.168.43.1"))
        binding.edtPort.setText(prefs.getInt(KEY_LAST_PORT, 49152).toString())

        binding.toggleTransportMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTabQr -> {
                    isIphoneHostTab = false
                    selectedNetwork = NetworkKind.HOTSPOT
                }
                R.id.btnTabIphone -> {
                    isIphoneHostTab = true
                    selectedNetwork = NetworkKind.HOTSPOT
                }
                else -> {
                    isIphoneHostTab = false
                    selectedNetwork = NetworkKind.SHARED_WIFI
                }
            }
            hideError()
            renderRolePanels()
            updatePreFlightAndStartIfReady()
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
            if (!PermissionHelper.isLocationEnabledForHotspot(this)) {
                updatePreFlightAndStartIfReady()
                return@setOnClickListener
            }
            launchQrScanner()
        }

        binding.btnCopyOpenWifi.setOnClickListener {
            val qr = lastScannedQr ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RiderLink Wi-Fi Password", qr.passphrase)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            cm.setPrimaryClip(clip)
            Toast.makeText(this, "Password copied. Connect to ${qr.ssid} then return.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        binding.btnConnectAfterWifi.setOnClickListener {
            val qr = lastScannedQr ?: return@setOnClickListener
            hideError()
            ride.join(PairingInvitation.fromQrPayload(qr), JoinVia.CURRENT_WIFI)
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
            ride.join(
                PairingInvitation(wifi = null, hostIp = parsed.hostIp, port = parsed.udpPort, sessionToken = parsed.sessionToken),
                JoinVia.CURRENT_WIFI
            )
        }

        binding.btnRetry.setOnClickListener {
            hideError()
            if (role == RideRole.HOST) {
                updatePreFlightAndStartIfReady() // hosting the same kind after a failure retries it
            } else {
                ride.retry()
            }
        }

        renderRolePanels()
        savedInstanceState?.getString(STATE_LAST_SCANNED_QR)?.let { QrPayload.parse(it) }?.let { qr ->
            lastScannedQr = qr
            showWifiFallbackPanel(qr)
        }
        observeRide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastScannedQr?.encode()?.let { outState.putString(STATE_LAST_SCANNED_QR, it) }
    }

    override fun onResume() {
        super.onResume()
        updatePreFlightAndStartIfReady()
    }

    override fun onDestroy() {
        qrRenderJob?.cancel()
        if (!navigatedToRide && isFinishing && !rideEndedByUser) {
            ride.end()
        }
        super.onDestroy()
    }

    private fun joinViaScannedQr(qr: QrPayload) {
        // Wait briefly so PairActivity is focused again after ZXing closes; Android's Wi-Fi
        // chooser for WifiNetworkSpecifier only shows over a focused foreground Activity.
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            ride.join(PairingInvitation.fromQrPayload(qr), JoinVia.INVITATION_WIFI)
        }, SCANNER_CLOSE_DELAY_MS)
    }

    private fun renderRolePanels() {
        when (role) {
            RideRole.HOST -> {
                binding.panelHost.visibility = View.VISIBLE
                binding.toggleIphoneStep.visibility = if (isIphoneHostTab) View.VISIBLE else View.GONE
                binding.panelJoinQr.visibility = View.GONE
                binding.panelJoinManual.visibility = View.GONE
                renderHostQr()
            }
            RideRole.PEER -> {
                binding.panelHost.visibility = View.GONE
                binding.toggleIphoneStep.visibility = View.GONE
                binding.panelJoinQr.visibility =
                    if (selectedNetwork == NetworkKind.HOTSPOT) View.VISIBLE else View.GONE
                binding.panelJoinManual.visibility =
                    if (selectedNetwork == NetworkKind.SHARED_WIFI) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderHostQr() {
        val qr = lastHostInvitation?.toQrPayload() ?: return
        val qrContent: String
        if (isIphoneHostTab) {
            if (!iphoneStepSafari) {
                qrContent = qr.toStandardWifiQr()
                binding.txtHostStatus.setText(R.string.iphone_hint_wifi)
                binding.txtHostAddress.text = "Wi-Fi: ${qr.ssid}"
            } else {
                val url = qr.toIosWebUrl()
                qrContent = url
                binding.txtHostStatus.setText(R.string.iphone_hint_safari)
                binding.txtHostAddress.text = url
            }
        } else {
            qrContent = qr.encode()
            binding.txtHostStatus.setText(R.string.host_waiting_scan)
            binding.txtHostAddress.text = "${qr.hostIp}:${qr.udpPort}"
        }
        binding.txtHostSessionCode.text =
            "${getString(R.string.host_code_label)}: ${SessionToken.format(qr.sessionToken)}"
        binding.txtHostHotspotNote.visibility =
            if (selectedNetwork == NetworkKind.HOTSPOT) View.VISIBLE else View.GONE

        val cached = qrBitmapCache[qrContent]
        if (cached != null) {
            qrRenderJob?.cancel()
            binding.imgQrCode.setImageBitmap(cached)
            return
        }

        qrRenderJob?.cancel()
        qrRenderJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                QrBitmapGenerator.generate(qrContent)
            }
            qrBitmapCache[qrContent] = bmp
            binding.imgQrCode.setImageBitmap(bmp)
        }
    }

    private fun updatePreFlightAndStartIfReady() {
        val missing = PermissionHelper.missingPermissions(
            this,
            includeCamera = role == RideRole.PEER && selectedNetwork == NetworkKind.HOTSPOT
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

        if (selectedNetwork == NetworkKind.HOTSPOT &&
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

        if (role == RideRole.HOST) {
            // The Ride ignores this if it is already hosting on this network, and retries if that failed.
            ride.host(selectedNetwork)
        }
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

    private fun observeRide() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ride.state.collect { render(it) }
            }
        }
    }

    private fun render(state: RideState) {
        when (val phase = state.phase) {
            RidePhase.Idle -> Unit
            RidePhase.FormingNetwork -> binding.txtHostStatus.setText(R.string.host_starting_hotspot)
            is RidePhase.JoiningNetwork -> binding.txtJoinStatus.setText(
                if (phase.attempt > 1) R.string.join_retrying_wifi else R.string.join_connecting_wifi
            )
            RidePhase.WaitingForPeers -> {
                if (state.invitation != lastHostInvitation) {
                    lastHostInvitation = state.invitation
                    renderHostQr()
                }
            }
            RidePhase.Linking -> binding.txtJoinStatus.setText(R.string.join_connecting_udp)
            RidePhase.Riding -> {
                if (!navigatedToRide) {
                    navigatedToRide = true
                    startActivity(Intent(this, RideActivity::class.java))
                    finish()
                }
            }
            RidePhase.Ending -> Unit
            is RidePhase.NetworkLost -> showError(phase.message)
            is RidePhase.Failed -> showError(phase.message)
        }
    }

    private fun showWifiFallbackPanel(qr: QrPayload) {
        binding.panelJoinWifiFallback.visibility = View.VISIBLE
        binding.txtFallbackWifiCreds.text = "Wi-Fi: ${qr.ssid}\nPassword: ${qr.passphrase}"
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
        private const val STATE_LAST_SCANNED_QR = "state_last_scanned_qr"
        private const val SCANNER_CLOSE_DELAY_MS = 350L
    }
}
