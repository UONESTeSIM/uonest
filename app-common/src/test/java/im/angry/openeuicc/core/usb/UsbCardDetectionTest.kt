package im.angry.openeuicc.core.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise real CCID parsing, power-on and APDU setup with scripted USB wire replies. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [UsbCardDetectionTest.ScriptedConnection::class])
class UsbCardDetectionTest {
    @Implements(UsbDeviceConnection::class)
    class ScriptedConnection {
        val replies = ArrayDeque<ByteArray>()
        val commands = mutableListOf<Int>()
        var closed = false
        private var sequence: Byte = 0

        @Implementation fun getRawDescriptors() = ByteArray(54).apply {
            this[0] = 54
            this[1] = 0x21
            this[6] = 1 // T=0
            this[40] = 8 // Automatic voltage selection
        }

        @Implementation fun close() { closed = true }

        @Implementation fun bulkTransfer(
            endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int
        ): Int {
            if (endpoint.direction == 0) {
                commands.add(buffer[offset].toInt() and 0xFF)
                sequence = buffer[offset + 6]
                return length
            }
            // The real transceiver drains stale input before powering on.
            if (timeout == 100 || replies.isEmpty()) return -1
            val reply = replies.removeFirst().copyOf()
            reply[6] = sequence
            reply.copyInto(buffer, offset)
            return reply.size
        }

        @Implementation fun bulkTransfer(
            endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int
        ) = bulkTransfer(endpoint, buffer, 0, length, timeout)
    }

    private fun context(): Pair<UsbCcidContext, ScriptedConnection> {
        val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 64),
            ClassParameter.from(Int::class.javaPrimitiveType, 0)
        )
        val context = ReflectionHelpers.callConstructor(
            UsbCcidContext::class.java,
            ClassParameter.from(UsbDeviceConnection::class.java, connection),
            ClassParameter.from(UsbEndpoint::class.java, endpoint(0x81)),
            ClassParameter.from(UsbEndpoint::class.java, endpoint(0x02)),
            ClassParameter.from(Flow::class.java, flowOf(false)),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false)
        )
        return context to Shadow.extract(connection)
    }

    private fun reply(vararg data: Int, status: Int = 0) = byteArrayOf(
        0x80.toByte(), data.size.toByte(), 0, 0, 0, 0, 0, status.toByte(),
        (if (status == 0) 0 else 0xFE).toByte(), 0
    ) + data.map { it.toByte() }

    private fun slotReply(status: Int, error: Int = 0) = reply(status = status).apply {
        this[0] = 0x81.toByte()
        this[8] = error.toByte()
    }

    private fun ScriptedConnection.prepareCard() {
        replies.add(slotReply(1)) // Present, not yet activated
        replies.add(reply(0x3B, 0)) // ATR
        replies.add(reply(0x90, 0)) // Terminal capabilities
    }

    @Test fun `empty reader is detected from power on reply and closed for another detection`() {
        val (context, connection) = context()
        connection.replies.add(slotReply(1)) // Removed between status query and activation
        connection.replies.add(reply(status = 0x42))
        assertThrows(UsbCcidTransceiver.UsbCcidErrorException::class.java) {
            UsbApduInterface(context).connect()
        }
        assertEquals(UsbCardFailure.NO_CARD, context.cardProbe.result)
        context.disconnect()
        assertFalse("JNI cleanup must not close a shared context between AID attempts", connection.closed)
        context.allowDisconnect = true
        context.disconnect()
        assertTrue("Failed power-on must still release the USB connection", connection.closed)
    }

    @Test fun `empty slot is detected before a reader can misreport mute as inactive`() {
        for (status in listOf(2, 0x42)) {
            val (context, connection) = context()
            connection.replies.add(slotReply(status, if (status == 2) 0 else 0xFE))
            // This is the 2ce3:9563 empty-reader power-on reply observed on the phone.
            connection.replies.add(reply(status = 0x41))
            assertThrows(UsbCcidTransceiver.UsbCcidErrorException::class.java) {
                UsbApduInterface(context).connect()
            }
            assertEquals(UsbCardFailure.NO_CARD, context.cardProbe.result)
            assertEquals("Do not activate a card when the slot is empty", listOf(0x65), connection.commands)
        }
    }

    @Test fun `phone reader empty slot replies report unreadable card without asserting absence`() {
        val (context, connection) = context()
        // Exact GetSlotStatus and IccPowerOn replies from the user's empty 2ce3:9563 reader.
        connection.replies.add(slotReply(1).apply { this[9] = 1 })
        connection.replies.add(reply(status = 0x41))
        assertThrows(UsbCcidTransceiver.UsbCcidErrorException::class.java) {
            UsbApduInterface(context).connect()
        }
        assertEquals(UsbCardFailure.CARD_UNRESPONSIVE, context.cardProbe.result)
        assertEquals(listOf(0x65, 0x62), connection.commands)
    }

    @Test fun `hardware failure during activation must not be described as an unreadable card`() {
        val (context, connection) = context()
        connection.replies.add(slotReply(1))
        connection.replies.add(reply(status = 0x41).apply { this[8] = 0xFB.toByte() })
        assertThrows(UsbCcidTransceiver.UsbCcidErrorException::class.java) {
            UsbApduInterface(context).connect()
        }
        assertEquals(UsbCardFailure.READ_FAILED, context.cardProbe.result)
    }

    @Test fun `mute after successful activation remains a reading failure`() {
        val (context, connection) = context()
        connection.prepareCard()
        connection.replies.add(reply(status = 0x41))
        val apdu = UsbApduInterface(context)
        apdu.connect()
        assertEquals(-1, apdu.logicalChannelOpen(byteArrayOf(1)))
        assertEquals(UsbCardFailure.READ_FAILED, context.cardProbe.result)
    }

    @Test fun `invalid slot or failed status query must not imply an empty reader`() {
        for (status in listOf(slotReply(0x42, 5), slotReply(0x40), reply(status = 2))) {
            val (context, connection) = context()
            connection.replies.add(status)
            connection.replies.add(reply(0x3B, 0))
            connection.replies.add(reply(0x90, 0))
            connection.replies.add(reply(1, 0x90, 0))
            connection.replies.add(reply(0x90, 0))
            val apdu = UsbApduInterface(context)
            apdu.connect()
            assertEquals(1, apdu.logicalChannelOpen(byteArrayOf(1)))
            assertTrue(apdu.valid)
            assertTrue(connection.replies.isEmpty())
        }
    }

    @Test fun `missing eSIM application on a readable card is unsupported`() {
        val (context, connection) = context()
        connection.prepareCard()
        connection.replies.add(reply(1, 0x90, 0)) // Open channel
        connection.replies.add(reply(0x6A, 0x82)) // SELECT: application not found
        connection.replies.add(reply(0x90, 0)) // Close channel
        val apdu = UsbApduInterface(context)
        apdu.connect()
        assertEquals(-1, apdu.logicalChannelOpen(byteArrayOf(1)))
        assertEquals(UsbCardFailure.UNSUPPORTED_CARD, context.cardProbe.result)
        assertFalse(apdu.valid)
        assertTrue(connection.replies.isEmpty())
    }

    @Test fun `timeout swallowed by logical channel open retains reading failure`() {
        val (context, connection) = context()
        connection.prepareCard()
        val apdu = UsbApduInterface(context)
        apdu.connect()
        assertEquals(-1, apdu.logicalChannelOpen(byteArrayOf(1)))
        assertEquals(UsbCardFailure.READ_FAILED, context.cardProbe.result)
    }

    @Test fun `alternative AID can open normally after the first application is missing`() {
        val (context, connection) = context()
        connection.prepareCard()
        connection.replies.add(reply(1, 0x90, 0))
        connection.replies.add(reply(0x6A, 0x82))
        connection.replies.add(reply(0x90, 0))
        val first = UsbApduInterface(context)
        first.connect()
        assertEquals(-1, first.logicalChannelOpen(byteArrayOf(1)))

        connection.replies.add(reply(0x90, 0)) // Terminal capabilities; no second power-on
        connection.replies.add(reply(1, 0x90, 0))
        connection.replies.add(reply(0x90, 0))
        val second = UsbApduInterface(context)
        second.connect()
        assertEquals(1, second.logicalChannelOpen(byteArrayOf(2)))
        assertTrue(second.valid)
        assertTrue(connection.replies.isEmpty())
    }
}
