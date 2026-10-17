package im.angry.openeuicc.ui.wizard

import android.view.View
import android.view.ContextThemeWrapper
import im.angry.openeuicc.common.R
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.HttpInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class DownloadProgressViewTest {
    private fun view() = DownloadProgressView(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC))

    @Test fun `network failures offer network advice and malformed errors remain readable`() {
        val view = view()
        view.showResult(DownloadAnimationView.Result.Failure,
            LocalProfileAssistant.ProfileDownloadException("ES10B_ERROR_REASON_UNDEFINED", null,
                java.net.SocketTimeoutException(), null, null))
        assertEquals(view.context.getString(R.string.download_wizard_error_network_unreachable), view.reason.text)
        assertEquals(view.context.getString(R.string.download_failure_check_network), view.advice.text)
        for (body in listOf("", "{broken")) {
            view.showResult(DownloadAnimationView.Result.Failure,
                LocalProfileAssistant.ProfileDownloadException("ES10B_ERROR_REASON_UNDEFINED",
                    HttpInterface.HttpResponse(200, body.toByteArray()), null, null, null))
            assertEquals(view.context.getString(R.string.download_failure_unknown), view.reason.text)
            assertEquals(View.VISIBLE, view.diagnostics.visibility)
        }
    }

    @Test @Config(fontScale = 1.8f)
    fun `long failure content scrolls above home button on a compact screen`() {
        val view = view()
        view.showResult(DownloadAnimationView.Result.Failure)
        view.advice.text = "Long localized advice. ".repeat(35)
        view.diagnosticText.text = "Long diagnostic trace.\n".repeat(100)
        val density = view.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val scroll = view.getChildAt(1) as android.widget.ScrollView
        assertTrue(scroll.height > 0)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertTrue(scroll.bottom < view.diagnostics.top)
        assertTrue(view.diagnostics.bottom <= view.returnHome.top)
        val diagnosticScroll = view.diagnosticText.parent as android.widget.ScrollView
        assertTrue("Long diagnostics must remain readable by scrolling", diagnosticScroll.height > 0)
        assertTrue(view.diagnosticText.height > diagnosticScroll.height)
        view.showResult(DownloadAnimationView.Result.Success)
        assertEquals(View.GONE, view.returnHome.visibility)
        assertEquals(View.GONE, view.diagnostics.visibility)
    }
}
