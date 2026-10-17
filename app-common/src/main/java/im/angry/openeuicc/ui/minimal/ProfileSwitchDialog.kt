package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.WindowCompat
import im.angry.openeuicc.common.R

/** Only a waiting indicator; its owner closes it after reading the real profile state. */
class ProfileSwitchDialog(context: Context) : AppCompatDialog(context, R.style.Theme_Uonest_ProfileSwitch) {
    private val ui = MinimalUi(context)
    private val logo = UonestDrawingView(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setContentView(FrameLayout(context).apply {
            addView(logo, FrameLayout.LayoutParams(ui.dp(118), ui.dp(27), Gravity.CENTER))
        })
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawable(ColorDrawable(0x80FFFFFF.toInt()))
            decorView.elevation = 0f
            WindowCompat.setDecorFitsSystemWindows(this, false)
            // The scrim must also cover the display cutout and system-bar regions.
            if (Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.CENTER)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        logo.start()
    }

    override fun onStop() {
        logo.stop()
        super.onStop()
    }
}
