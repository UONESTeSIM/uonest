package im.angry.openeuicc.ui.minimal

import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class HomeRefreshStyleTest {
    @Test
    fun transparentIndicatorSurvivesInitializationAndRefreshReset() {
        val context = RuntimeEnvironment.getApplication()
        val refresh = SwipeRefreshLayout(context)
        refresh.addView(FrameLayout(context))
        val indicator = (0 until refresh.childCount).map { refresh.getChildAt(it) }
            .filterIsInstance<ImageView>().first()
        val progress = indicator.drawable as CircularProgressDrawable
        MinimalUi(context).styleHomeRefresh(refresh)
        assertTrue(indicator.drawable is RefreshSpinnerDrawable)
        assertSame(indicator.drawable, progress.callback)
        // Disabling and reattaching a refresh view resets the indicator background.
        refresh.isRefreshing = true
        refresh.isRefreshing = false
        refresh.isEnabled = false
        refresh.isEnabled = true
        refresh.setProgressViewOffset(true, -40, 56)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun spinnerHasTransparentCenterAnd20dpBoundsAndAdvancesWhileRefreshing() {
        val progress = CircularProgressDrawable(RuntimeEnvironment.getApplication())
        val spinner = RefreshSpinnerDrawable(progress, 2f, Color.BLUE)
        spinner.setBounds(0, 0, 80, 80)
        fun frame() = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).also {
            spinner.draw(Canvas(it))
        }
        progress.start()
        try {
            val first = frame()
            assertEquals(Color.TRANSPARENT, first.getPixel(40, 40))
            val painted = (0 until 80).flatMap { x -> (0 until 80).map { y -> x to y } }
                .filter { (x, y) -> Color.alpha(first.getPixel(x, y)) > 0 }
            assertTrue(painted.isNotEmpty())
            assertEquals(40, painted.maxOf { it.first } - painted.minOf { it.first } + 1)
            assertEquals(40, painted.maxOf { it.second } - painted.minOf { it.second } + 1)
            ShadowSystemClock.advanceBy(Duration.ofMillis(100))
            assertFalse(first.sameAs(frame()))
            ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
            assertTrue(first.sameAs(frame()))
        } finally {
            progress.stop()
        }
        val stopped = frame()
        ShadowSystemClock.advanceBy(Duration.ofMillis(150))
        assertTrue(stopped.sameAs(frame()))
    }
}
