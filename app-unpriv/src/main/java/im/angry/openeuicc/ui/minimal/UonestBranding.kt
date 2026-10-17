package im.angry.openeuicc.ui.minimal

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import im.angry.openeuicc.common.R

internal fun MinimalToolbar.showUonestWordmark(demo: Boolean = false) {
    val ui = MinimalUi(context)
    title = null
    val brand = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(R.drawable.uonest_wordmark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "uonest"
        }, LinearLayout.LayoutParams(ui.dp(100), ui.dp(23)))
        if (demo) addView(ui.text("· 演示", 13f, true),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
    }
    addView(brand, androidx.appcompat.widget.Toolbar.LayoutParams(-2, -2,
        Gravity.START or Gravity.CENTER_VERTICAL))
}
