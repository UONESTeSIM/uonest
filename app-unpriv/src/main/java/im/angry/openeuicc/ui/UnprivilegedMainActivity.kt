package im.angry.openeuicc.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.lifecycle.lifecycleScope
import im.angry.easyeuicc.R
import im.angry.openeuicc.common.R as CommonR
import im.angry.openeuicc.util.*
import im.angry.openeuicc.ui.minimal.MinimalToolbar
import im.angry.openeuicc.ui.minimal.showUonestWordmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UnprivilegedMainActivity : MainActivity(), UnprivilegedEuiccContextMarker {
    private val stk by lazy {
        SIMToolkit(this)
    }

    private var managerReady = false
    private var startupLaunched = false
    private var resolvingStartup = false
    private val startup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        startupLaunched = false
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            if (managerReady) super.onInit()
        } else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        startupLaunched = savedInstanceState?.getBoolean("startupLaunched") ?: false
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        findViewById<MinimalToolbar>(CommonR.id.toolbar).showUonestWordmark()
    }

    override fun onInit() {
        managerReady = true
        if (startupLaunched || resolvingStartup) return
        resolvingStartup = true
        lifecycleScope.launch {
            try {
                if (preferenceRepository.quickCompatibilityPassedFlow.first()) super.onInit()
                else launchCompatibilityCheck()
            } finally {
                resolvingStartup = false
            }
        }
    }

    private fun launchCompatibilityCheck(manual: Boolean = false) {
        if (startupLaunched) return
        startupLaunched = true
        lifecycleScope.launch {
            preferenceRepository.quickCompatibilityPassedFlow.updatePreference(false)
            startup.launch(Intent(this@UnprivilegedMainActivity, QuickCompatibilityActivity::class.java)
                .putExtra(QuickCompatibilityActivity.EXTRA_MANUAL_CHECK, manual))
        }
    }

    override fun addMoreActions(
        ui: im.angry.openeuicc.ui.minimal.MinimalUi,
        page: android.widget.LinearLayout,
        dismiss: () -> Unit
    ) {
        ui.row(ui.card(page, small = true), getString(R.string.startup_recheck), getString(R.string.startup_recheck_detail),
            im.angry.openeuicc.common.R.drawable.ic_minimal_refresh) {
            dismiss()
            launchCompatibilityCheck(manual = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("startupLaunched", startupLaunched)
    }

    override fun addMoreSettingsActions(
        ui: im.angry.openeuicc.ui.minimal.MinimalUi,
        page: android.widget.LinearLayout
    ) {
        val entries = ui.column()
        page.addView(entries, android.widget.LinearLayout.LayoutParams(-1, -2))
        val job = lifecycleScope.launch {
            preferenceRepository.developerOptionsEnabledFlow.collect { developer ->
                entries.removeAllViews()
                fun entry(title: Int, detail: Int, icon: Int, screen: String) {
                    ui.row(ui.card(entries, small = true), getString(title), getString(detail), icon) {
                        startActivity(Intent(this@UnprivilegedMainActivity, SettingsActivity::class.java)
                            .putExtra(SettingsActivity.EXTRA_SCREEN, screen))
                    }
                }
                entry(CommonR.string.minimal_notification_settings, CommonR.string.pref_notifications_desc, CommonR.drawable.ic_minimal_bell, "settings")
                entry(CommonR.string.pref_advanced, CommonR.string.minimal_advanced_detail, CommonR.drawable.ic_minimal_sliders, "advanced")
                entry(CommonR.string.minimal_about, CommonR.string.minimal_about_detail, CommonR.drawable.ic_minimal_info, "about")
                if (developer) entry(CommonR.string.pref_developer, CommonR.string.minimal_developer_detail, CommonR.drawable.ic_minimal_code, "developer")
            }
        }
        entries.doOnAttach { it.doOnDetach { job.cancel() } }
    }

    override fun buildShortcuts() = buildList {
        addAll(super.buildShortcuts())
        val context = this@UnprivilegedMainActivity
        fun addShortcut(intent: Intent, index: Int, label: String) {
            val id = "stk_slot_$index"
            val icon = packageManager.getActivityIcon(intent)
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithBitmap(icon.toBitmap()))
                .setIntent(intent)
                .build()
            add(shortcut)
        }
        for ((index, intent) in stk.intents.withIndex()) {
            if (stk.isSelection(intent ?: continue)) {
                val label = getString(R.string.shortcut_sim_toolkit)
                addShortcut(intent, index, label)
                break
            }
            val label = getString(R.string.shortcut_sim_toolkit_with_slot, index)
            addShortcut(intent, index, label)
        }
    }
}
