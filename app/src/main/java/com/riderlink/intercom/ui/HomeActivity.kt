package com.riderlink.intercom.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.RiderLinkApp
import com.riderlink.intercom.databinding.ActivityHomeBinding
import com.riderlink.intercom.ride.RideRole
import com.riderlink.intercom.oem.BatteryOptimizationHelper

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val profile by lazy { RiderLinkApp.from(this).profile }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()

        binding.btnHostPlate.setOnClickListener {
            withRiderName { openPairScreen(RideRole.HOST) }
        }
        binding.btnJoinPlate.setOnClickListener {
            withRiderName { openPairScreen(RideRole.PEER) }
        }
        binding.txtRiderName.setOnClickListener { askRiderName(then = null) }
        binding.barBatteryReadiness.setOnClickListener {
            showBatteryChecklistDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryBanner()
        renderRiderName()
    }

    private fun renderRiderName() {
        binding.txtRiderName.text = getString(R.string.rider_name_label, profile.displayName)
    }

    /** Asks for a Rider name once, the first time a rider picks a role; then carries on. */
    private fun withRiderName(then: () -> Unit) {
        if (profile.hasChosenName) then() else askRiderName(then)
    }

    private fun askRiderName(then: (() -> Unit)?) {
        val input = EditText(this).apply {
            setText(profile.displayName)
            setSelection(text.length)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_CHARS))
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rider_name_dialog_title)
            .setMessage(R.string.rider_name_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.rider_name_save) { _, _ ->
                profile.displayName = input.text.toString().trim().ifBlank { profile.displayName }
                renderRiderName()
                then?.invoke()
            }
            .setNegativeButton(R.string.close_label, null)
            .show()
    }

    private fun openPairScreen(role: RideRole) {
        val intent = Intent(this, PairActivity::class.java).apply {
            putExtra(PairActivity.EXTRA_ROLE, role.name)
        }
        startActivity(intent)
    }

    private fun refreshBatteryBanner() {
        val exempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        if (exempt) {
            binding.dotBatteryStatus.setBackgroundColor(
                ContextCompat.getColor(this, R.color.live_emerald)
            )
            binding.txtBatteryStatus.setText(R.string.battery_ready)
            binding.txtBatteryAction.text = ""
        } else {
            binding.dotBatteryStatus.setBackgroundColor(
                ContextCompat.getColor(this, R.color.signal_amber)
            )
            binding.txtBatteryStatus.setText(R.string.battery_setup_needed)
            binding.txtBatteryAction.setText(R.string.battery_allow_unrestricted)
        }
    }

    private fun showBatteryChecklistDialog() {
        val advice = BatteryOptimizationHelper.getAdvice(this)
        val message = buildString {
            append(advice.manufacturerLabel)
            append("\n\n")
            advice.steps.forEachIndexed { index, step ->
                append("${index + 1}. $step\n")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.battery_allow_unrestricted) { _, _ ->
                runCatching {
                    startActivity(BatteryOptimizationHelper.createRequestExemptionIntent(this))
                }.onFailure {
                    startActivity(BatteryOptimizationHelper.createAppSettingsIntent(this))
                }
            }
            .setNeutralButton(R.string.battery_open_app_info) { _, _ ->
                startActivity(BatteryOptimizationHelper.createAppSettingsIntent(this))
            }
            .setNegativeButton(R.string.close_label, null)
            .show()
    }

    private companion object {
        const val MAX_NAME_CHARS = 24
    }
}
