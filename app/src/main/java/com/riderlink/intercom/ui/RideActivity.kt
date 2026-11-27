package com.riderlink.intercom.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riderlink.intercom.R
import com.riderlink.intercom.RiderLinkApp
import com.riderlink.intercom.databinding.ActivityRideBinding
import com.riderlink.intercom.oem.BatteryOptimizationHelper
import com.riderlink.intercom.ride.LinkStatus
import com.riderlink.intercom.ride.RideOutcome
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideState
import com.riderlink.intercom.ride.RideStats
import com.riderlink.intercom.service.AudioRouteState
import com.riderlink.intercom.service.AudioRouteType
import com.riderlink.intercom.service.TalkControlState
import com.riderlink.intercom.service.TalkMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRideBinding
    private lateinit var diagnosticsDrawer: LiveDiagnosticsDrawer
    private val app by lazy { RiderLinkApp.from(this) }
    private var leaving = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        diagnosticsDrawer = LiveDiagnosticsDrawer(this, binding.diagnosticsDrawer)
        val voice = app.voice

        binding.btnCockpitCenter.setOnClickListener {
            if (voice.talkControlState.value.mode == TalkMode.HANDS_FREE) {
                voice.toggleMute()
            }
        }

        binding.btnCockpitCenter.setOnTouchListener { _, event ->
            if (voice.talkControlState.value.mode != TalkMode.PUSH_TO_TALK) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    voice.setPttPressed(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    voice.setPttPressed(false)
                    true
                }
                else -> false
            }
        }

        binding.toggleTalkMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeHandsFree) TalkMode.HANDS_FREE else TalkMode.PUSH_TO_TALK
            if (voice.talkControlState.value.mode != mode) {
                voice.setTalkMode(mode)
            }
        }

        binding.seekWindGate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.txtWindGateValue.text = formatWindGate(progress)
                if (fromUser) {
                    voice.setMicGateThreshold(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnToggleDiagnostics.setOnClickListener {
            val root = binding.diagnosticsDrawer.root
            root.visibility = if (root.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.txtRideAlert.setOnClickListener {
            val phase = app.ride.state.value.phase
            if (phase is RidePhase.NetworkLost || phase is RidePhase.Failed) {
                app.ride.retry()
            }
        }

        binding.btnEndRide.setOnClickListener {
            app.ride.end()
            goHome()
        }

        observeRide()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (app.voice.handleHardwareKeyDown(keyCode, event?.repeatCount ?: 0)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (app.voice.handleHardwareKeyUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun observeRide() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    app.ride.state,
                    app.ride.stats,
                    app.voice.audioRouteState,
                    app.voice.talkControlState
                ) { ride, stats, route, talk -> Snapshot(ride, stats, route, talk) }
                    .collect { s ->
                        if (s.ride.isIdle) {
                            if (s.ride.lastOutcome == RideOutcome.EndedByHost) {
                                Toast.makeText(this@RideActivity, "The Host ended the ride.", Toast.LENGTH_LONG).show()
                            }
                            goHome()
                            return@collect
                        }
                        renderCockpit(s.ride, s.stats, s.route, s.talk)
                        diagnosticsDrawer.render(
                            s.ride,
                            s.stats,
                            s.route,
                            LiveDiagnosticsDrawer.DeviceStatus(
                                wakeLockHeld = app.locks.isWakeLockHeld,
                                wifiLockHeld = app.locks.isWifiLockHeld,
                                batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@RideActivity),
                                jitterDepthFrames = app.voice.jitterDepthFrames
                            )
                        )
                    }
            }
        }
    }

    private fun goHome() {
        if (leaving) return
        leaving = true
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun renderCockpit(ride: RideState, stats: RideStats, route: AudioRouteState, talk: TalkControlState) {
        // Link pill
        val phase = ride.phase
        when {
            ride.anyLinked -> {
                val rtt = stats.links.firstOrNull()?.stats?.rttMs ?: 0L
                binding.txtPillLink.text = "Connected $rtt ms"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.live_emerald))
            }
            ride.links.any { it.status == LinkStatus.RECONNECTING } -> {
                binding.txtPillLink.text = "Reconnecting"
                binding.txtPillLink.setTextColor(ContextCompat.getColor(this, R.color.signal_amber))
            }
            phase == RidePhase.WaitingForPeers || phase == RidePhase.Linking -> {
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
            ContextCompat.getColor(this, if (route.isSpeakerFallback) R.color.signal_amber else R.color.ink_white)
        )

        // Mode pill & toggle sync
        binding.txtPillMode.setText(
            if (talk.mode == TalkMode.HANDS_FREE) R.string.mode_hands_free else R.string.mode_ptt
        )
        val desiredToggleId = if (talk.mode == TalkMode.HANDS_FREE) R.id.btnModeHandsFree else R.id.btnModePtt
        if (binding.toggleTalkMode.checkedButtonId != desiredToggleId) {
            binding.toggleTalkMode.check(desiredToggleId)
        }
        if (binding.seekWindGate.progress != talk.micGateThresholdRms) {
            binding.seekWindGate.progress = talk.micGateThresholdRms
            binding.txtWindGateValue.text = formatWindGate(talk.micGateThresholdRms)
        }

        // Alert strip priority: local call > peer call > network gone > reconnecting > speaker fallback
        val alertText = when {
            talk.isLocalCallPaused -> getString(R.string.warn_local_on_call)
            talk.isPeerOnCall -> getString(R.string.warn_peer_on_call)
            phase is RidePhase.NetworkLost -> "${phase.message} (tap here)"
            phase is RidePhase.Failed -> "${phase.message} Tap here to try again."
            ride.links.isNotEmpty() && !ride.anyLinked -> getString(R.string.warn_reconnecting)
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
            if (talk.isMuted) {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_muted)
                binding.txtCenterPrimary.setText(R.string.mic_muted)
                binding.txtCenterPrimary.setTextColor(ContextCompat.getColor(this, R.color.ink_white))
                binding.txtCenterSub.setText(R.string.mic_muted_sub)
                binding.txtCenterSub.setTextColor(ContextCompat.getColor(this, R.color.ink_slate))
            } else {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_live)
                binding.txtCenterPrimary.setText(R.string.mic_live)
                binding.txtCenterPrimary.setTextColor(ContextCompat.getColor(this, R.color.ink_white))
                binding.txtCenterSub.setText(R.string.mic_live_sub)
                binding.txtCenterSub.setTextColor(ContextCompat.getColor(this, R.color.ink_slate))
            }
        } else {
            binding.panelWindGate.visibility = View.GONE
            if (talk.isPttTransmitting) {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_ptt_active)
                binding.txtCenterPrimary.setText(R.string.ptt_active)
                binding.txtCenterPrimary.setTextColor(ContextCompat.getColor(this, R.color.cockpit_black))
                binding.txtCenterSub.setText(R.string.ptt_active_sub)
                binding.txtCenterSub.setTextColor(ContextCompat.getColor(this, R.color.cockpit_black))
            } else {
                binding.btnCockpitCenter.setBackgroundResource(R.drawable.bg_cockpit_ring_ptt_idle)
                binding.txtCenterPrimary.setText(R.string.ptt_idle)
                binding.txtCenterPrimary.setTextColor(ContextCompat.getColor(this, R.color.ink_white))
                binding.txtCenterSub.setText(R.string.ptt_idle_sub)
                binding.txtCenterSub.setTextColor(ContextCompat.getColor(this, R.color.ink_slate))
            }
        }
    }

    private fun formatWindGate(rms: Int): String = when {
        rms <= 0 -> "Off"
        rms < 450 -> "Low ($rms)"
        rms < 1000 -> "Medium ($rms)"
        else -> "High ($rms)"
    }

    private data class Snapshot(
        val ride: RideState,
        val stats: RideStats,
        val route: AudioRouteState,
        val talk: TalkControlState
    )
}
