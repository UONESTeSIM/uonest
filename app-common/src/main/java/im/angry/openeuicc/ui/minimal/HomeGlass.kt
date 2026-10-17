package im.angry.openeuicc.ui.minimal

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import im.angry.openeuicc.common.R

/** Home-only backdrop. Glass samples this artwork, never the text or swipe actions. */
object HomeGlass {
    private var artwork: Bitmap? = null

    private fun bitmap(context: Context): Bitmap {
        artwork?.let { return it }
        val options = BitmapFactory.Options().apply { inScaled = false }
        // Use the complete artwork as one image: globe, haze and waves stay together.
        return BitmapFactory.decodeResource(context.resources, R.drawable.minimal_home_globe, options)
            .also { artwork = it }
    }

    /** Fill the viewport without stretching the globe; keep its top/right placement stable. */
    internal fun backdropBounds(imageWidth: Int, imageHeight: Int, width: Int, height: Int): RectF {
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        return RectF(width - imageWidth * scale, 0f, width.toFloat(), imageHeight * scale)
    }

    fun install(activity: Activity) {
        activity.window.setBackgroundDrawable(object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            override fun draw(canvas: Canvas) {
                val image = bitmap(activity)
                val target = backdropBounds(image.width, image.height, bounds.width(), bounds.height())
                target.offset(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.drawBitmap(image, null, target, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.OPAQUE
        })
        activity.findViewById<View>(R.id.main_tabs_container).let {
            it.background = surface(it, whiteAlpha = 170, cornerRadiusDp = 25f)
        }
    }

    fun surface(view: View, whiteAlpha: Int = 235, cornerRadiusDp: Float = 14f,
        strokeWidthDp: Float = .6f, strokeColor: Int = 0xBEFFFFFF.toInt()): Drawable =
        GlassSurface(view, whiteAlpha, cornerRadiusDp, strokeWidthDp, strokeColor)

    private class GlassSurface(private val view: View, private val whiteAlpha: Int, cornerRadiusDp: Float,
        private val strokeWidthDp: Float, private val strokeColor: Int) : Drawable() {
        private val density = view.resources.displayMetrics.density
        private val radius = cornerRadiusDp * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip = Path()
        private val rect = RectF()
        private val destination = RectF()
        private val location = IntArray(2)
        private var opacity = 255
        private var blur: BackgroundBlur? = null

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            rect.set(bounds)
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            view.getLocationInWindow(location)
            val root = view.rootView
            val image = bitmap(view.context)
            destination.set(backdropBounds(image.width, image.height, root.width, root.height))
            destination.offset(-location[0].toFloat(), -location[1].toFloat())
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = opacity
            // Render only the static backdrop into a blur node. A fully drawn backdrop also
            // prevents the blue/red swipe actions bleeding through the translucent white tint.
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
                val effect = blur ?: BackgroundBlur(8f * density).also { blur = it }
                effect.draw(canvas, bitmap(view.context), destination, bounds.width(), bounds.height())
            } else {
                // Older/software renderers retain the softly textured, translucent appearance.
                canvas.drawBitmap(bitmap(view.context), null, destination, paint)
            }
            paint.color = Color.WHITE
            paint.alpha = whiteAlpha * opacity / 255
            canvas.drawRect(rect, paint)
            canvas.restoreToCount(save)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthDp * density
            paint.color = strokeColor
            paint.alpha = Color.alpha(strokeColor) * opacity / 255
            rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            canvas.drawRoundRect(rect, radius, radius, paint)

        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
            outline.alpha = opacity / 255f
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @android.annotation.TargetApi(31)
    private class BackgroundBlur(radius: Float) {
        private val node = RenderNode("Home glass backdrop").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val lastDestination = RectF()
        private var lastWidth = 0
        private var lastHeight = 0

        fun draw(canvas: Canvas, bitmap: Bitmap, destination: RectF, width: Int, height: Int) {
            if (!node.hasDisplayList() || lastDestination != destination || lastWidth != width || lastHeight != height) {
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(width, height)
                recording.drawBitmap(bitmap, null, destination, paint)
                node.endRecording()
                lastDestination.set(destination)
                lastWidth = width
                lastHeight = height
            }
            canvas.drawRenderNode(node)
        }
    }
}
