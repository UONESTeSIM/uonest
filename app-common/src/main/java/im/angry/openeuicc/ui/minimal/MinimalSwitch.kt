package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.CompoundButton
import im.angry.openeuicc.common.R

/** Preview-sized switch with native checked state, keyboard and accessibility behavior. */
class MinimalSwitch(context: Context) : CompoundButton(context) {
    private val ui = MinimalUi(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buttonDrawable = null
        background = null
        isClickable = true
        isFocusable = true
        setPadding(0, 0, 0, 0)
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(ui.dp(57), widthMeasureSpec), resolveSize(ui.dp(44), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = resources.displayMetrics.density
        val left = (width - 51 * scale) / 2
        val top = (height - 28 * scale) / 2
        paint.color = ui.color(if (isChecked) R.color.minimal_blue else R.color.minimal_switch_off)
        paint.alpha = if (isEnabled) 255 else 110
        canvas.drawRoundRect(left, top, left + 51 * scale, top + 28 * scale, 14 * scale, 14 * scale, paint)
        val checkedOnRight = isChecked != (layoutDirection == LAYOUT_DIRECTION_RTL)
        val x = left + (if (checkedOnRight) 37f else 14f) * scale
        paint.color = Color.WHITE
        paint.alpha = if (isEnabled) 255 else 160
        canvas.drawCircle(x, top + 14 * scale, 12 * scale, paint)
        if (isFocused) {
            paint.color = ui.color(R.color.minimal_blue)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * scale
            canvas.drawRoundRect(left - 2 * scale, top - 2 * scale, left + 53 * scale, top + 30 * scale,
                16 * scale, 16 * scale, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
