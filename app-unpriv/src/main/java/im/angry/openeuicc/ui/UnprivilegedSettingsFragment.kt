package im.angry.openeuicc.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

class UnprivilegedSettingsFragment : Fragment(), OpenEuiccContextMarker {
    private lateinit var ui: MinimalUi
    private lateinit var page: LinearLayout
    private var screen = "settings"
    private var job: Job? = null
    private var proxyDraft: String? = null
    private var proxyEditor: android.widget.EditText? = null
    private val history = mutableListOf<String>()
    private var taps = 0
    private var lastTap = 0L
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { navigateBack() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        ui = MinimalUi(requireContext(), rowTitleSize = 16f, rowDetailSize = 13f, glassCards = true)
        page = ui.page()
        screen = state?.getString("screen") ?: arguments?.getString(SettingsActivity.EXTRA_SCREEN)
            ?.takeIf { it in setOf("advanced", "about", "developer") } ?: "settings"
        history.addAll(state?.getStringArrayList("history").orEmpty())
        proxyDraft = state?.getString("proxyDraft")
        return ui.scroll(page)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        setupRootViewSystemBarInsets(view, arrayOf(ui.pageInsets(page)))
        show(screen, false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen)
        outState.putStringArrayList("history", ArrayList(history))
        outState.putString("proxyDraft", proxyEditor?.text?.toString() ?: proxyDraft)
    }

    private fun navigateBack() { show(if (history.isNotEmpty()) history.removeAt(history.lastIndex) else "settings", false) }

    private fun show(next: String, push: Boolean = true) {
        if (push && next != screen) history.add(screen)
        if (screen == "proxy") proxyDraft = proxyEditor?.text?.toString() ?: proxyDraft
        proxyEditor = null
        screen = next
        back.isEnabled = history.isNotEmpty()
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            val prefs = preferenceRepository
            val developer = prefs.developerOptionsEnabledFlow.first()
            page.removeAllViews()
            (requireActivity() as AppCompatActivity).supportActionBar?.setTitle(when (next) {
                "advanced" -> R.string.pref_advanced
                "developer" -> R.string.pref_developer
                "about" -> R.string.minimal_about
                "proxy" -> R.string.pref_advanced_http_proxy
                else -> R.string.minimal_notification_settings
            })
            when (next) {
                "settings" -> {
                    val notifications = ui.card(page)
                    toggle(notifications, R.string.pref_notifications_download, R.string.pref_notifications_download_desc, prefs.notificationDownloadFlow)
                    ui.divider(notifications, verticalMargin = 12)
                    toggle(notifications, R.string.pref_notifications_delete, R.string.pref_notifications_delete_desc, prefs.notificationDeleteFlow)
                    ui.divider(notifications, verticalMargin = 12)
                    toggle(notifications, R.string.pref_notifications_switch, R.string.pref_notifications_switch_desc, prefs.notificationSwitchFlow)
                }
                "advanced" -> {
                    val options = ui.card(page)
                    toggle(options, R.string.pref_advanced_disable_safeguard_removable_esim, R.string.pref_advanced_disable_safeguard_removable_esim_desc, prefs.disableSafeguardFlow)
                    ui.divider(options, verticalMargin = 12)
                    toggle(options, R.string.pref_advanced_force_tpdu_mode, R.string.pref_advanced_force_tpdu_mode_desc, prefs.forceTpduModeFlow)
                    languageSelector(ui.card(page, small = true))
                    val links = ui.card(page)
                    ui.row(links, getString(R.string.pref_advanced_http_proxy), prefs.httpProxyFlow.first().ifBlank { getString(R.string.minimal_unset) }, R.drawable.ic_minimal_network) { show("proxy") }
                    ui.row(ui.card(page, small = true), getString(R.string.pref_developer_isdr_aid_list), getString(R.string.minimal_aid_detail), R.drawable.ic_minimal_code) {
                        startActivity(Intent(requireContext(), IsdrAidListActivity::class.java))
                    }
                }
                "developer" -> {
                    if (!developer) { show("settings"); return@launch }
                    val options = ui.card(page)
                    toggle(options, R.string.pref_advanced_verbose_logging, R.string.pref_advanced_verbose_logging_desc, prefs.verboseLoggingFlow)
                    ui.divider(options, verticalMargin = 12)
                    toggle(options, R.string.pref_developer_refresh_after_switch, R.string.pref_developer_refresh_after_switch_desc, prefs.refreshAfterSwitchFlow)
                    ui.divider(options, verticalMargin = 12)
                    toggle(options, R.string.pref_developer_unfiltered_profile_list, R.string.pref_developer_unfiltered_profile_list_desc, prefs.unfilteredProfileListFlow)
                    ui.divider(options, verticalMargin = 12)
                    toggle(options, R.string.pref_developer_ignore_tls_certificate, R.string.pref_developer_ignore_tls_certificate_desc, prefs.ignoreTLSCertificateFlow)
                    val values = resources.getStringArray(R.array.pref_developer_es10x_entry_values).toList()
                    val names = resources.getStringArray(R.array.pref_developer_es10x_entry_keys).toList()
                    inlineSelector(ui.card(page, small = true), im.angry.easyeuicc.R.id.es10x_mss_selector,
                        getString(R.string.pref_developer_es10x_mss), names,
                        values.indexOf(prefs.es10xMssFlow.first().toString()), widthDp = 110, arrowWidthDp = 20,
                        backgroundRes = R.drawable.minimal_language_selector) { index ->
                        viewLifecycleOwner.lifecycleScope.launch { prefs.es10xMssFlow.updatePreference(values[index].toInt()) }
                    }
                    ui.row(ui.card(page, small = true), getString(R.string.pref_advanced_logs), getString(R.string.pref_advanced_logs_desc), R.drawable.ic_minimal_logs) { startActivity(Intent(requireContext(), LogsActivity::class.java)) }
                }
                "about" -> {
                    val identity = ui.card(page, small = true)
                    val identityRow = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
                    identityRow.addView(android.widget.ImageView(requireContext()).apply {
                        setImageResource(im.angry.easyeuicc.R.mipmap.uonest_launcher)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(14) })
                    val identityText = ui.column()
                    identityText.addView(android.widget.ImageView(requireContext()).apply {
                        setImageResource(im.angry.openeuicc.common.R.drawable.uonest_wordmark)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        contentDescription = getString(im.angry.easyeuicc.R.string.app_name)
                    }, LinearLayout.LayoutParams(ui.dp(100), ui.dp(23)))
                    identityRow.addView(identityText)
                    identity.addView(identityRow)
                    val links = ui.card(page)
                    ui.row(links, getString(R.string.pref_info_app_version), requireContext().selfAppVersion, R.drawable.ic_minimal_layers) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        taps = if (now - lastTap < 1500) taps + 1 else 1
                        lastTap = now
                        if (taps >= 7 && !developer) viewLifecycleOwner.lifecycleScope.launch {
                            prefs.developerOptionsEnabledFlow.updatePreference(true)
                            Toast.makeText(requireContext(), R.string.developer_options_enabled, Toast.LENGTH_SHORT).show()
                            show("about", false)
                        }
                    }
                    val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val hashes = info.signingInfo!!.apkContentsSigners.map {
                        MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).encodeHex().uppercase(java.util.Locale.ROOT)
                    }.distinct().filterNot { it == "D1C0F48B370E74D4EA4770ED4C3CD70A3198D31F" }
                    val certificates = ui.card(page)
                    ui.row(certificates, getString(R.string.minimal_ara_m), icon = R.drawable.ic_minimal_fingerprint)
                    hashes.forEachIndexed { index, hash ->
                        ui.gap(certificates, if (index == 0) 8 else 12)
                        if (hashes.size > 1) {
                            val label = when (hash) {
                                "23BEBA4A34379ED306CFE63EAF4FE8EECA9979C9" -> "uonest"
                                else -> getString(R.string.minimal_certificate_number, index + 1)
                            }
                            certificates.addView(ui.text(label, 12f, true))
                            ui.gap(certificates, 4)
                        }
                        val value = ui.text("", 13f).apply {
                            id = R.id.about_certificate_hash
                            setBackgroundResource(R.drawable.minimal_language_selector)
                            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                            textDirection = View.TEXT_DIRECTION_LTR
                            val copyIcon = android.graphics.drawable.InsetDrawable(
                                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_minimal_copy), ui.dp(2)
                            ).apply { setBounds(0, 0, ui.dp(18), ui.dp(18)) }
                            val content = android.text.SpannableStringBuilder(hash).append("\u00A0\uFFFC")
                            val iconStart = content.length - 1
                            content.setSpan(object : android.text.style.ImageSpan(copyIcon) {
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
                            }, iconStart, content.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            content.setSpan(object : android.text.style.ClickableSpan() {
                                override fun onClick(widget: View) {
                                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                        android.content.ClipData.newPlainText(getString(R.string.minimal_ara_m), hash))
                                    Toast.makeText(context, R.string.minimal_copied, Toast.LENGTH_SHORT).show()
                                }
                                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
                            }, iconStart, content.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            text = content
                            movementMethod = android.text.method.LinkMovementMethod.getInstance()
                            highlightColor = android.graphics.Color.TRANSPARENT
                        }
                        certificates.addView(value, LinearLayout.LayoutParams(-1, -2))
                    }
                    appContainer.customizableTextProvider.websiteUri?.let { uri ->
                        ui.row(ui.card(page, small = true), getString(R.string.pref_info_website), uri.host, R.drawable.ic_minimal_network) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                }
                "proxy" -> {
                    val card = ui.card(page)
                    card.addView(ui.text(getString(R.string.pref_advanced_http_proxy), 16f))
                    val editor = ui.editor(card, proxyDraft ?: prefs.httpProxyFlow.first())
                    editor.setBackgroundResource(R.drawable.minimal_language_selector)
                    proxyEditor = editor
                    editor.hint = "http://host:8080"
                    ui.gap(card)
                    card.addView(ui.text(getString(R.string.pref_advanced_http_proxy_dialog_message), 13f, true))
                    ui.button(ui.actions(page), getString(R.string.minimal_save)) {
                        val value = editor.text.toString().trim()
                        val uri = Uri.parse(value)
                        if (value.isNotEmpty() && (uri.scheme != "http" || uri.host.isNullOrBlank() || uri.port !in 1..65535 || !uri.userInfo.isNullOrEmpty() || !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty())) {
                            editor.error = getString(R.string.minimal_proxy_invalid)
                        } else viewLifecycleOwner.lifecycleScope.launch {
                            prefs.httpProxyFlow.updatePreference(value)
                            proxyDraft = null
                            proxyEditor = null
                            navigateBack()
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(8) }
                        textSize = 16f
                        backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_blue))
                        setTextColor(android.graphics.Color.WHITE)
                        insetTop = 0
                        insetBottom = 0
                    }
                }
            }
        }
    }

    private fun languageSelector(parent: LinearLayout) {
        val tags = listOf("", "zh-CN", "zh-TW", "en-US", "ja")
        val choices = listOf(getString(R.string.minimal_system_language), "简体中文", "繁體中文", "English", "日本語")
        val selected = tags.indexOf(AppCompatDelegate.getApplicationLocales().toLanguageTags()).coerceAtLeast(0)
        inlineSelector(parent, im.angry.easyeuicc.R.id.language_selector,
            getString(R.string.pref_advanced_language), choices, selected, arrowWidthDp = 20,
            backgroundRes = R.drawable.minimal_language_selector) { index ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[index]))
        }
    }

    private fun inlineSelector(parent: LinearLayout, viewId: Int, title: String, choices: List<String>,
                               selected: Int, fieldChoices: List<String> = choices, widthDp: Int = 0, arrowWidthDp: Int = 20,
                               backgroundRes: Int = 0, change: (Int) -> Unit) {
        var current = selected.coerceAtLeast(0)
        val row = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(ui.text(title, 16f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(16) })
        val field = LinearLayout(requireContext()).apply {
            id = viewId
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.color(R.color.minimal_tint))
                cornerRadius = ui.dp(10).toFloat()
            }
            if (backgroundRes != 0) setBackgroundResource(backgroundRes)
            gravity = android.view.Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            minimumHeight = ui.dp(40)
            isFocusable = true
            contentDescription = "$title: ${choices[current]}"
        }
        val valueText = ui.text(fieldChoices[current], 14f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            maxWidth = ui.dp(76)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        field.addView(valueText, if (widthDp > 0) LinearLayout.LayoutParams(0, -2, 1f)
            else LinearLayout.LayoutParams(-2, -2))
        field.addView(android.widget.ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_chevron_down_minimal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(arrowWidthDp), ui.dp(20)).apply { marginStart = ui.dp(4) })
        row.addView(field, LinearLayout.LayoutParams(if (widthDp > 0) ui.dp(widthDp) else -2, -2))
        parent.addView(row)

        val choicesAdapter = object : android.widget.ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as android.widget.TextView).apply {
                    textSize = 14f
                    setTextColor(ui.color(if (position == current) R.color.minimal_blue else R.color.minimal_text))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    minHeight = ui.dp(40)
                    minimumHeight = ui.dp(40)
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                    layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                }
        }
        val popup = androidx.appcompat.widget.ListPopupWindow(requireContext()).apply {
            anchorView = field
            isModal = true
            setOverlapAnchor(false)
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.color(R.color.minimal_surface))
                cornerRadius = ui.dp(10).toFloat()
            })
            if (backgroundRes != 0) setBackgroundDrawable(
                androidx.core.content.ContextCompat.getDrawable(requireContext(), backgroundRes))
            setAdapter(choicesAdapter)
            setOnItemClickListener { _, _, index, _ ->
                dismiss()
                if (index != current) {
                    current = index
                    valueText.text = fieldChoices[index]
                    field.contentDescription = "$title: ${choices[index]}"
                    choicesAdapter.notifyDataSetChanged()
                    change(index)
                }
            }
        }
        field.setOnClickListener {
            val visible = android.graphics.Rect().also { field.getWindowVisibleDisplayFrame(it) }
            val location = IntArray(2).also { field.getLocationOnScreen(it) }
            popup.width = field.width
            popup.verticalOffset = ui.dp(4)
            val contentHeight = choices.indices.sumOf { index ->
                choicesAdapter.getView(index, null, field).let { item ->
                    item.measure(View.MeasureSpec.makeMeasureSpec(field.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    item.measuredHeight
                }
            }
            // Keep the menu below its field; scroll its items when the available height is limited.
            popup.height = minOf(contentHeight,
                (visible.bottom - location[1] - field.height - ui.dp(4)).coerceAtLeast(1))
            popup.show()
            popup.setSelection(current)
        }
        field.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { popup.dismiss() }
        })
    }

    private suspend fun toggle(parent: LinearLayout, title: Int, desc: Int, flow: PreferenceFlowWrapper<Boolean>) {
        ui.toggle(parent, getText(title), getText(desc), flow.first()) { value ->
            viewLifecycleOwner.lifecycleScope.launch { flow.updatePreference(value) }
        }
    }
}
