package im.angry.openeuicc.core.usb

import org.junit.Assert.*
import org.junit.Test

class UsbCardProbeTest {
    private fun ccidError(status: Int) = UsbCcidTransceiver.UsbCcidErrorException(
        "CCID command failed",
        UsbCcidTransceiver.CcidDataBlock.parseHeaderFromBytes(byteArrayOf(
            0x80.toByte(), 0, 0, 0, 0, 0, 0, status.toByte(), 0xFE.toByte(), 0
        ))
    )

    private fun fail(probe: UsbCardProbe, error: Exception) {
        try {
            probe.track { throw error }
            fail("The original exception must still reach the caller")
        } catch (actual: Exception) {
            assertSame(error, actual)
        }
    }

    @Test fun `explicit absent card status survives the JNI caller discarding the exception`() {
        val probe = UsbCardProbe()
        fail(probe, ccidError(0x42))
        assertEquals(UsbCardFailure.NO_CARD, probe.result)
    }

    @Test fun `inactive card and reader timeout must not ask the user to insert a card`() {
        for (error in listOf(ccidError(0x41), ccidError(0x40), ccidError(0x43),
            UsbTransportException("Timeout"), IllegalArgumentException("Bad descriptor"))) {
            val probe = UsbCardProbe()
            fail(probe, error)
            assertEquals(UsbCardFailure.READ_FAILED, probe.result)
        }
    }

    @Test fun `unsupported requires a card response rather than just unsuccessful initialization`() {
        val probe = UsbCardProbe()
        assertEquals(UsbCardFailure.READ_FAILED, probe.result)
        probe.recordOpenChannelResponse(byteArrayOf(1, 0x90.toByte(), 0))
        probe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
        assertEquals(UsbCardFailure.UNSUPPORTED_CARD, probe.result)
    }

    @Test fun `cards explicitly rejecting logical channel support are unsupported`() {
        for (response in listOf(byteArrayOf(0x68, 0x81.toByte()), byteArrayOf(0x6A, 0x81.toByte()))) {
            val probe = UsbCardProbe()
            probe.recordOpenChannelResponse(response)
            assertEquals(UsbCardFailure.UNSUPPORTED_CARD, probe.result)
        }
    }

    @Test fun `security errors and malformed replies are reading failures`() {
        for (response in listOf(byteArrayOf(0x69, 0x82.toByte()), byteArrayOf(0x6F, 0), byteArrayOf(0))) {
            val probe = UsbCardProbe()
            probe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
            probe.recordSelectResponse(response)
            assertEquals(UsbCardFailure.READ_FAILED, probe.result)
        }
    }

    @Test fun `transport failure on another AID takes precedence over application not found`() {
        val probe = UsbCardProbe()
        probe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
        fail(probe, UsbTransportException("Disconnected"))
        assertEquals(UsbCardFailure.READ_FAILED, probe.result)
    }

    @Test fun `card removal during AID probing is reported as no card`() {
        val probe = UsbCardProbe()
        probe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
        fail(probe, ccidError(0x42))
        assertEquals(UsbCardFailure.NO_CARD, probe.result)
    }

    @Test fun `a selected alternative AID followed by invalid LPA data is not an unsupported card`() {
        val probe = UsbCardProbe()
        probe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
        probe.recordSelectResponse(byteArrayOf(0x90.toByte(), 0))
        assertEquals(UsbCardFailure.READ_FAILED, probe.result)
    }

    @Test fun `new detection does not retain the previous no card state`() {
        val previousProbe = UsbCardProbe()
        fail(previousProbe, ccidError(0x42))
        val newProbe = UsbCardProbe()
        newProbe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
        assertEquals(UsbCardFailure.UNSUPPORTED_CARD, newProbe.result)
        assertEquals("Successful operations must preserve their result", 7, newProbe.track { 7 })
    }
}
