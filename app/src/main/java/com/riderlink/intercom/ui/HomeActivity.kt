package com.riderlink.intercom.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.riderlink.intercom.R
import com.riderlink.intercom.databinding.ActivityHomeBinding
import com.riderlink.intercom.network.Role
import com.riderlink.intercom.oem.BatteryOptimizationHelper

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHostPlate.setOnClickListener {
            openPairScreen(Role.HOST_RIDER)
        }
        binding.btnJoinPlate.setOnClickListener {
            openPairScreen(Role.JOIN_OBR)
        }
        binding.barBatteryReadiness.setOnClickListener {
            showBatteryChecklistDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryBanner()
    }

    private fun openPairScreen(role: Role) {
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
}
