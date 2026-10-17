package im.angry.openeuicc.ui.wizard

import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import org.junit.Assert.*
import org.junit.Test

class DownloadSlotTest {
    @Test fun `slot and secure element encoding stays compatible with saved download targets`() {
        for (slot in listOf(0, 1, EuiccChannelManager.USB_CHANNEL_ID)) {
            for (element in listOf(0, 1, 255)) {
                val se = EuiccChannel.SecureElementId.createFromInt(element)
                val encoded = encodeSyntheticSlotId(slot, se)
                assertEquals((slot shl 16) + element, encoded)
                assertEquals(slot to se, decodeSyntheticSlotId(encoded))
            }
        }
    }

    @Test fun `shared server validation still rejects malformed addresses and ports`() {
        for (server in listOf("smdp.example", "smdp.example:443", "smdp.example:8443"))
            assertTrue(server, isValidAddress(server))
        for (server in listOf("", "host", "bad host.example", "-bad.example", "smdp.example:0", "smdp.example:65536"))
            assertFalse(server, isValidAddress(server))
    }
}
