package im.angry.openeuicc.ui.wizard

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.ui.minimal.HomeGlass
import im.angry.openeuicc.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Desktop shortcuts and activation links share the add page and destination selector. */
class DownloadTargetActivity : AddEsimActivity(), OpenEuiccContextMarker {
    private data class Target(val slot: Int, val seId: EuiccChannel.SecureElementId,
                              val title: String, val eid: String) {
        val key get() = encodeSyntheticSlotId(slot, seId)
    }

    private val linkCode by lazy {
        intent.data?.let { uri ->
            runCatching {
                require(uri.scheme.equals("lpa", ignoreCase = true))
                parseActivationInput("LPA:${uri.schemeSpecificPart}")
            }.getOrNull()
        }
    }
    override val activationCodeOnly get() = linkCode != null

    private var service: EuiccChannelManagerService? = null
    private var targets = emptyList<Target>()
    private var selectedKey = -1
    private var selectedEid: String? = null
    private var selectionInitialized = false
    private var loading = true
    private var submitting = false
    private val preferences by lazy { getSharedPreferences("download_target", MODE_PRIVATE) }
    private val selected get() = targets.firstOrNull { it.key == selectedKey && it.eid == selectedEid }
    override val canAcquireCode get() = !loading && !submitting && selected != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as EuiccChannelManagerService.LocalBinder).service
            loadTargets()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            targets = emptyList()
            loading = false
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        selectedKey = savedInstanceState?.getInt("targetKey", -1) ?: -1
        selectedEid = savedInstanceState?.getString("targetEid")
        selectionInitialized = savedInstanceState?.getBoolean("targetInitialized") ?: false
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.data != null) {
            val code = linkCode
            if (code != null) retainCode(code)
            else Toast.makeText(this, R.string.add_esim_invalid, Toast.LENGTH_LONG).show()
        }
        bindService(Intent(this, EuiccChannelManagerService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (service != null && !loading && !submitting) loadTargets()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("targetKey", selectedKey)
        outState.putString("targetEid", selectedEid)
        outState.putBoolean("targetInitialized", selectionInitialized)
        super.onSaveInstanceState(outState)
    }

    private fun loadTargets() {
        val managerService = service ?: return
        loading = true
        render()
        lifecycleScope.launch {
            try {
                managerService.waitForForegroundTask()
                targets = withContext(Dispatchers.IO) {
                    val manager = managerService.euiccChannelManager
                    val found = mutableListOf<Target>()
                    manager.flowAllOpenEuiccPorts().collect { (slot, port) ->
                        try {
                            manager.flowEuiccSecureElements(slot, port).collect { se ->
                                try {
                                    manager.withEuiccChannel(slot, port, se) { channel ->
                                        if (channel.valid) {
                                            val eid = channel.lpa.eID
                                            if (eid.isNotBlank()) {
                                                val title = if (channel.logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID) {
                                                    if (channel.hasMultipleSE) getString(R.string.usb_tab_title_se, se.id)
                                                    else getString(R.string.usb_tab_title)
                                                } else if (channel.hasMultipleSE) {
                                                    appContainer.customizableTextProvider.formatNonUsbChannelNameWithSeId(channel.logicalSlotId, se)
                                                } else appContainer.customizableTextProvider.formatNonUsbChannelName(channel.logicalSlotId)
                                                found += Target(channel.logicalSlotId, se, title, eid)
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { /* Other slots remain selectable. */ }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                    found.distinctBy { it.key }.sortedWith(compareBy({ it.slot }, { it.seId.id }))
                }
                if (!selectionInitialized) {
                    val initial = targets.firstOrNull { it.key == preferences.getInt("lastTarget", -1) }
                        ?: targets.firstOrNull()
                    selectedKey = initial?.key ?: -1
                    selectedEid = initial?.eid
                    selectionInitialized = true
                } else if (selected == null) {
                    // Never silently switch an in-progress add flow to another card.
                    selectedKey = -1
                    selectedEid = null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { targets = emptyList() }
            finally {
                loading = false
                render()
            }
        }
    }

    override fun targetSelector(parent: LinearLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            background = HomeGlass.surface(this, whiteAlpha = 242, cornerRadiusDp = 25f,
                strokeWidthDp = .4f, strokeColor = ui.color(R.color.minimal_line))
        }
        parent.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val colors = androidx.appcompat.content.res.AppCompatResources.getColorStateList(this, R.color.minimal_tab_content)
        val slots = (targets.map { it.slot } + (0 until telephonyManager.activeModemCountCompat)).distinct().sorted()
        slots.forEach { slot ->
            val options: List<Target?> = targets.filter { it.slot == slot }.ifEmpty { listOf(null) }
            options.forEach { target ->
                val title = target?.title ?: appContainer.customizableTextProvider.formatNonUsbChannelName(slot)
                val option = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.minimal_tab)
                    isSelected = target != null && target == selected
                    isEnabled = target != null && !loading && !submitting
                    alpha = if (isEnabled) 1f else .45f
                    contentDescription = if (target == null) "$title，${getString(R.string.download_target_unavailable)}" else title
                    addView(ImageView(this@DownloadTargetActivity).apply {
                        setImageResource(if (slot == EuiccChannelManager.USB_CHANNEL_ID)
                            R.drawable.ic_minimal_usb_reader else R.drawable.ic_minimal_sim)
                        isDuplicateParentStateEnabled = true
                        imageTintList = colors
                    }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).apply { marginEnd = ui.dp(7) })
                    addView(ui.text(title, 14f).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        isDuplicateParentStateEnabled = true
                        setTextColor(colors)
                        maxLines = 1
                    })
                    if (target != null) setOnClickListener {
                        selectedKey = target.key
                        selectedEid = target.eid
                        preferences.edit().putInt("lastTarget", target.key).apply()
                        render()
                    }
                }
                bar.addView(option, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
            }
        }
        ui.gap(parent, 12)
        if (loading || selected == null) {
            parent.addView(ui.text(getString(if (loading) R.string.download_target_loading
                else if (targets.isEmpty()) R.string.download_target_none else R.string.download_target_choose), 13f, true))
            ui.gap(parent, 12)
        }
    }

    override fun continueWithCode(code: LPAString, directDownload: Boolean) {
        val target = selected
        val managerService = service
        retainCode(code)
        if (submitting) return
        if (target == null || managerService == null) {
            Toast.makeText(this, R.string.download_target_choose, Toast.LENGTH_LONG).show()
            return
        }
        submitting = true
        render()
        lifecycleScope.launch {
            try {
                managerService.waitForForegroundTask()
                managerService.euiccChannelManager.withEuiccChannel(target.slot, target.seId) { channel ->
                    check(channel.valid && channel.lpa.eID == target.eid)
                }
                preferences.edit().putInt("lastTarget", target.key).apply()
                intent.putExtras(AddEsimActivity.newIntent(this@DownloadTargetActivity, target.slot, target.seId).extras!!)
                super.continueWithCode(code, directDownload)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                selectedKey = -1
                selectedEid = null
                Toast.makeText(this@DownloadTargetActivity, R.string.download_wizard_slot_removed, Toast.LENGTH_LONG).show()
                loadTargets()
            } finally { submitting = false }
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}
