package com.riderlink.intercom.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riderlink.intercom.R
import com.riderlink.intercom.databinding.ActivityRideBinding
import com.riderlink.intercom.network.LinkState
import com.riderlink.intercom.network.TransportStats
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.AudioRouteType
import com.riderlink.intercom.service.IntercomForegroundService
import com.riderlink.intercom.service.TalkControlState
import com.riderlink.intercom.service.TalkMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRideBinding
    private lateinit var diagnosticsDrawer: LiveDiagnosticsDrawer

    private var service: IntercomForegroundService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as IntercomForegroundService.LocalBinder).getService()
            service = s
            bound = true
            s.refreshBatteryStatus()
            observeService(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diagnosticsDrawer = LiveDiagnosticsDrawer(this, binding.diagnosticsDrawer)

        binding.btnCockpitCenter.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.talkControlState.value.mode == TalkMode.HANDS_FREE) {
                s.toggleMute()
            }
        }

        binding.btnCockpitCenter.setOnTouchListener { _, event ->
            val s = service ?: return@setOnTouchListener false
            if (s.talkControlState.value.mode != TalkMode.PUSH_TO_TALK) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    s.setPttPressed(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    s.setPttPressed(false)
                    true
                }
                else -> false
            }
        }

        binding.toggleTalkMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeHandsFree) {
                TalkMode.HANDS_FREE
            } else {
                TalkMode.PUSH_TO_TALK
            }
            service?.setTalkMode(mode)
        }

        binding.seekWindGate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.txtWindGateValue.text = formatWindGate(progress)
                if (fromUser) {
                    service?.setMicGateThreshold(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnToggleDiagnostics.setOnClickListener {
            val root = binding.diagnosticsDrawer.root
            root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnEndRide.setOnClickListener {
            service?.stopSession()
            startActivity(Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        bindService(
            Intent(this, IntercomForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        service?.refreshBatteryStatus()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (service?.handleHardwareKeyDown(keyCode, event?.repeatCount ?: 0) == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (service?.handleHardwareKeyUp(keyCode) == true) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun observeService(s: IntercomForegroundService) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    s.transportStats,
                    s.diagnostics,
                    s.audioRouteState,
                    s.talkControlState
                ) { stats, diag, route, talk ->
                    Quad(stats, diag, route, talk)
                }.collect { (stats, diag, route, talk) ->
                    renderCockpit(stats, diag, route, talk)
                    diagnosticsDrawer.render(stats, diag, route, s.activeTransportMode)
                }
            }
        }
    }

    private fun renderCockpit(
        stats: TransportStats,
        diag: IntercomForegroundService.ServiceDiagnostics,
        route: AudioRouteState,
        talk: TalkControlState
    ) {
        // Link pill
        when (stats.linkState) {
            LinkState.CONNECTED -> {
                binding.txtPillLink.text = "Connected ${stats.rttMs} ms"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.live_emerald))
            }
            LinkState.RECONNECTING -> {
                binding.txtPillLink.text = "Reconnecting"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.signal_amber))
            }
            LinkState.WAITING_FOR_PEER -> {
                binding.txtPillLink.text = "Waiting"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.ink_slate))
            }
            else -> {
                binding.txtPillLink.text = "Offline"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.cutoff_crimson))
            }
        }

        // Audio pill
        binding.txtPillAudio.text = when (route.routeType) {
            AudioRouteType.BLUETOOTH_HEADSET -> "Bluetooth"
            AudioRouteType.WIRED_HEADSET -> "Wired"
            AudioRouteType.PHONE_SPEAKER -> "Speaker"
        }
        binding.txtPillAudio.setTextColor(
            ContextCompat.getColor(
                this,
                if (route.isSpeakerFallback) R.color.signal_amber else R.color.ink_white
            )
        )

        // Mode pill
        binding.txtPillMode.setText(
            if (talk.mode == TalkMode.HANDS_FREE) R.string.mode_hands_free else R.string.mode_ptt
        )

        // Alert strip priority: local call > peer call > reconnecting > speaker fallback
        val alertText = when {
            talk.isLocalCallPaused -> getString(R.string.warn_local_on_call)
            talk.isPeerOnCall -> getString(R.string.warn_peer_on_call)
            stats.linkState == LinkState.RECONNECTING -> getString(R.string.warn_reconnecting)
            route.isSpeakerFallback -> getString(R.string.warn_speaker_fallback)
            else -> null
        }
        if (alertText != null) {
            binding.txtRideAlert.visibility = View.VISIBLE
            binding.txtRideAlert.text = alertText
        } else {
            binding.txtRideAlert.visibility = View.GONE
        }

        // Center cockpit switch + wind gate visibility
        if (talk.mode == TalkMode.HANDS_FREE) {
            binding.panelWindGate.visibility = View.VISIBLE
            if (talk.isMuted || diag.isMuted) {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_muted)
                binding.txtCenterPrimary.setText(R.string.mic_muted)
                binding.txtCenterSub.setText(R.string.mic_muted_sub)
            } else {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_live)
                binding.txtCenterPrimary.setText(R.string.mic_live)
                binding.txtCenterSub.setText(R.string.mic_live_sub)
            }
        } else {
            binding.panelWindGate.visibility = View.GONE
            if (talk.isPttTransmitting) {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_ptt_active)
                binding.txtCenterPrimary.setText(R.string.ptt_active)
                binding.txtCenterSub.setText(R.string.ptt_active_sub)
            } else {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_ptt_idle)
                binding.txtCenterPrimary.setText(R.string.ptt_idle)
                binding.txtCenterSub.setText(R.string.ptt_idle_sub)
            }
        }
    }

    private fun formatWindGate(rms: Int): String = when {
        rms <= 0 -> "Off"
        rms < 450 -> "Low ($rms)"
        rms < 1000 -> "Medium ($rms)"
        else -> "High ($rms)"
    }

    private data class Quad(
        val stats: TransportStats,
        val diag: IntercomForegroundService.ServiceDiagnostics,
        val route: AudioRouteState,
        val talk: TalkControlState
    )
}
