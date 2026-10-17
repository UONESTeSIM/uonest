package im.angry.openeuicc.ui.wizard

import net.typeblog.lpac_jni.LocalProfileAssistant
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDownloadErrorTest {
    @Test fun `reader disconnect has actionable guidance instead of a generic download error`() {
        val error = LocalProfileAssistant.ProfileDownloadException(
            "USB_READER_DISCONNECTED", null, null, null, IllegalStateException("disconnected"))
        assertEquals(SimplifiedErrorMessages.UsbReaderDisconnected,
            SimplifiedErrorMessages.fromDownloadError(error))
    }
}
