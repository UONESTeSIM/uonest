package im.angry.openeuicc.ui.minimal

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeGlassTest {
    @Test fun `globe stays circular on tall wide and landscape viewports without blank edges`() {
        for ((width, height) in listOf(1200 to 2670, 1080 to 2400, 1600 to 2560, 2400 to 1080)) {
            val bounds = HomeGlass.backdropBounds(937, 1678, width, height)
            assertEquals("Both axes must use the same scale", bounds.width() / 937f,
                bounds.height() / 1678f, 0.0001f)
            assertTrue(bounds.left <= 0.001f)
            assertEquals(0f, bounds.top, 0.001f)
            assertEquals(width.toFloat(), bounds.right, 0.001f)
            assertTrue(bounds.bottom >= height - 0.001f)
        }
    }
}
