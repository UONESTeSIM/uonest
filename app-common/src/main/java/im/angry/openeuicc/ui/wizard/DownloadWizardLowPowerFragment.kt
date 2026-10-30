package im.angry.openeuicc.ui.wizard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.DownloadPowerNoticeView
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog

class DownloadWizardLowPowerFragment : DownloadWizardActivity.DownloadWizardStepFragment() {
    companion object {
        /** Battery level threshold (in percentage) below which the battery is considered low. */
        private const val BATTERY_LEVEL_THRESHOLD = 20

        fun isBatteryLow(context: Context): Boolean = readPowerState(context).let {
            it.isLow || it.powerSave
        }

        private fun readPowerState(context: Context): PowerState {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percentage = if (scale > 0 && level in 0..scale)
                (level.toLong() * 100 / scale).toInt() else null
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            return PowerState(percentage,
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        }
    }

    private data class PowerState(val percentage: Int?, val charging: Boolean, val powerSave: Boolean) {
        val isLow: Boolean get() = percentage != null && percentage <= BATTERY_LEVEL_THRESHOLD
    }

    private var powerDialog: ProfileEditorDialog? = null
    private var powerNotice: DownloadPowerNoticeView? = null
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updatePowerNotice()
    }

    override val hasNext: Boolean
        get() = true

    override val hasPrev: Boolean
        get() = true

    override fun createNextFragment() = (requireActivity() as DownloadWizardActivity).createEntryFragment()

    override fun createPrevFragment() = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideProgressBar()
    }

    override fun onStart() {
        super.onStart()
        if (powerDialog == null) {
            val activity = requireActivity() as DownloadWizardActivity
            val ui = MinimalUi(requireContext())
            val dialog = ProfileEditorDialog(requireContext())
            val continueLabel = if (state.confirmationCodeRequired && state.confirmationCode.isNullOrBlank())
                R.string.download_low_power_continue else R.string.download_low_power_download
            val content = DownloadPowerNoticeView(requireContext(), continueLabel,
                onContinue = {
                    dialog.setCancelable(false)
                    dialog.setCanceledOnTouchOutside(false)
                    activity.continueAfterPowerNotice()
                },
                onCancel = { dialog.cancel() })
            powerNotice = content
            dialog.setContentView(ui.scroll(content))
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnCancelListener { activity.finish() }
            powerDialog = dialog
            dialog.show()
        }
        ContextCompat.registerReceiver(requireContext(), powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        updatePowerNotice()
    }

    private fun updatePowerNotice() {
        val notice = powerNotice ?: return
        val power = readPowerState(requireContext())
        val title = when {
            power.isLow -> R.string.download_wizard_low_power
            power.powerSave -> R.string.download_low_power_saver_title
            else -> R.string.download_low_power_status_title
        }
        val summary = power.percentage?.let {
            getString(if (power.charging) R.string.download_low_power_charging_level
                else R.string.download_low_power_level, it)
        } ?: if (power.charging) getString(R.string.download_low_power_charging) else null
        val message = when {
            power.isLow && !power.charging -> R.string.download_wizard_low_power_content
            power.isLow || (power.charging && !power.powerSave) -> R.string.download_low_power_charging_content
            power.powerSave -> R.string.download_low_power_saver_content
            else -> R.string.download_low_power_ready_content
        }
        notice.showNotice(title, summary, message)
    }

    override fun onStop() {
        requireContext().unregisterReceiver(powerReceiver)
        super.onStop()
    }

    override fun onDestroyView() {
        powerDialog?.setOnCancelListener(null)
        powerDialog?.dismiss()
        powerDialog = null
        powerNotice = null
        super.onDestroyView()
    }
}
