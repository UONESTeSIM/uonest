package im.angry.openeuicc.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.util.*
import kotlinx.coroutines.launch

class EuiccMemoryResetFragment : DialogFragment(), EuiccChannelFragmentMarker {
    companion object {
        const val TAG = "EuiccMemoryResetFragment"

        private const val FIELD_EID = "eid"

        fun newInstance(slotId: Int, portId: Int, seId: EuiccChannel.SecureElementId, eid: String) =
            newInstanceEuicc(EuiccMemoryResetFragment::class.java, slotId, portId, seId) {
                putString(FIELD_EID, eid)
            }
    }

    private val eid: String by lazy { requireArguments().getString(FIELD_EID)!! }

    private val confirmText: String by lazy { eid.takeLast(8) }

    private inline val isMatched: Boolean
        get() = confirmText.length == 8 && confirmText.all { it in '0'..'9' } &&
            editText.text.toString() == confirmText

    private var confirmed = false

    private var toast: Toast? = null
        set(value) {
            toast?.cancel()
            field = value
            value?.show()
        }

    private lateinit var editText: EditText
    private lateinit var eraseButton: com.google.android.material.button.MaterialButton
    private lateinit var cancelButton: com.google.android.material.button.MaterialButton

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(requireContext())
        val content = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            setBackgroundResource(R.drawable.minimal_card)
            isFocusableInTouchMode = true
        }
        content.addView(ui.text(getString(R.string.minimal_delete_notice_title), 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
        }, android.widget.LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 10)
        content.addView(ui.text(getString(R.string.euicc_erase_warning), 13f, true))
        ui.gap(content, 14)
        content.addView(ui.text(getString(R.string.euicc_info_eid), 13f, true))
        content.addView(ui.text(eid, 13f).apply {
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        ui.gap(content, 14)
        content.addView(ui.text(getString(R.string.euicc_erase_instruction, confirmText), 13f, true))
        editText = ui.editor(content, "").apply {
            id = R.id.euicc_erase_input
            textSize = 13f
            typeface = Typeface.DEFAULT
            hint = getString(R.string.euicc_erase_input_hint)
            contentDescription = getString(R.string.euicc_erase_instruction, confirmText)
            isLongClickable = false
            backgroundTintList = null
            setBackgroundResource(R.drawable.minimal_language_selector)
            includeFontPadding = false
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789")
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            setText(savedInstanceState?.getString("confirmation").orEmpty())
            minLines = 1
            minHeight = ui.dp(48)
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setHorizontallyScrolling(false)
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }
        ui.gap(content, 18)
        val actions = android.widget.LinearLayout(requireContext())
        content.addView(actions, android.widget.LinearLayout.LayoutParams(-1, -2))
        cancelButton = ui.button(actions, getString(android.R.string.cancel)) { if (!confirmed) dismiss() }.apply {
            id = android.R.id.button2
            textSize = 16f
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ui.dp(40), 1f)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_tint))
            setTextColor(ui.color(R.color.minimal_secondary))
        }
        eraseButton = ui.button(actions, getString(R.string.euicc_memory_reset_invoke_button)) {
            if (!confirmed) confirmation()
        }.apply {
            id = android.R.id.button1
            textSize = 16f
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { marginStart = ui.dp(12) }
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_red))
            setTextColor(android.graphics.Color.WHITE)
        }
        fun updateEraseButton() {
            eraseButton.isEnabled = !confirmed && isMatched
            eraseButton.alpha = if (eraseButton.isEnabled) 1f else 0.35f
        }
        editText.doAfterTextChanged { updateEraseButton() }
        updateEraseButton()
        return im.angry.openeuicc.ui.minimal.ProfileEditorDialog(requireContext()).apply {
            setContentView(ui.scroll(content))
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("confirmation", editText.text.toString())
    }

    private fun confirmation() {
        toast?.cancel()
        if (!isMatched) {
            val resId = R.string.toast_euicc_memory_reset_confirm_text_mismatched
            editText.error = getString(resId)
            editText.requestFocus()
            return
        }
        confirmed = true
        preventUserAction()

        (parentFragment?.lifecycleScope ?: requireActivity().lifecycleScope).launch {
            ensureEuiccChannelManager()
            euiccChannelManagerService.waitForForegroundTask()

            val error = euiccChannelManagerService.launchMemoryReset(slotId, portId, seId)
                .stateFlow.waitDone()
            if (error == null) {
                parentFragment?.notifyEuiccProfilesChanged()
                if (isAdded) parentFragmentManager.setFragmentResult("chipReset", Bundle())
                toast = Toast.makeText(context, R.string.toast_euicc_memory_reset_finished, Toast.LENGTH_LONG)
            } else {
                toast = Toast.makeText(context, R.string.minimal_error, Toast.LENGTH_LONG)
            }
            runCatching(::dismiss)

        }
    }

    private fun preventUserAction() {
        editText.isEnabled = false
        isCancelable = false
        requireDialog().setCanceledOnTouchOutside(false)
        eraseButton.isEnabled = false
        eraseButton.alpha = 0.35f
        cancelButton.isEnabled = false
    }
}
