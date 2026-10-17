package im.angry.openeuicc.ui.minimal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.swiperefreshlayout.widget.CircularProgressDrawable

/** Uses the refresh widget's existing animation clock and drag opacity. */
internal class RefreshSpinnerDrawable(
    private val progress: CircularProgressDrawable,
    private val density: Float,
    color: Int
) : Drawable(), Drawable.Callback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    init {
        progress.callback = this
    }

    override fun draw(canvas: Canvas) {
        val head = if (progress.isRunning) ((SystemClock.uptimeMillis() % 1200) / 100).toInt() else 0
        val checkpoint = canvas.save()
        canvas.translate(bounds.exactCenterX(), bounds.exactCenterY())
        for (index in 0 until 12) {
            val age = (head - index + 12) % 12
            paint.alpha = (progress.alpha * (0.18f + 0.82f * (11 - age) / 11f)).toInt()
            // Rounded caps extend the outer radius to 10dp: total visible size is 20dp.
            canvas.drawLine(0f, -5f * density, 0f, -9.25f * density, paint)
            canvas.rotate(30f)
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) { progress.alpha = alpha }
    override fun getAlpha(): Int = progress.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
