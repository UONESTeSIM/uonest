package im.angry.openeuicc.ui.minimal

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.TextView
import im.angry.openeuicc.common.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class UsbReaderStatusViewTest {
    @Test fun `permission action is available only when the reader needs authorization`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC)
        val view = UsbReaderStatusView(context)
        val button = view.findViewById<Button>(R.id.usb_grant_permission)
        val text = view.findViewById<TextView>(R.id.usb_reader_text)
        var retries = 0
        view.onRetry = { retries++ }
        var requests = 0
        view.onGrantPermission = { requests++ }
        assertEquals(View.GONE, button.visibility)
        assertFalse(button.isEnabled)
        view.showState(UsbReaderStatusView.State.PERMISSION)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)
        assertEquals(context.getString(R.string.usb_permission_needed), text.text)
        button.performClick()
        assertEquals(1, requests)
        view.showState(UsbReaderStatusView.State.FAILURE)
        assertEquals(context.getString(R.string.usb_read_failed), text.text)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)
        assertEquals(context.getString(R.string.usb_detect_again), button.text)
        button.performClick()
        assertEquals(1, retries)
        assertEquals(1, requests)
        assertEquals(View.GONE, view.findViewById<View>(R.id.loading).visibility)
        view.showState(UsbReaderStatusView.State.LOADING)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.loading).visibility)
        assertFalse(button.isEnabled)
    }

    @Test @Config(qualifiers = "zh-rCN")
    fun `card states give distinct instructions and redetect without requesting permission`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC)
        val view = UsbReaderStatusView(context)
        val button = view.findViewById<Button>(R.id.usb_grant_permission)
        val text = view.findViewById<TextView>(R.id.usb_reader_text)
        var retries = 0
        view.onRetry = { retries++ }
        view.onGrantPermission = { fail("An authorized reader must not request permission again") }

        view.showState(UsbReaderStatusView.State.NO_CARD)
        assertEquals("读卡器已连接，未检测到卡片", text.text)
        assertEquals("重新检测", button.text)
        assertTrue(button.isEnabled)
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()
        assertEquals(1, retries)

        view.showState(UsbReaderStatusView.State.LOADING)
        assertEquals(View.GONE, button.visibility)
        assertFalse(button.isEnabled)
        button.performClick()
        assertEquals(1, retries)

        view.showState(UsbReaderStatusView.State.CARD_UNRESPONSIVE)
        assertEquals("读卡器已连接，未检测到可读取的卡片", text.text)
        assertEquals("重新检测", button.text)
        assertTrue(button.isEnabled)
        button.performClick()
        assertEquals(2, retries)

        view.showState(UsbReaderStatusView.State.UNSUPPORTED_CARD)
        assertEquals(context.getString(R.string.usb_unsupported_card), text.text)
        assertEquals("重新检测", button.text)
        button.performClick()
        view.showState(UsbReaderStatusView.State.FAILURE)
        assertEquals("读取卡失败，请检查卡片接触情况和读卡器连接", text.text)
        assertEquals("重新检测", button.text)
        button.performClick()
        assertEquals(4, retries)
    }
}
