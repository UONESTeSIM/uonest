package im.angry.openeuicc.ui.wizard

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivationCodeInputTest {
    @Test fun directAndCarrierLinkCodesPreserveConfirmationRequirement() {
        val code = "LPA:1\$smdp.example:8443\$matching-token\$\$1"
        for (input in listOf("  $code  ", "https://carrier.example/activate?code=${Uri.encode(code)}")) {
            val parsed = parseActivationInput(input)
            assertEquals("smdp.example:8443", parsed.address)
            assertEquals("matching-token", parsed.matchingId)
            assertTrue(parsed.confirmationCodeRequired)
        }
    }

    @Test fun invalidCodesCannotAdvanceToDownload() {
        for (input in listOf("", "https://example.com", "LPA:2\$server.example\$token", "LPA:1\$bad host\$token", "LPA:1\$server.example:99999\$token")) {
            assertTrue("Reject invalid code: $input", runCatching { parseActivationInput(input) }.isFailure)
        }
    }

    @Test fun optionalMatchingIdStaysNull() {
        assertNull(parseActivationInput("1\$smdp.example").matchingId)
    }
}
