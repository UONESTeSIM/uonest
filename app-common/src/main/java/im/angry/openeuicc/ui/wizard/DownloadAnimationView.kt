package im.angry.openeuicc.ui.wizard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.UonestWordmarkDrawing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

/** Decorative motion only: completion is always supplied by the download service. */
class DownloadAnimationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Result { Downloading, Success, Failure }

    var result = Result.Downloading
        private set
    val isAnimating: Boolean get() = animator.isRunning
    internal var simTopOverride: Float? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.minimal_blue)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glass = BitmapFactory.decodeResource(resources, R.drawable.download_glass_sim)
    private val failedGlassFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    private val wordmark = UonestWordmarkDrawing(resources)
    private val check = Path().apply { moveTo(-10f, 0f); lineTo(-3f, 7f); lineTo(11f, -7f) }
    private val checkMeasure = PathMeasure(check, false)
    private val revealedCheck = Path()
    private var checkProgress = 1f
    private var checkPending = false
    private val checkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400
        interpolator = LinearInterpolator()
        addUpdateListener { checkProgress = it.animatedValue as Float; invalidate() }
    }
    private val fracture = Path().apply {
        moveTo(-5f, -50f); lineTo(-5f, -28f); lineTo(4f, -17f)
        lineTo(-4f, -4f); lineTo(6f, 10f); lineTo(-2f, 22f); lineTo(2f, 50f)
    }
    private val fractureLeft = Path(fracture).apply {
        lineTo(-80f, 50f); lineTo(-80f, -50f); close()
    }
    private val fractureRight = Path(fracture).apply {
        lineTo(80f, 50f); lineTo(80f, -50f); close()
    }
    private val fractureEdge = Path().apply {
        moveTo(-5f, -33f); lineTo(-5f, -28f); lineTo(4f, -17f)
        lineTo(-4f, -4f); lineTo(6f, 10f); lineTo(-2f, 22f); lineTo(-1f, 29f)
    }
    private val orbit = RectF(-114f, -114f, 114f, 114f)
    private val glow = RadialGradient(0f, 0f, 122f,
        intArrayOf(0x2467B5FF, 0x1267B5FF, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val orbitGradient = SweepGradient(0f, 0f,
        intArrayOf(0x006BB8FF, 0x606BB8FF, 0xFF258BFA.toInt()), floatArrayOf(0f, .72f, 1f))
    private val orbitDotGlass = RadialGradient(-4f, -5f, 22f,
        intArrayOf(0xD9FFFFFF.toInt(), 0x28E5F4FF, 0x708AC4FF),
        floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
    private val orbitDotEdge = SweepGradient(0f, 0f,
        intArrayOf(0x809CCFFF.toInt(), 0x509CCFFF, 0xF5FFFFFF.toInt(),
            0xF5FFFFFF.toInt(), 0x809CCFFF.toInt()),
        floatArrayOf(0f, .25f, .55f, .8f, 1f))
    private val orbitDotCore = RadialGradient(-1.5f, -2f, 8f,
        intArrayOf(0xFF9CE4FF.toInt(), 0xFF258BFA.toInt(), 0xFF0066D6.toInt()),
        null, Shader.TileMode.CLAMP)

    internal fun artworkHeight(viewWidth: Int, viewHeight: Int): Float =
        (if (result == Result.Failure) 100f else 244f) * drawingUnit(viewWidth, viewHeight)

    internal fun artworkContentBottom(viewWidth: Int, viewHeight: Int): Float =
        if (result == Result.Failure)
            // Visible lower edge of the separated glass halves, excluding transparent margins.
            (50f + 32f * .92f) * drawingUnit(viewWidth, viewHeight)
        else artworkHeight(viewWidth, viewHeight)

    private fun drawingUnit(viewWidth: Int, viewHeight: Int) =
        .9f * min(density, min(viewWidth / 280f, viewHeight / 440f))
    private var elapsed = 0f
    private var active = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 60_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { elapsed = it.currentPlayTime.toFloat(); invalidate() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = context.getString(R.string.download_wizard_progress)
    }

    fun showResult(value: Result) {
        if (result != value) {
            checkAnimator.cancel()
            checkPending = value == Result.Success
            checkProgress = if (checkPending) 0f else 1f
        }
        result = value
        contentDescription = context.getString(when (value) {
            Result.Downloading -> R.string.download_wizard_progress
            Result.Success -> R.string.download_animation_success
            Result.Failure -> R.string.task_profile_download_failure
        })
        updateAnimator()
        invalidate()
    }

    fun setActive(value: Boolean) {
        active = value
        updateAnimator()
    }

    private fun updateAnimator() {
        val canAnimate = active && isAttachedToWindow &&
            windowVisibility == VISIBLE && isShown && ValueAnimator.areAnimatorsEnabled()
        val shouldRun = canAnimate && result == Result.Downloading
        if (shouldRun && !animator.isStarted) animator.start()
        else if (!shouldRun) animator.cancel()
        if (result == Result.Success) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                finishCheck()
            } else if (canAnimate && checkPending) {
                checkPending = false
                checkAnimator.start()
            } else if (!canAnimate && checkAnimator.isStarted) {
                // Returning to the page must not replay a completed result.
                finishCheck()
            }
        }
    }

    private fun finishCheck() {
        checkAnimator.cancel()
        checkPending = false
        checkProgress = 1f
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimator() }
    override fun onDetachedFromWindow() {
        animator.cancel()
        if (checkAnimator.isStarted) finishCheck()
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = drawingUnit(width, height)
        val artworkHeight = artworkHeight(width, height)
        val top = simTopOverride ?: (height - artworkHeight) / 2f
        canvas.save()
        canvas.translate(width / 2f, top + artworkHeight / 2f)
        canvas.scale(unit, unit)
        val failed = result == Result.Failure
        if (!failed) {
            paint.style = Paint.Style.FILL
            paint.shader = glow
            canvas.drawCircle(0f, 0f, 122f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.color = 0x2067B5FF
            paint.strokeWidth = 1f
            canvas.drawCircle(0f, 0f, 90f, paint)
            paint.color = 0x2467B5FF
            paint.strokeWidth = 4f
            canvas.drawOval(orbit, paint)
            paint.color = 0xDDFFFFFF.toInt()
            paint.strokeWidth = .8f
            canvas.drawCircle(0f, 0f, 117f, paint)
            if (result == Result.Downloading) {
                // An indeterminate orbit, never a percentage of the real download.
                val angle = (elapsed % 4200f) / 4200f * 360f - 90f
                canvas.save()
                canvas.rotate(angle)
                paint.shader = orbitGradient
                paint.strokeWidth = 4f
                canvas.drawArc(orbit, 0f, 359f, false, paint)
                paint.shader = null
                canvas.restore()
                val radians = angle * Math.PI / 180.0
                val x = cos(radians).toFloat() * 114f
                val y = sin(radians).toFloat() * 114f
                canvas.save()
                canvas.translate(x, y)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.shader = orbitDotGlass
                canvas.drawCircle(0f, 0f, 13f, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .8f
                paint.shader = orbitDotEdge
                canvas.drawCircle(0f, 0f, 12.6f, paint)
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.shader = orbitDotCore
                canvas.drawCircle(0f, 0f, 4.5f, paint)
                paint.shader = null
                canvas.restore()
            }
        }
        // Keep the generated glass separate from the vector brand / result glyph.
        canvas.save()
        canvas.scale(.92f, .92f)
        val halfWidth = if (failed) 66f else 76f
        val halfHeight = halfWidth * glass.height / glass.width
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.isFilterBitmap = true
        val glassBounds = RectF(-halfWidth, -halfHeight, halfWidth, halfHeight)
        if (failed) drawFracturedGlass(canvas, glassBounds)
        else canvas.drawBitmap(glass, null, glassBounds, paint)
        when (result) {
            Result.Downloading -> {
                canvas.save()
                // The glass face is slightly right/above the bitmap center because
                // of its transparent shadow margins. Align the complete wordmark
                // to the face and keep that anchor fixed throughout the reveal.
                canvas.translate(-46f, -13.5f)
                val wordmarkTime = if (ValueAnimator.areAnimatorsEnabled())
                    elapsed % UonestWordmarkDrawing.CYCLE_MS else UonestWordmarkDrawing.COMPLETE_MS
                wordmark.draw(canvas, 96f, 22f, wordmarkTime)
                canvas.restore()
            }
            Result.Success -> {
                paint.style = Paint.Style.STROKE
                paint.color = blue
                paint.strokeWidth = 3.5f
                revealedCheck.rewind()
                checkMeasure.getSegment(0f, checkMeasure.length * checkProgress, revealedCheck, true)
                canvas.drawPath(revealedCheck, paint)
            }
            Result.Failure -> Unit
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawFracturedGlass(canvas: Canvas, bounds: RectF) {
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(side * 4f, 0f)
            canvas.rotate(side * 3f)
            canvas.clipPath(if (side < 0) fractureLeft else fractureRight)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.colorFilter = failedGlassFilter
            canvas.drawBitmap(glass, null, bounds, paint)
            paint.colorFilter = null
            // The cut faces catch light like the existing glass perimeter.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xFFB0B0B0.toInt()
            canvas.drawPath(fractureEdge, paint)
            paint.strokeWidth = .8f
            paint.color = Color.WHITE
            canvas.drawPath(fractureEdge, paint)
            canvas.restore()
        }
    }
}
