package im.angry.openeuicc.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import com.google.android.material.R
import com.google.android.material.tabs.TabLayout

/**
 * A TabLayout that automatically switches to MODE_SCROLLABLE when
 * child tabs overflow the full width of the layout.
 */
class DynamicModeTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.tabStyle
) : TabLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        updateModeIfNecessary()
        // Restore the constrained measurements after probing the tabs' natural widths.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateModeIfNecessary() {
        if (measuredWidth <= 0 || tabCount == 0) return

        val tabStrip = getChildAt(0) as? ViewGroup ?: return
        val totalTabWidth = (0 until tabStrip.childCount).sumOf { index ->
            val tabView = tabStrip.getChildAt(index)
            val layoutParams = tabView.layoutParams as? MarginLayoutParams
            tabView.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            tabView.measuredWidth + (layoutParams?.leftMargin ?: 0) + (layoutParams?.rightMargin ?: 0)
        }

        val availableWidth = measuredWidth - paddingLeft - paddingRight
        val shouldScroll = totalTabWidth > availableWidth
        val targetMode = if (shouldScroll) MODE_SCROLLABLE else MODE_FIXED
        val targetGravity = if (shouldScroll) GRAVITY_START else GRAVITY_FILL

        if (tabMode != targetMode) {
            tabMode = targetMode
        }

        if (tabGravity != targetGravity) {
            tabGravity = targetGravity
        }
    }
}
