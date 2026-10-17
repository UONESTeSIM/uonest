package im.angry.openeuicc.ui.wizard

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.HomeGlass
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Acquire activation codes and pass the selected home slot to the download wizard. */
open class AddEsimActivity : AppCompatActivity() {
    companion object {
        fun newIntent(context: Context, slot: Int, seId: EuiccChannel.SecureElementId) =
            DownloadWizardActivity.newIntent(context, slot, seId).setClass(context, AddEsimActivity::class.java)
    }

    protected open val isDemo = false
    protected open val activationCodeOnly = false
    protected lateinit var ui: MinimalUi
    private lateinit var page: LinearLayout
    private var screen = "methods"
    private var draft = ""
    private var reading = false
    private lateinit var manualForm: LinearLayout
    private lateinit var manualRow: LinearLayout

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { acceptCode(it) }
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            reading = true
            render()
            val code = withContext(Dispatchers.IO) { runCatching { readActivationQr(applicationContext, uri) }.getOrNull() }
            reading = false
            if (code == null) {
                render()
                Toast.makeText(this@AddEsimActivity, R.string.add_esim_image_error, Toast.LENGTH_LONG).show()
            } else acceptCode(code)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ui = MinimalUi(this, rowTitleSize = 16f, rowDetailSize = 13f)
        window.setBackgroundDrawableResource(R.color.add_esim_background)
        page = ui.page()
        requireViewById<FrameLayout>(R.id.settings_container).addView(ui.scroll(page))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView.rootView) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            activityToolbarInsetHandler(bars)
            page.setPadding(ui.dp(18) + bars.left, 0, ui.dp(18) + bars.right,
                ui.dp(24) + maxOf(bars.bottom, ime.bottom))
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        draft = savedInstanceState?.getString("draft").orEmpty()
        screen = savedInstanceState?.getString("screen")
            ?.takeIf { it == "manual" } ?: "methods"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen)
        outState.putString("draft", draft)
        super.onSaveInstanceState(outState)
    }

    private fun back() {
        if (activationCodeOnly) { finish(); return }
        when (screen) {
            "methods" -> finish()
            "manual" -> setManualExpanded(false)
            else -> { screen = "methods"; render() }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { back(); return true }
        return super.onOptionsItemSelected(item)
    }

    protected fun render() {
        page.removeAllViews()
        setTitle(R.string.add_esim_title)
        ui.gap(page, 6)
        targetSelector(page)
        if (activationCodeOnly) manual(glassCard()) else methods()
        if (isDemo) {
            ui.gap(page, 6)
            page.addView(ui.text(getString(R.string.add_esim_demo_notice), 13f, true))
        }
    }

    protected open fun targetSelector(parent: LinearLayout) = Unit
    protected open val canAcquireCode: Boolean get() = true

    protected fun retainCode(code: LPAString) {
        draft = "LPA:$code"
        screen = "manual"
        render()
    }

    private fun methods() {
        if (reading) {
            page.addView(ui.text(getString(R.string.add_esim_reading), 13f, true))
            ui.gap(page, 18)
        }
        method(R.id.add_esim_scan, R.string.add_esim_scan, R.string.add_esim_scan_detail, R.drawable.ic_scan_black) {
            runCatching { scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setOrientationLocked(false)
                setBeepEnabled(false)
                setPrompt(getString(R.string.add_esim_scan_detail))
            }) }.onFailure { Toast.makeText(this, R.string.add_esim_camera_error, Toast.LENGTH_LONG).show() }
        }
        method(R.id.add_esim_image, R.string.add_esim_image, R.string.add_esim_image_detail, R.drawable.ic_gallery_black) {
            gallery.launch("image/*")
        }
        val card = method(R.id.add_esim_manual, R.string.add_esim_manual, R.string.add_esim_manual_detail, R.drawable.ic_edit) {
            setManualExpanded(screen != "manual")
        }
        manualRow = card.getChildAt(0) as LinearLayout
        (manualRow.getChildAt(manualRow.childCount - 1) as android.widget.ImageView)
            .setImageResource(R.drawable.ic_chevron_down_minimal)
        manualForm = ui.column()
        card.addView(manualForm, LinearLayout.LayoutParams(-1, -2))
        manual(manualForm)
        setManualExpanded(screen == "manual", animate = false)
    }

    private fun glassCard() = ui.card(page).apply {
        background = HomeGlass.surface(this, whiteAlpha = 242, strokeWidthDp = .4f,
            strokeColor = ui.color(R.color.minimal_line))
        elevation = 3f * resources.displayMetrics.density
        outlineAmbientShadowColor = ui.color(R.color.minimal_glass_card_shadow)
        outlineSpotShadowColor = ui.color(R.color.minimal_glass_card_shadow)
    }

    private fun method(id: Int, title: Int, detail: Int, icon: Int, action: () -> Unit): LinearLayout {
        val card = glassCard()
        val row = ui.row(card, getString(title), getString(detail), icon) { if (!reading) action() }
        row.id = id
        row.minimumHeight = ui.dp(44)
        row.isEnabled = !reading && (id == R.id.add_esim_manual || canAcquireCode)
        (row.getChildAt(0) as android.widget.ImageView).imageTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
        return card
    }

    private fun setManualExpanded(expanded: Boolean, animate: Boolean = true) {
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            android.transition.TransitionManager.beginDelayedTransition(page,
                android.transition.AutoTransition().apply { duration = 180 })
        }
        screen = if (expanded) "manual" else "methods"
        manualForm.visibility = if (expanded) View.VISIBLE else View.GONE
        manualRow.getChildAt(manualRow.childCount - 1).rotation = if (expanded) 180f else 0f
        androidx.core.view.ViewCompat.setStateDescription(manualRow,
            getString(if (expanded) R.string.add_esim_expanded else R.string.add_esim_collapsed))
        if (!expanded) {
            currentFocus?.let {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(it.windowToken, 0)
                it.clearFocus()
            }
        }
    }

    private fun manual(container: LinearLayout) {
        val input = ui.editor(container, draft, multiline = true).apply {
            id = R.id.add_esim_code
            hint = getString(R.string.add_esim_code_hint)
            backgroundTintList = null
            setBackgroundResource(R.drawable.minimal_language_selector)
            includeFontPadding = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener { draft = it.toString(); error = null }
        }
        ui.gap(container, 16)
        primary(getString(R.string.add_esim_next), container) {
            if (!acceptCode(input.text.toString())) input.error = getString(R.string.add_esim_invalid)
        }
    }

    private fun acceptCode(code: String): Boolean {
        val result = runCatching { parseActivationInput(code) }.getOrNull()
        if (result == null) {
            if (screen != "manual") {
                Toast.makeText(this, R.string.add_esim_invalid, Toast.LENGTH_LONG).show()
                render()
            }
            return false
        }
        currentFocus?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        continueWithCode(result, directDownload = true)
        return true
    }

    private fun primary(title: String, parent: LinearLayout = page, action: () -> Unit) {
        ui.button(parent, title, action).apply {
            id = R.id.add_esim_next
            isEnabled = canAcquireCode
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48))
            insetTop = 0; insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(android.graphics.Color.WHITE)
        }
    }

    protected open fun continueWithCode(code: LPAString, directDownload: Boolean) {
        startActivity(Intent(this, DownloadWizardActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            putExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, directDownload)
            data = "LPA:$code".toUri()
        })
        finish()
    }
}
