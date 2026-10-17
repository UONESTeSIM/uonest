package im.angry.openeuicc.ui.minimal

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.doOnPreDraw
import im.angry.openeuicc.common.R

/** Position the window before it is attached; animate only around its own center. */
class ProfileEditorDialog(context: Context, theme: Int = 0) : AppCompatDialog(context, theme) {
    private var dismissing = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setWindowAnimations(0)
    }

    override fun onStart() {
        super.onStart()
        val ui = MinimalUi(context)
        window?.apply {
            setWindowAnimations(0)
            setBackgroundDrawableResource(R.drawable.minimal_card)
            setLayout(minOf(ui.dp(360), context.resources.displayMetrics.widthPixels - ui.dp(36)), -2)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.apply {
                animate().cancel()
                val animated = ValueAnimator.areAnimatorsEnabled()
                alpha = if (animated) 0f else 1f
                scaleX = if (animated) 0.92f else 1f
                scaleY = scaleX
                if (animated) doOnPreDraw {
                    pivotX = width / 2f
                    pivotY = height / 2f
                    animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(240).setInterpolator(DecelerateInterpolator()).start()
                }
            }
        }
    }

    override fun dismiss() {
        if (dismissing) return
        val decor = window?.decorView
        if (!isShowing || decor == null || !decor.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            super.dismiss()
            return
        }
        dismissing = true
        decor.animate().cancel()
        decor.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
            .setDuration(180).setInterpolator(AccelerateInterpolator()).withEndAction {
                super.dismiss()
                dismissing = false
            }.start()
    }
}
