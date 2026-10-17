package im.angry.openeuicc.ui.minimal

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.core.graphics.PathParser
import im.angry.openeuicc.common.R
import org.xmlpull.v1.XmlPullParser

/** Shared geometry and timing for the home overlay and download SIM wordmark. */
internal class UonestWordmarkDrawing(resources: Resources) {
    companion object {
        const val CYCLE_MS = 2750L
        const val COMPLETE_MS = 2500f
    }

    private val uo = PathMeasure()
    private val nest = Path()
    private val drawnUo = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 102, 214)
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }

    init {
        resources.getXml(R.drawable.uonest_wordmark).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                    val path = PathParser.createPathFromPathData(xml.getAttributeValue(
                        "http://schemas.android.com/apk/res/android", "pathData"))!!
                    uo.setPath(path, false)
                    val contours = PathMeasure(path, false)
                    while (contours.nextContour()) contours.getSegment(0f, contours.length, nest, true)
                    break
                }
            }
        }
    }

    fun draw(canvas: Canvas, width: Float, height: Float, elapsed: Float) {
        // Match the viewport and group translation of uonest_wordmark.xml.
        val scale = minOf(width / 652f, height / 148f)
        canvas.save()
        canvas.translate((width - 652f * scale) / 2f, (height - 148f * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-8f, 26f)
        val fade = (1f - (elapsed - 2500f) / 250f).coerceIn(0f, 1f)
        paint.alpha = (255 * fade).toInt()
        drawnUo.rewind()
        uo.getSegment(0f, uo.length * (elapsed / 1200f).coerceIn(0f, 1f), drawnUo, true)
        canvas.drawPath(drawnUo, paint)
        val reveal = ((elapsed - 1200f) / 600f).coerceIn(0f, 1f)
        if (reveal > 0f) {
            canvas.save()
            canvas.clipRect(228f, -26f, 228f + 425f * reveal, 122f)
            paint.alpha = (255 * fade * reveal).toInt()
            canvas.drawPath(nest, paint)
            canvas.restore()
        }
        canvas.restore()
    }
}
