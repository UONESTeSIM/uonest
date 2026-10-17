package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import im.angry.openeuicc.common.R

/** Shared by the download flow and the isolated confirmation-code demo. */
class ConfirmationCodeView(
    context: Context,
    initialCode: String,
    onCodeChanged: (String) -> Unit,
    onContinue: () -> Unit
) : LinearLayout(context) {
    private val ui = MinimalUi(context)
    private var submitted = false
    val input = TextInputEditText(context).apply {
        id = R.id.download_confirmation_input
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        textSize = 13f
        typeface = Typeface.DEFAULT
        setTextColor(ui.color(R.color.minimal_text))
        setHintTextColor(ui.color(R.color.minimal_secondary))
        hint = context.getString(R.string.download_confirmation_title)
        contentDescription = hint
        background = null
        includeFontPadding = false
        setSingleLine(true)
        minHeight = ui.dp(48)
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        imeOptions = EditorInfo.IME_ACTION_DONE
        setText(initialCode)
    }

    init {
        orientation = VERTICAL
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        setBackgroundResource(R.drawable.minimal_card)
        isFocusableInTouchMode = true
        addView(ui.text(context.getString(R.string.download_confirmation_instruction), 13f, true), LayoutParams(-1, -2))
        ui.gap(this, 14)
        addView(TextInputLayout(context).apply {
            isHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            setBackgroundResource(R.drawable.minimal_language_selector)
            addView(input, LayoutParams(-1, -2))
            endIconMode = TextInputLayout.END_ICON_NONE
        }, LayoutParams(-1, -2))
        ui.gap(this, 18)
        val actions = LinearLayout(context)
        addView(actions, LayoutParams(-1, -2))
        val next = ui.button(actions, context.getString(R.string.download_confirmation_continue)) {}.apply {
            id = android.R.id.button1
            textSize = 16f
            cornerRadius = ui.dp(10)
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = LayoutParams(-1, ui.dp(40))
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(Color.WHITE)
        }
        fun updateButton() {
            next.isEnabled = !submitted && input.text.toString().isNotBlank()
            next.alpha = if (next.isEnabled) 1f else .35f
        }
        input.doAfterTextChanged {
            onCodeChanged(it.toString().trim())
            updateButton()
        }
        next.setOnClickListener {
            if (!submitted && input.text.toString().isNotBlank()) {
                submitted = true
                onCodeChanged(input.text.toString().trim())
                updateButton()
                input.isEnabled = false
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                onContinue()
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                if (next.isEnabled) next.performClick()
                true
            } else false
        }
        updateButton()
    }
}
