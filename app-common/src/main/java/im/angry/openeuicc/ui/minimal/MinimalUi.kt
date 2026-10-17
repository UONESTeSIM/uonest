package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import im.angry.openeuicc.common.R

/** Native widgets shared by the compact screens. Dimensions are dp, text sizes sp. */
class MinimalUi(
    val context: Context,
    private val rowTitleSize: Float = 14f,
    private val rowDetailSize: Float = 11f,
    private val glassCards: Boolean = false
) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(id: Int) = ContextCompat.getColor(context, id)
    fun styleIconPress(view: View) {
        view.background = null
        view.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.minimal_icon_press)
    }
    fun styleHomeRefresh(refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout) {
        refresh.apply {
            setSize(androidx.swiperefreshlayout.widget.SwipeRefreshLayout.DEFAULT)
            setColorSchemeColors(color(R.color.minimal_blue))
            setProgressBackgroundColorSchemeColor(android.graphics.Color.TRANSPARENT)
            for (index in 0 until childCount) {
                val indicator = getChildAt(index) as? ImageView ?: continue
                val ring = indicator.drawable as? androidx.swiperefreshlayout.widget.CircularProgressDrawable ?: continue
                // SwipeRefreshLayout resets this drawable's alpha; keep its transparent background.
                indicator.elevation = 0f
                val spinner = RefreshSpinnerDrawable(ring,
                    context.resources.displayMetrics.density, color(R.color.minimal_blue))
                indicator.setImageDrawable(spinner)
                // ImageView clears the old drawable's callback when replacing it.
                ring.callback = spinner
            }
            setProgressViewOffset(true, -dp(40), dp(56))
            setDistanceToTriggerSync(dp(72))
            setSlingshotDistance(dp(72))
        }
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun scroll(content: View) = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(content)
    }
    fun page() = column().apply {
        setPadding(dp(18), 0, dp(18), dp(24))
        clipChildren = false
        clipToPadding = false
    }
    fun pageInsets(page: View, includeTop: Boolean = false): (androidx.core.graphics.Insets) -> Unit = { insets ->
        page.setPadding(dp(18) + insets.left, if (includeTop) insets.top else 0,
            dp(18) + insets.right, dp(24) + insets.bottom)
    }
    fun text(value: CharSequence, size: Float = 14f, secondary: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setLineSpacing(0f, 1.2f)
        setTextColor(color(if (secondary) R.color.minimal_secondary else R.color.minimal_text))
    }
    fun card(parent: LinearLayout, small: Boolean = false) = column().apply {
        setBackgroundResource(if (small) R.drawable.minimal_card_small else R.drawable.minimal_card)
        elevation = (if (small) 2.5f else 5f) * context.resources.displayMetrics.density
        outlineAmbientShadowColor = color(R.color.minimal_card_shadow)
        outlineSpotShadowColor = color(R.color.minimal_card_shadow)
        if (glassCards && context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            background = HomeGlass.surface(this, whiteAlpha = 242,
                cornerRadiusDp = if (small) 14f else 18f,
                strokeWidthDp = .4f, strokeColor = color(R.color.minimal_line))
            elevation = 3f * context.resources.displayMetrics.density
            outlineAmbientShadowColor = color(R.color.minimal_glass_card_shadow)
            outlineSpotShadowColor = color(R.color.minimal_glass_card_shadow)
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    fun gap(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
    }
    fun divider(parent: LinearLayout, verticalMargin: Int = 14) {
        parent.addView(View(context).apply { setBackgroundColor(color(R.color.minimal_line)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(verticalMargin); bottomMargin = dp(verticalMargin) })
    }
    fun row(parent: LinearLayout, title: CharSequence, detail: CharSequence? = null,
            icon: Int? = null, action: (() -> Unit)? = null): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (icon != null) addView(ImageView(context).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(19), dp(19)).apply { marginEnd = dp(12) })
        val labels = column().apply {
            addView(text(title, rowTitleSize).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            if (!detail.isNullOrEmpty()) addView(text(detail, rowDetailSize, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(color(R.color.minimal_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(12) })
            isFocusable = true
            setOnClickListener { action() }
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun toggle(parent: LinearLayout, title: CharSequence, detail: CharSequence,
               checked: Boolean, change: (Boolean) -> Unit): MinimalSwitch {
        val row = row(parent, title, detail)
        return MinimalSwitch(context).apply {
            contentDescription = title
            isChecked = checked
            row.addView(this, LinearLayout.LayoutParams(dp(57), dp(31)).apply { marginStart = dp(12) })
            row.setOnClickListener { toggle() }
            setOnCheckedChangeListener { _, value -> change(value) }
        }
    }

    fun actions(parent: LinearLayout) = LinearLayout(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun button(parent: LinearLayout, title: CharSequence, action: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = dp(6)
            insetBottom = dp(6)
            cornerRadius = dp(10)
            strokeWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(color(R.color.minimal_blue))
            backgroundTintList = ColorStateList.valueOf(color(R.color.minimal_blue_soft))
            parent.addView(this, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = if (parent.childCount > 0) dp(8) else 0 })
            setOnClickListener { action() }
        }
    fun keyValue(parent: LinearLayout, label: CharSequence, value: CharSequence,
                 labelSize: Float = 12f, valueSize: Float = 12f, verticalGravity: Int = Gravity.TOP) {
        val row = LinearLayout(context).apply { gravity = verticalGravity }
        row.addView(text(label, labelSize, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(value, valueSize).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, -2, 1.5f).apply { marginStart = dp(12) })
        parent.addView(row)
    }
    fun editor(parent: LinearLayout, value: String, multiline: Boolean = false) = EditText(context).apply {
        setText(value)
        textSize = 16f
        setTextColor(color(R.color.minimal_text))
        setHintTextColor(color(R.color.minimal_secondary))
        setBackgroundResource(R.drawable.minimal_input)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if (multiline)
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_TEXT_VARIATION_URI
        if (multiline) minLines = 3 else setSingleLine(true)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    fun selector(parent: LinearLayout, title: CharSequence, choices: List<String>, selected: Int,
                 change: (Int) -> Unit) {
        var current = selected.coerceIn(0, choices.lastIndex)
        parent.addView(text(title, rowTitleSize))
        val field = LinearLayout(context).apply {
            setBackgroundResource(R.drawable.minimal_language_selector)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(48)
            isFocusable = true
            contentDescription = "$title: ${choices[current]}"
        }
        val valueText = text(choices[current], 16f).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        field.addView(valueText, LinearLayout.LayoutParams(0, -2, 1f))
        field.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down_minimal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
        parent.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val choicesAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 16f
                    setTextColor(color(if (position == current) R.color.minimal_blue else R.color.minimal_text))
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(40)
                    minimumHeight = dp(40)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = AbsListView.LayoutParams(-1, -2)
                }
        }
        val popup = androidx.appcompat.widget.ListPopupWindow(context).apply {
            anchorView = field
            isModal = true
            setOverlapAnchor(false)
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.minimal_language_selector))
            setAdapter(choicesAdapter)
            setOnItemClickListener { _, _, index, _ ->
                dismiss()
                if (index != current) {
                    current = index
                    valueText.text = choices[index]
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
            popup.verticalOffset = dp(4)
            // Bound the long country list to the space below the field, scrolling the remaining items.
            popup.height = minOf(dp(240),
                (visible.bottom - location[1] - field.height - dp(4)).coerceAtLeast(1))
            popup.show()
            popup.setSelection(current)
        }
        field.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { popup.dismiss() }
        })
    }
}
