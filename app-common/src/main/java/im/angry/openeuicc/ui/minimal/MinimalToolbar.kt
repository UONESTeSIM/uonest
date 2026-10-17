package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.widget.ActionMenuView
import com.google.android.material.appbar.MaterialToolbar
import im.angry.openeuicc.common.R

class MinimalToolbar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    MaterialToolbar(context, attrs) {
    private val ui = MinimalUi(context)
    var navigationIconStartOffsetDp = 0
        set(value) {
            field = value
            requestLayout()
        }

    init {
        setContentInsetsAbsolute(0, 0)
        contentInsetStartWithNavigation = 0
        contentInsetEndWithActions = 0
        setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8))
        setTitleTextAppearance(context, R.style.MinimalTitleText)
        setTitleTextColor(ui.color(R.color.minimal_text))
        minimumHeight = ui.dp(60)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is ImageButton) {
                child.layoutParams.width = ui.dp(40)
                child.layoutParams.height = ui.dp(40)
                // Match the title's vertical center within the toolbar's padded content.
                (child.layoutParams as LayoutParams).gravity = Gravity.START or Gravity.CENTER_VERTICAL
                child.setPaddingRelative(ui.dp(8 + navigationIconStartOffsetDp), ui.dp(8),
                    ui.dp(8 - navigationIconStartOffsetDp), ui.dp(8))
                child.scaleType = ImageView.ScaleType.CENTER
                child.setImageResource(R.drawable.ic_minimal_back)
                child.imageTintList = ColorStateList.valueOf(ui.color(R.color.minimal_text))
                child.background = null
            } else if (child is ActionMenuView) {
                (child.layoutParams as LayoutParams).gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        titleMarginStart = if (navigationIcon != null) ui.dp(6) else 0
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
