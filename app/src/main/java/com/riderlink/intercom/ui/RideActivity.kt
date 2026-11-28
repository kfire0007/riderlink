package com.riderlink.intercom.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riderlink.intercom.R
import com.riderlink.intercom.RiderLinkApp
import com.riderlink.intercom.databinding.ActivityRideBinding
import com.riderlink.intercom.music.MusicLevel
import com.riderlink.intercom.music.MusicState
import com.riderlink.intercom.oem.BatteryOptimizationHelper
import com.riderlink.intercom.ride.LinkKind
import com.riderlink.intercom.ride.LinkStatus
import com.riderlink.intercom.ride.RideOutcome
import com.riderlink.intercom.ride.RidePhase
import com.riderlink.intercom.ride.RideRole
import com.riderlink.intercom.ride.RideState
import com.riderlink.intercom.ride.RideStats
import com.riderlink.intercom.ride.RosterRider
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
    private var shownRoster: List<RosterRider>? = null

    /** The Host picks songs with the system file picker; read access is kept for the queue. */
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        for (uri in uris) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        app.music.add(uris)
    }

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
            val ride = app.ride.state.value
            if (ride.phase is RidePhase.NetworkLost || ride.phase is RidePhase.Failed) {
                if (ride.role == RideRole.HOST) {
                    // A Host retry issues a new invitation, which only the pairing screen can show.
                    goToHostPairing()
                } else {
                    app.ride.retry()
                }
            }
        }

        setUpMusicCard()

        binding.btnEndRide.setOnClickListener {
            app.ride.end()
            goHome()
        }

        observeRide()
    }

    private fun setUpMusicCard() {
        binding.btnMusicAdd.setOnClickListener {
            runCatching { pickMusic.launch(arrayOf("audio/*")) }
                .onFailure { Toast.makeText(this, R.string.music_pick_failed, Toast.LENGTH_LONG).show() }
        }
        binding.btnMusicPlay.setOnClickListener { app.music.togglePlay() }
        binding.btnMusicNext.setOnClickListener { app.music.next() }
        binding.btnMusicPrevious.setOnClickListener { app.music.previous() }
        binding.toggleMusicLevel.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val level = LEVEL_BUTTONS.entries.firstOrNull { it.value == checkedId }?.key ?: return@addOnButtonCheckedListener
            if (app.ride.musicLevel.value != level) app.ride.setMusicLevel(level)
        }
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
                launch {
                    combine(app.ride.state, app.music.state, app.ride.musicLevel) { r, p, l -> renderMusic(r, p, l) }.collect {}
                }
                combine(
                    app.ride.state,
                    app.ride.stats,
                    app.voice.audioRouteState,
                    app.voice.talkControlState
                ) { ride, stats, route, talk -> Snapshot(ride, stats, route, talk) }
                    .collect { s ->
                        if (s.ride.isIdle) {
                            when (s.ride.lastOutcome) {
                                RideOutcome.EndedByHost ->
                                    Toast.makeText(this@RideActivity, "The Host ended the ride.", Toast.LENGTH_LONG).show()
                                RideOutcome.RemovedByHost ->
                                    Toast.makeText(this@RideActivity, R.string.toast_removed_by_host, Toast.LENGTH_LONG).show()
                                else -> Unit
                            }
                            goHome()
                            return@collect
                        }
                        if (s.ride.role == RideRole.HOST && s.ride.phase == RidePhase.WaitingForPeers) {
                            // Every Peer left: show the invitation again so they can rejoin.
                            goToHostPairing()
                            return@collect
                        }
                        renderCockpit(s.ride, s.stats, s.route, s.talk)
                        renderRoster(s.ride)
                        diagnosticsDrawer.render(
                            s.ride,
                            s.stats,
                            s.route,
                            LiveDiagnosticsDrawer.DeviceStatus(
                                wakeLockHeld = app.locks.isWakeLockHeld,
                                wifiLockHeld = app.locks.isWifiLockHeld,
                                batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@RideActivity),
                                jitterDepthFrames = app.voice.jitterDepthFrames,
                                mixTickMicros = if (s.ride.role == RideRole.HOST) app.voice.mixTickMicros else 0L
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

    /** The pairing screen picks up the running ride's network and retries it if it failed. */
    private fun goToHostPairing() {
        if (leaving) return
        leaving = true
        startActivity(Intent(this, PairActivity::class.java).apply {
            putExtra(PairActivity.EXTRA_ROLE, RideRole.HOST.name)
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
            ride.youAreHostMuted -> getString(R.string.warn_you_are_host_muted)
            phase is RidePhase.NetworkLost -> "${phase.message} Tap here to try again."
            phase is RidePhase.Failed -> "${phase.message} Tap here to try again."
            ride.links.isNotEmpty() && !ride.anyLinked -> getString(R.string.warn_reconnecting)
            route.isSpeakerFallback -> getString(R.string.warn_speaker_fallback)
            ride.role == RideRole.HOST && ride.links.count { it.kind == LinkKind.ANDROID } > CROWDED_AFTER_PEERS ->
                getString(R.string.warn_group_crowded, ride.links.count { it.kind == LinkKind.ANDROID } + 1)
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

    /** The group roster: one row per rider; on the Host, tapping a rider offers Mute and Remove. */
    private fun renderRoster(ride: RideState) {
        val roster = ride.roster
        if (roster == shownRoster) return
        shownRoster = roster
        binding.panelRoster.visibility = if (roster.size > 1) View.VISIBLE else View.GONE
        val list = binding.rosterList
        list.removeAllViews()
        for (rider in roster) {
            val badges = buildList {
                if (rider.speaking) add(getString(R.string.roster_speaking))
                if (rider.reconnecting) add(getString(R.string.roster_reconnecting))
                if (rider.onCall) add(getString(R.string.roster_on_call))
                if (rider.hostMuted) add(getString(R.string.roster_muted))
            }
            val name = when {
                rider.isYou -> getString(R.string.roster_you, rider.name)
                rider.isHost -> getString(R.string.roster_host, rider.name)
                else -> rider.name
            }
            val color = when {
                rider.hostMuted -> R.color.ink_slate
                rider.reconnecting -> R.color.signal_amber
                rider.speaking -> R.color.live_emerald
                else -> R.color.ink_white
            }
            val row = TextView(this).apply {
                text = if (badges.isEmpty()) "● $name" else "● $name · ${badges.joinToString(" · ")}"
                setTextColor(ContextCompat.getColor(context, color))
                textSize = 16f
                minHeight = (40 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            if (ride.role == RideRole.HOST && !rider.isYou) {
                row.setOnClickListener { offerHostActions(rider) }
            }
            list.addView(row)
        }
    }

    /**
     * The music card. The Host always sees it (with the player); a Peer sees it once the Host plays
     * something, and only picks how loud it hears the music.
     */
    private fun renderMusic(ride: RideState, player: MusicState, level: MusicLevel) {
        val hosting = ride.role == RideRole.HOST
        val show = !ride.isIdle && (hosting || ride.nowPlaying != null)
        binding.panelMusic.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        binding.rowMusicTransport.visibility = if (hosting) View.VISIBLE else View.GONE
        val track = player.current
        binding.txtNowPlaying.text = when {
            !hosting -> ride.nowPlaying
            track == null -> getString(R.string.music_empty_host)
            else -> {
                val title = if (player.queue.size > 1) {
                    getString(R.string.music_track_of, track.title, player.index + 1, player.queue.size)
                } else {
                    track.title
                }
                if (player.playing) title else getString(R.string.music_paused, title)
            }
        }
        if (hosting) {
            val hasQueue = player.queue.isNotEmpty()
            binding.btnMusicPlay.isEnabled = hasQueue
            binding.btnMusicNext.isEnabled = hasQueue
            binding.btnMusicPrevious.isEnabled = hasQueue
            binding.btnMusicPlay.setIconResource(if (player.playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
            binding.btnMusicPlay.contentDescription = getString(if (player.playing) R.string.music_pause else R.string.music_play)
        }
        val wanted = LEVEL_BUTTONS.getValue(level)
        if (binding.toggleMusicLevel.checkedButtonId != wanted) binding.toggleMusicLevel.check(wanted)
    }

    private fun offerHostActions(rider: RosterRider) {
        val muteLabel = getString(
            if (rider.hostMuted) R.string.roster_action_unmute else R.string.roster_action_mute,
            rider.name
        )
        val removeLabel = getString(R.string.roster_action_remove, rider.name)
        AlertDialog.Builder(this)
            .setTitle(rider.name)
            .setItems(arrayOf(muteLabel, removeLabel)) { _, which ->
                when (which) {
                    0 -> app.ride.setHostMuted(rider.riderId, !rider.hostMuted)
                    1 -> app.ride.remove(rider.riderId)
                }
            }
            .setNegativeButton(R.string.close_label, null)
            .show()
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

    private companion object {
        /** Above this many Android Peers the Host suggests the Wi-Fi may get crowded (spec: 6 phones is the sweet spot). */
        const val CROWDED_AFTER_PEERS = 5

        val LEVEL_BUTTONS = mapOf(
            MusicLevel.OFF to R.id.btnMusicOff,
            MusicLevel.LOW to R.id.btnMusicLow,
            MusicLevel.MEDIUM to R.id.btnMusicMedium,
            MusicLevel.FULL to R.id.btnMusicFull
        )
    }
}
