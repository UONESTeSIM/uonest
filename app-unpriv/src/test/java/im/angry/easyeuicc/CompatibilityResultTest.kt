package im.angry.easyeuicc

import im.angry.openeuicc.ui.QuickCompatibilityFragment.Companion.Compatibility
import im.angry.openeuicc.ui.QuickCompatibilityFragment.Companion.CompatibilityResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityResultTest {
    @Test fun readerPresenceIsEnoughForOriginalDeviceCompatibilityEvenWithoutIsdrResults() {
        val result = CompatibilityResult(slotsOmapi = listOf("SIM 0", "SIM 1"))
        assertEquals(Compatibility.COMPATIBLE, result.compatibility)
        assertEquals(emptyList<String>(), result.slotsIsdr)
    }

    @Test fun noReadersMeansNoInternalDeviceCompatibility() {
        assertEquals(Compatibility.NOT_COMPATIBLE, CompatibilityResult().compatibility)
    }
}
