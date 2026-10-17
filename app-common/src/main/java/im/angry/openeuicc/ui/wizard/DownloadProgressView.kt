package im.angry.openeuicc.ui.wizard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.MinimalUi
import net.typeblog.lpac_jni.LocalProfileAssistant
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared download status and failure layout, also used by the debug preview. */
class DownloadProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val ui = MinimalUi(context)
    private val density = resources.displayMetrics.density
    val animation = DownloadAnimationView(context).apply { id = R.id.download_animation }
    val status = ui.text(context.getString(R.string.download_wizard_progress_step_preparing), 16f).apply {
        id = R.id.download_status
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val reason = ui.text("", 13f).apply {
        id = R.id.download_failure_reason
        gravity = Gravity.START
        setTextColor(ui.color(R.color.minimal_failure_notice_text))
    }
    val advice = ui.text("", 13f, true).apply { id = R.id.download_failure_advice; gravity = Gravity.START }
    val diagnostics = LayoutInflater.from(context).inflate(R.layout.download_failure_diagnostics, this, false).apply {
        id = R.id.download_failure_diagnostics
        elevation = ui.dp(2).toFloat()
        outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
        outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
    }
    val diagnosticText: TextView = diagnostics.requireViewById(R.id.download_wizard_diagnostics_text)
    val copyDiagnostics: View = diagnostics.requireViewById(R.id.download_failure_diagnostics_copy)
    val returnHome = com.google.android.material.button.MaterialButton(context).apply {
        id = R.id.download_return_home
        setText(R.string.download_return_home)
        textSize = 16f
        isAllCaps = false
        cornerRadius = ui.dp(10)
        insetTop = 0; insetBottom = 0
        backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
        setTextColor(Color.WHITE)
    }
    private val failureNotice = ui.column().apply {
        setBackgroundResource(R.drawable.download_notice_glass)
        elevation = ui.dp(1).toFloat()
        outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
        outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        addView(reason, LinearLayout.LayoutParams(-1, -2))
        addView(advice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
    }
    private val labels = ui.column().apply {
        clipChildren = false
        clipToPadding = false
        addView(status, LinearLayout.LayoutParams(-1, -2))
        addView(failureNotice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
    }
    private val details = ScrollView(context).apply {
        isFillViewport = false
        addView(labels)
    }

    init {
        // The visible status text announces changes; avoid announcing the icon twice.
        animation.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(animation, LayoutParams(-1, -1))
        addView(details, LayoutParams(-1, -2))
        addView(diagnostics, LayoutParams(-1, -2))
        addView(returnHome, LayoutParams(-1, ui.dp(48)))
        copyDiagnostics.setOnClickListener {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.download_diagnostics_information), diagnosticText.text.toString())
            )
            Toast.makeText(context, R.string.minimal_copied, Toast.LENGTH_SHORT).show()
        }
        showResult(DownloadAnimationView.Result.Downloading)
    }

    fun showStage(text: CharSequence) {
        status.text = text
        animation.contentDescription = text
    }

    fun showResult(result: DownloadAnimationView.Result,
                   error: LocalProfileAssistant.ProfileDownloadException? = null) {
        animation.showResult(result)
        val failed = result == DownloadAnimationView.Result.Failure
        status.textSize = if (failed) 16f else 14f
        status.includeFontPadding = !failed
        listOf(failureNotice, reason, advice, diagnostics, returnHome).forEach { it.visibility = if (failed) View.VISIBLE else View.GONE }
        when (result) {
            DownloadAnimationView.Result.Downloading -> Unit
            DownloadAnimationView.Result.Success -> status.setText(R.string.download_animation_success)
            DownloadAnimationView.Result.Failure -> {
                diagnosticText.text = error?.let { context.buildDownloadDiagnostics(it) }
                    ?: context.getString(R.string.download_failure_unknown)
                status.setText(R.string.download_failure_title)
                val summary = error?.let { runCatching { SimplifiedErrorMessages.fromDownloadError(it) }.getOrNull() }
                reason.setText(when (summary) {
                    SimplifiedErrorMessages.UnreleasedProfile -> R.string.download_failure_not_ready
                    SimplifiedErrorMessages.EIDMismatch -> R.string.download_failure_eid_mismatch
                    SimplifiedErrorMessages.TLSError -> R.string.download_failure_secure_connection
                    else -> summary?.titleResId ?: R.string.download_failure_unknown
                })
                advice.setText(when (summary) {
                    SimplifiedErrorMessages.NetworkUnreachable -> R.string.download_failure_check_network
                    SimplifiedErrorMessages.UnreleasedProfile -> R.string.download_failure_contact_release
                    SimplifiedErrorMessages.UnknownHost -> R.string.download_failure_check_address
                    SimplifiedErrorMessages.TLSError -> R.string.download_failure_check_connection
                    SimplifiedErrorMessages.UnsupportedProfile -> R.string.download_wizard_error_suggest_contact_carrier
                    SimplifiedErrorMessages.CardInternalError -> R.string.download_failure_check_chip
                    else -> summary?.suggestResId ?: R.string.download_failure_check_diagnostics
                })
            }
        }
        requestLayout()
    }

    private fun iconHeight(): Float = animation.artworkHeight(measuredWidth, measuredHeight)

    private fun contentBottom(): Int = measuredHeight - if (returnHome.visibility == VISIBLE) ui.dp(84) else ui.dp(24)

    private fun preferredIconTop(): Float = when (animation.result) {
        DownloadAnimationView.Result.Failure -> min(height * .08f, ui.dp(48).toFloat())
        else -> (height - iconHeight() - ui.dp(24) - details.measuredHeight) / 2f - ui.dp(17)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val failed = diagnostics.visibility == VISIBLE
        val summarySpace = if (failed) (contentBottom() - iconHeight().roundToInt() - ui.dp(180)).coerceAtLeast(ui.dp(48))
            else contentBottom() - iconHeight().roundToInt() - ui.dp(40)
        details.measure(
            MeasureSpec.makeMeasureSpec((measuredWidth - ui.dp(if (failed) 36 else 48)).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(summarySpace.coerceAtLeast(0), MeasureSpec.AT_MOST)
        )
        returnHome.measure(MeasureSpec.makeMeasureSpec((measuredWidth - ui.dp(36)).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ui.dp(48), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val failed = diagnostics.visibility == VISIBLE
        val reserved = if (failed) ui.dp(120) else 0
        val iconTop = min(preferredIconTop(), contentBottom() - details.measuredHeight - ui.dp(24) - iconHeight() - reserved)
            .coerceAtLeast(ui.dp(16).toFloat())
        animation.simTopOverride = iconTop
        val textTop = (iconTop + animation.artworkContentBottom(width, height) +
            ui.dp(if (failed) 16 else 24)).roundToInt()
        val detailsInset = ui.dp(if (failed) 18 else 24)
        details.layout(detailsInset, textTop, width - detailsInset, textTop + details.measuredHeight)
        if (failed) {
            val diagnosticTop = details.bottom + ui.dp(16)
            diagnostics.measure(MeasureSpec.makeMeasureSpec((width - ui.dp(36)).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((contentBottom() - diagnosticTop).coerceAtLeast(0), MeasureSpec.AT_MOST))
            diagnostics.layout(ui.dp(18), diagnosticTop, width - ui.dp(18), diagnosticTop + diagnostics.measuredHeight)
        }
        returnHome.layout(ui.dp(18), height - ui.dp(66), width - ui.dp(18), height - ui.dp(18))
    }
}
