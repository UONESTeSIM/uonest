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
        assertEquals(context.getString(R.string.usb_failed), text.text)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)
        assertEquals(context.getString(R.string.usb_retry), button.text)
        button.performClick()
        assertEquals(1, retries)
        assertEquals(1, requests)
        assertEquals(View.GONE, view.findViewById<View>(R.id.loading).visibility)
        view.showState(UsbReaderStatusView.State.LOADING)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.loading).visibility)
        assertFalse(button.isEnabled)
    }
}
