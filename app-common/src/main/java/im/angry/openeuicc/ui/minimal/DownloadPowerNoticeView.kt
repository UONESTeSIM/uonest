package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import im.angry.openeuicc.common.R

/** Shared by the download flow and its debug-only phone preview. */
class DownloadPowerNoticeView(
    context: Context,
    continueLabel: Int,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) : LinearLayout(context) {
    private val ui = MinimalUi(context)
    private val title = ui.text("", 18f).apply {
        id = android.R.id.title
        gravity = Gravity.CENTER_HORIZONTAL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val summary = ui.text("", 13f, true).apply {
        id = android.R.id.summary
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val message = ui.text("", 14f, true).apply {
        id = android.R.id.message
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val actions = LinearLayout(context)
    private val next: MaterialButton
    private val cancel: MaterialButton
    private var stacked: Boolean? = null

    init {
        orientation = VERTICAL
        setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
        addView(title, LayoutParams(-1, -2))
        ui.gap(this, 8)
        addView(summary, LayoutParams(-1, -2))
        ui.gap(this, 14)
        addView(message, LayoutParams(-1, -2))
        ui.gap(this, 20)
        addView(actions, LayoutParams(-1, -2))
        cancel = ui.button(actions, context.getString(R.string.download_low_power_cancel), onCancel).apply {
            id = android.R.id.button2
            textSize = 16f
            minHeight = ui.dp(44); minimumHeight = ui.dp(44)
            insetTop = 0; insetBottom = 0
        }
        next = ui.button(actions, context.getString(continueLabel)) {}.apply {
            id = android.R.id.button1
            textSize = 16f
            minHeight = ui.dp(44); minimumHeight = ui.dp(44)
            insetTop = 0; insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(Color.WHITE)
        }
        next.setOnClickListener {
            if (!next.isEnabled) return@setOnClickListener
            next.isEnabled = false
            cancel.isEnabled = false
            onContinue()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val buttonWidth = (availableWidth - ui.dp(12)) / 2
        val shouldStack = listOf(cancel, next).any { button ->
            val label = button.transformationMethod?.getTransformation(button.text, button) ?: button.text
            val requiredWidth = Layout.getDesiredWidth(label, button.paint) +
                button.compoundPaddingLeft + button.compoundPaddingRight
            maxOf(requiredWidth, button.minimumWidth.toFloat()) > buttonWidth
        }
        if (stacked != shouldStack) {
            stacked = shouldStack
            actions.orientation = if (shouldStack) VERTICAL else HORIZONTAL
            cancel.layoutParams = if (shouldStack) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
            next.layoutParams = (if (shouldStack) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)).apply {
                if (shouldStack) topMargin = ui.dp(8) else marginStart = ui.dp(12)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun showNotice(titleRes: Int, status: CharSequence?, messageRes: Int) {
        title.setText(titleRes)
        summary.visibility = if (status == null) View.GONE else View.VISIBLE
        summary.text = status
        message.setText(messageRes)
    }
}
