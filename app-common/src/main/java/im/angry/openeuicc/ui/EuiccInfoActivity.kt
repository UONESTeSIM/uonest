package im.angry.openeuicc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.impl.PKID_GSMA_LIVE_CI
import net.typeblog.lpac_jni.impl.PKID_GSMA_TEST_CI

// https://euicc-manual.osmocom.org/docs/pki/eum/accredited.json
// ref: <https://regex101.com/r/5FFz8u>
private val RE_SAS = Regex(
    """^[A-Z]{2}-[A-Z]{2}(?:-UP)?-\d{4}T?(?:-\d+)?T?$""",
    setOf(RegexOption.IGNORE_CASE),
)

class EuiccInfoActivity : BaseEuiccAccessActivity(), OpenEuiccContextMarker {
    companion object {
        private val YES_NO = Pair(R.string.euicc_info_yes, R.string.euicc_info_no)
    }

    private val ui by lazy { im.angry.openeuicc.ui.minimal.MinimalUi(this, rowTitleSize = 16f, rowDetailSize = 13f, glassCards = true) }
    private lateinit var page: android.widget.LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var logicalSlotId = -1
    private var seId = EuiccChannel.SecureElementId.DEFAULT
    private var busy = false
    private lateinit var refreshButton: androidx.appcompat.widget.AppCompatImageButton
    private lateinit var eraseButton: androidx.appcompat.widget.AppCompatImageButton
    private var eraseAction: (() -> Unit)? = null

    data class Item(val titleResId: Int, val content: String?, val copiedToastResId: Int? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.euicc_info)
        val toolbar = requireViewById<im.angry.openeuicc.ui.minimal.MinimalToolbar>(R.id.toolbar)
        val actions = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun action(title: Int, icon: Int, color: Int, click: () -> Unit) =
            androidx.appcompat.widget.AppCompatImageButton(this).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ui.color(color))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                ui.styleIconPress(this)
                contentDescription = getString(title)
                tooltipText = contentDescription
                setOnClickListener { if (!busy) click() }
                actions.addView(this, android.widget.LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            }
        refreshButton = action(R.string.minimal_refresh, R.drawable.ic_minimal_refresh, R.color.minimal_blue) { refresh() }
        eraseButton = action(R.string.minimal_erase, R.drawable.ic_minimal_trash, R.color.minimal_red) {
            if (supportFragmentManager.findFragmentByTag(EuiccMemoryResetFragment.TAG) == null) eraseAction?.invoke()
        }
        updateActions()
        toolbar.addView(actions, androidx.appcompat.widget.Toolbar.LayoutParams(-2, ui.dp(40),
            android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        page = ui.page()
        swipeRefresh = SwipeRefreshLayout(this).apply { addView(ui.scroll(page)) }
        ui.styleHomeRefresh(swipeRefresh)
        requireViewById<android.widget.FrameLayout>(R.id.settings_container).addView(swipeRefresh)
        logicalSlotId = intent.getIntExtra("logicalSlotId", 0)
        @Suppress("DEPRECATION")
        seId = intent.getParcelableExtra("seId") ?: EuiccChannel.SecureElementId.DEFAULT
        swipeRefresh.setOnRefreshListener { refresh() }
        supportFragmentManager.setFragmentResultListener("chipReset", this) { _, _ -> refresh() }
        setupRootViewSystemBarInsets(window.decorView.rootView,
            arrayOf({ insets ->
                activityToolbarInsetHandler(insets)
                // The last glyph ends about 13dp inside its 40dp target: 5 + 13 = the card's 18dp edge.
                val rtl = toolbar.layoutDirection == View.LAYOUT_DIRECTION_RTL
                toolbar.setPadding(if (rtl) ui.dp(5) + insets.left else toolbar.paddingLeft,
                    toolbar.paddingTop, if (rtl) toolbar.paddingRight else ui.dp(5) + insets.right,
                    toolbar.paddingBottom)
            }, ui.pageInsets(page)))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        else -> super.onOptionsItemSelected(item)
    }
    override fun onInit() { refresh() }

    private fun updateActions() {
        refreshButton.isEnabled = !busy
        eraseButton.isEnabled = !busy && eraseAction != null
        refreshButton.alpha = if (refreshButton.isEnabled) 1f else 0.35f
        eraseButton.alpha = if (eraseButton.isEnabled) 1f else 0.35f
    }

    private fun refresh() {
        if (busy) return
        busy = true
        eraseAction = null
        updateActions()
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                euiccChannelManagerLoaded.await()
                euiccChannelManagerService.waitForForegroundTask()
                euiccChannelManager.withEuiccChannel(logicalSlotId, seId) { channel ->
                    val items = buildEuiccInfoItems(channel)
                    val slot = if (logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID) {
                        if (channel.hasMultipleSE) getString(R.string.channel_name_format_usb_se, seId.id)
                        else getString(R.string.channel_name_format_usb)
                    } else if (channel.hasMultipleSE) appContainer.customizableTextProvider.formatNonUsbChannelNameWithSeId(logicalSlotId, seId)
                        else appContainer.customizableTextProvider.formatNonUsbChannelName(logicalSlotId)
                    val canErase = channel.lpa.profiles.enabled == null
                    val physicalSlot = channel.slotId
                    val port = channel.portId
                    val eid = channel.lpa.eID
                    withContext(Dispatchers.Main) {
                        page.removeAllViews()
                        val info = ui.card(page)
                        ui.keyValue(info, getString(R.string.minimal_slot), slot, labelSize = 13f, valueSize = 13f)
                        val copyIds = setOf(R.string.euicc_info_eid, R.string.euicc_info_sn,
                            R.string.euicc_info_isdr_aid, R.string.euicc_info_atr)
                        items.filter { it.titleResId !in copyIds }.forEach { item ->
                            ui.divider(info, verticalMargin = 12)
                            ui.keyValue(info, getString(item.titleResId), item.content ?: getString(R.string.euicc_info_unknown),
                                labelSize = 13f, valueSize = 13f, verticalGravity = android.view.Gravity.CENTER_VERTICAL)
                        }
                        items.filter { it.titleResId in copyIds }.forEach { item ->
                            val identifiers = ui.card(page)
                            val title = getString(item.titleResId)
                            val value = item.content ?: getString(R.string.euicc_info_unknown)
                            val icon = when (item.titleResId) {
                                R.string.euicc_info_eid -> R.drawable.ic_minimal_fingerprint
                                R.string.euicc_info_sn -> R.drawable.ic_minimal_tag
                                R.string.euicc_info_isdr_aid -> R.drawable.ic_minimal_chip
                                else -> R.drawable.ic_minimal_code
                            }
                            val row = android.widget.LinearLayout(this@EuiccInfoActivity).apply {
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            row.addView(android.widget.ImageView(this@EuiccInfoActivity).apply {
                                setImageResource(icon)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, android.widget.LinearLayout.LayoutParams(ui.dp(19), ui.dp(19)).apply {
                                marginEnd = ui.dp(12)
                            })
                            row.addView(ui.text(title, 16f).apply {
                                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                            identifiers.addView(row)
                            val valueText = ui.text(value, 13f, true).apply {
                                setBackgroundResource(R.drawable.minimal_language_selector)
                                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                                textDirection = View.TEXT_DIRECTION_LTR
                                val copyIcon = android.graphics.drawable.InsetDrawable(
                                    androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_minimal_copy), ui.dp(2)
                                ).apply { setBounds(0, 0, ui.dp(18), ui.dp(18)) }
                                val inlineValue = android.text.SpannableStringBuilder(value).append("\u00A0\uFFFC")
                                val iconStart = inlineValue.length - 1
                                inlineValue.setSpan(object : android.text.style.ImageSpan(copyIcon) {
                                    override fun draw(canvas: android.graphics.Canvas, text: CharSequence,
                                                      start: Int, end: Int, x: Float, top: Int, y: Int,
                                                      bottom: Int, paint: android.graphics.Paint) {
                                        val metrics = paint.fontMetrics
                                        val centerY = y + (metrics.ascent + metrics.descent) / 2f
                                        canvas.save()
                                        canvas.translate(x, centerY - drawable.bounds.height() / 2f)
                                        drawable.draw(canvas)
                                        canvas.restore()
                                    }
                                },
                                    iconStart, inlineValue.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                inlineValue.setSpan(object : android.text.style.ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, value))
                                        Toast.makeText(this@EuiccInfoActivity,
                                            item.copiedToastResId ?: R.string.minimal_copied, Toast.LENGTH_SHORT).show()
                                    }
                                    override fun updateDrawState(ds: android.text.TextPaint) {
                                        ds.isUnderlineText = false
                                    }
                                }, iconStart, inlineValue.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                text = inlineValue
                                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                                highlightColor = android.graphics.Color.TRANSPARENT
                            }
                            identifiers.addView(valueText, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                                topMargin = ui.dp(8)
                            })
                        }
                        eraseAction = if (canErase) ({
                            EuiccMemoryResetFragment.newInstance(physicalSlot, port, seId, eid)
                                .show(supportFragmentManager, EuiccMemoryResetFragment.TAG)
                        }) else null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                Toast.makeText(this@EuiccInfoActivity, R.string.minimal_error, Toast.LENGTH_LONG).show()
            } finally { busy = false; swipeRefresh.isRefreshing = false; updateActions() }
        }
    }

    private fun buildEuiccInfoItems(channel: EuiccChannel) = buildList {
        add(Item(R.string.euicc_info_access_mode, channel.type))
        add(Item(R.string.euicc_info_removable, formatByBoolean(channel.port.card.isRemovable, YES_NO)))
        add(Item(R.string.euicc_info_eid, channel.lpa.eID, copiedToastResId = R.string.toast_eid_copied))
        if (!channel.isdrAid.contentEquals(EUICC_DEFAULT_ISDR_AID.decodeHex())) {
            // Only show if it's not the default ISD-R AID
            add(Item(R.string.euicc_info_isdr_aid, channel.isdrAid.encodeHex()))
        }
        channel.tryParseEuiccVendorInfo()?.let { vendorInfo ->
            // @formatter:off
            vendorInfo.skuName?.let { add(Item(R.string.euicc_info_sku, it)) }
            vendorInfo.serialNumber?.let { add(Item(R.string.euicc_info_sn, it, copiedToastResId = R.string.toast_sn_copied)) }
            vendorInfo.firmwareVersion?.let { add(Item(R.string.euicc_info_fw_ver, it)) }
            // @formatter:on
        }
        channel.lpa.euiccInfo2?.let { info ->
            add(Item(R.string.euicc_info_sgp22_version, info.sgp22Version.toString()))
            info.sasAccreditationNumber.trim().takeIf(RE_SAS::matches)
                ?.let { add(Item(R.string.euicc_info_sas_accreditation_number, it.uppercase())) }

            val nvramText = buildString {
                append(formatFreeSpace(info.freeNvram))
                append(' ')
                append(getString(R.string.euicc_info_free_nvram_hint))
            }
            add(Item(R.string.euicc_info_free_nvram, nvramText))
        }
        channel.lpa.euiccInfo2?.euiccCiPKIdListForSigning.orEmpty().let { signers ->
            // SGP.28 v1.0, eSIM CI Registration Criteria (Page 5 of 9, 2019-10-24)
            // https://www.gsma.com/newsroom/wp-content/uploads/SGP.28-v1.0.pdf#page=5
            // FS.27 v2.0, Security Guidelines for UICC Profiles (Page 25 of 27, 2024-01-30)
            // https://www.gsma.com/solutions-and-impact/technologies/security/wp-content/uploads/2024/01/FS.27-Security-Guidelines-for-UICC-Credentials-v2.0-FINAL-23-July.pdf#page=25
            val resId = when {
                signers.isEmpty() -> R.string.euicc_info_unknown // the case is not mp, but it is not common
                PKID_GSMA_LIVE_CI.any(signers::contains) -> R.string.euicc_info_ci_gsma_live
                PKID_GSMA_TEST_CI.any(signers::contains) -> R.string.euicc_info_ci_gsma_test
                else -> R.string.euicc_info_ci_unknown
            }
            add(Item(R.string.euicc_info_ci_type, getString(resId)))
        }
        val atr = channel.atr?.encodeHex() ?: getString(R.string.euicc_info_unavailable)
        add(Item(R.string.euicc_info_atr, atr, copiedToastResId = R.string.toast_atr_copied))
    }

    @Suppress("SameParameterValue")
    private fun formatByBoolean(b: Boolean, res: Pair<Int, Int>): String =
        getString(if (b) res.first else res.second)

}
