package im.angry.openeuicc.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsbSlotSelectionTest {
    @Test fun `inserting a reader switches from either phone slot to the reader`() {
        assertEquals(2, MainActivity.slotAfterRefresh(listOf(0, 1, 99), 0, 0, readerAdded = true))
        assertEquals(2, MainActivity.slotAfterRefresh(listOf(0, 1, 99), 1, 1, readerAdded = true))
    }

    @Test fun `ordinary refresh does not force the user back to a connected reader`() {
        assertEquals(0, MainActivity.slotAfterRefresh(listOf(0, 1, 99), 0, 0))
        assertEquals(1, MainActivity.slotAfterRefresh(listOf(0, 1, 99), 1, 1))
    }

    @Test fun `removing the selected reader returns to the last phone slot`() {
        assertEquals(1, MainActivity.slotAfterRefresh(listOf(0, 1), 99, 1))
        assertEquals(0, MainActivity.slotAfterRefresh(listOf(0, 1), 99, 0))
        assertEquals(1, MainActivity.slotAfterRefresh(listOf(0, 1), 1, 1))
    }

    @Test fun `reader stays selected while connected and missing phone slot has safe fallback`() {
        assertEquals(2, MainActivity.slotAfterRefresh(listOf(0, 1, 99), 99, 1))
        assertEquals(0, MainActivity.slotAfterRefresh(listOf(-1), 99, 1))
        assertEquals(0, MainActivity.slotAfterRefresh(listOf(0, 1), null, 1))
    }
}
