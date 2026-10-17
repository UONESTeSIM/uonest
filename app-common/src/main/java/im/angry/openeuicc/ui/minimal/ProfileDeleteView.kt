package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import im.angry.openeuicc.common.R

/** Shared confirmation layout for profile and notification deletion. */
class ProfileDeleteView(
    context: Context,
    message: CharSequence,
    title: CharSequence = context.getString(R.string.minimal_delete_notice_title),
    onDelete: () -> Unit,
    onCancel: () -> Unit
) : LinearLayout(context) {
    val titleView: android.widget.TextView
    val messageView: android.widget.TextView
    val cancelButton: MaterialButton
    val deleteButton: MaterialButton

    init {
        val ui = MinimalUi(context, rowTitleSize = 16f, rowDetailSize = 13f)
        orientation = VERTICAL
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        setBackgroundResource(R.drawable.minimal_card)
        isFocusableInTouchMode = true
        titleView = ui.text(title, 16f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        addView(titleView, LayoutParams(-1, -2))
        ui.gap(this, 10)
        messageView = ui.text(message, 13f, true)
        addView(messageView, LayoutParams(-1, -2))
        ui.gap(this, 18)
        val actions = LinearLayout(context)
        addView(actions, LayoutParams(-1, -2))
        cancelButton = ui.button(actions, context.getString(android.R.string.cancel), onCancel).apply {
            id = android.R.id.button2
            textSize = 16f
            cornerRadius = ui.dp(10)
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = LayoutParams(0, ui.dp(40), 1f)
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_tint))
            setTextColor(ui.color(R.color.minimal_secondary))
        }
        deleteButton = ui.button(actions, context.getString(R.string.profile_delete), onDelete).apply {
            id = android.R.id.button1
            textSize = 16f
            cornerRadius = ui.dp(10)
            insetTop = 0; insetBottom = 0
            minHeight = ui.dp(40); minimumHeight = ui.dp(40)
            layoutParams = LayoutParams(0, ui.dp(40), 1f).apply { marginStart = ui.dp(12) }
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_red))
            setTextColor(Color.WHITE)
        }
    }
}
