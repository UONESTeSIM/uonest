package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import im.angry.openeuicc.common.R

/** Shared form for real profiles and the isolated demo profiles. */
class ProfileEditorView(
    context: Context,
    currentName: String,
    onSave: () -> Unit,
    onCancel: () -> Unit
) : LinearLayout(context) {
    private val ui = MinimalUi(context, rowTitleSize = 16f, rowDetailSize = 13f)
    val nameEditor: android.widget.EditText
    val progress: ProgressBar

    init {
        orientation = VERTICAL
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        isFocusableInTouchMode = true
        background = GradientDrawable().apply {
            setColor(ui.color(R.color.minimal_surface))
            cornerRadius = ui.dp(18).toFloat()
        }
        addView(ui.text(context.getString(R.string.profile_rename), 16f).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }, LayoutParams(-1, -2))
        nameEditor = ui.editor(this, currentName).apply {
            layoutParams = (layoutParams as LayoutParams).apply { topMargin = ui.dp(12) }
            backgroundTintList = null
            setBackgroundResource(R.drawable.minimal_language_selector)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ui.dp(48)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            contentDescription = context.getString(R.string.profile_rename)
        }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        addView(progress, LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(12) })
        ui.gap(this, 18)
        val actions = LinearLayout(context)
        addView(actions, LayoutParams(-1, -2))
        ui.button(actions, context.getString(android.R.string.cancel), onCancel).apply {
            id = android.R.id.button2
            textSize = 16f
            cornerRadius = ui.dp(10)
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = LayoutParams(0, ui.dp(40), 1f)
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_tint))
            setTextColor(ui.color(R.color.minimal_secondary))
        }
        ui.button(actions, context.getString(R.string.minimal_save), onSave).apply {
            id = android.R.id.button1
            textSize = 16f
            cornerRadius = ui.dp(10)
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = LayoutParams(0, ui.dp(40), 1f).apply { marginStart = ui.dp(12) }
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(Color.WHITE)
        }
    }
}
