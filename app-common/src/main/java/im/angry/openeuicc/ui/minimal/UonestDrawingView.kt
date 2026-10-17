package im.angry.openeuicc.ui.minimal

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.LinearInterpolator
import im.angry.openeuicc.common.R

/** Use the same geometry as the static wordmark: its first contour is the connected uo. */
class UonestDrawingView(context: Context) : View(context) {
    private val wordmark = UonestWordmarkDrawing(resources)
    private var animator: ValueAnimator? = null
    private var elapsed = UonestWordmarkDrawing.COMPLETE_MS

    init {
        contentDescription = context.getString(R.string.task_profile_switch)
    }

    fun start() {
        stop()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        elapsed = 0f
        animator = ValueAnimator.ofFloat(0f, UonestWordmarkDrawing.CYCLE_MS.toFloat()).apply {
            duration = UonestWordmarkDrawing.CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        elapsed = UonestWordmarkDrawing.COMPLETE_MS
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wordmark.draw(canvas, width.toFloat(), height.toFloat(), elapsed)
    }
}
