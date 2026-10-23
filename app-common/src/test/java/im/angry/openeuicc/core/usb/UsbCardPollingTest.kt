package im.angry.openeuicc.core.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import im.angry.openeuicc.core.*
import im.angry.openeuicc.di.DefaultAppContainer
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class, shadows = [UsbCardPollingTest.Connection::class])
class UsbCardPollingTest {
    @Implements(UsbDeviceConnection::class)
    class Connection {
        companion object {
            var inserted = false
            var powerOns = 0
            var closes = 0
        }
        private var command = 0
        private var sequence: Byte = 0
        @Implementation fun claimInterface(iface: UsbInterface, force: Boolean) = true
        @Implementation fun close() { closes++ }
        @Implementation fun getRawDescriptors() = ByteArray(54).apply {
            this[0] = 54; this[1] = 0x21; this[6] = 1; this[40] = 8
        }
        @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray,
            offset: Int, length: Int, timeout: Int): Int {
            if (endpoint.direction == 0) {
                command = buffer[offset].toInt() and 0xFF
                sequence = buffer[offset + 6]
                if (command == 0x62) powerOns++
                return length
            }
            if (timeout == 100) return -1
            // Reproduce the real reader: even an empty slot reports present + inactive.
            val slot = command == 0x65
            val data = if (!slot && inserted) byteArrayOf(0x3B, 0) else byteArrayOf()
            val reply = byteArrayOf((if (slot) 0x81 else 0x80).toByte(),
                data.size.toByte(), 0, 0, 0, 0, sequence,
                (if (slot) 1 else if (inserted) 0 else 0x41).toByte(),
                (if (!slot && !inserted) 0xFE else 0).toByte(), 0) + data
            reply.copyInto(buffer, offset)
            return reply.size
        }
        @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray,
            length: Int, timeout: Int) = bulkTransfer(endpoint, buffer, 0, length, timeout)
    }

    @Test fun `poll detects insert removal replacement and reinsert without resetting a healthy card`() = runBlocking {
        Connection.inserted = false
        Connection.powerOns = 0
        Connection.closes = 0
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(MockEuiccChannel(0, 0))
        val app = RuntimeEnvironment.getApplication()
        val usb = shadowOf(app.getSystemService(UsbManager::class.java))
        val device = usbTestDevice()
        usb.addOrUpdateUsbDevice(device, true)
        var eid = "test-card-one"
        var supported = true
        var factoryCalls = 0
        var closedChannels = 0
        val container = object : DefaultAppContainer(app) {
            override val euiccChannelFactory = object : DefaultEuiccChannelFactory(app) {
                override fun tryOpenUsbEuiccChannel(ccidCtx: UsbCcidContext, isdrAid: ByteArray,
                    seId: EuiccChannel.SecureElementId): EuiccChannel? {
                    factoryCalls++
                    if (!supported) {
                        ccidCtx.cardProbe.recordSelectResponse(byteArrayOf(0x6A, 0x82.toByte()))
                        return null
                    }
                    val lpa = object : net.typeblog.lpac_jni.LocalProfileAssistant by MockLpa() {
                        override val eID: String get() {
                            check(Connection.inserted) { "Card removed" }
                            return eid
                        }
                    }
                    return object : EuiccChannel by MockEuiccChannel(99, 0, lpa) {
                        override val apduInterface = object : net.typeblog.lpac_jni.ApduInterface by
                            MockEuiccChannel(99, 0).apduInterface {
                            override fun <T> withLogicalChannel(aid: ByteArray,
                                cb: ((ByteArray) -> ByteArray) -> T): T = error("No vendor application")
                        }
                        override fun close() {
                            closedChannels++
                            ccidCtx.disconnect()
                        }
                    }
                }
            }
        }
        val manager = DefaultEuiccChannelManager(container, app)
        try {
            val empty = manager.pollUsbCard()
            assertEquals(UsbCardFailure.CARD_UNRESPONSIVE, empty.failure)
            assertEquals("Do not try every AID after failed activation", 1, Connection.powerOns)
            assertEquals(0, factoryCalls)
            assertEquals(1, Connection.closes)
            assertEquals(empty, manager.pollUsbCard())

            Connection.inserted = true
            val first = manager.pollUsbCard()
            assertTrue(first.canOpen)
            assertNotEquals(empty, first)
            val activations = Connection.powerOns
            repeat(3) { assertEquals(first, manager.pollUsbCard()) }
            assertEquals("Idle checks must not reset the reader", activations, Connection.powerOns)

            // A UI read keeps its channel alive even if polling runs at the same time.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val read = async(Dispatchers.Default) {
                manager.withEuiccChannel(99, 0, EuiccChannel.SecureElementId.DEFAULT) {
                    entered.complete(Unit)
                    release.await()
                    assertEquals(0, closedChannels)
                }
            }
            entered.await()
            eid = "test-card-two"
            val poll = async(Dispatchers.Default) { manager.pollUsbCard() }
            release.complete(Unit)
            read.await()
            val replacement = poll.await()
            assertTrue(replacement.canOpen)
            assertNotEquals(first.cardIdentity, replacement.cardIdentity)
            assertNotEquals(first.connectionId, replacement.connectionId)
            assertEquals(1, closedChannels)

            Connection.inserted = false
            assertEquals(empty, manager.pollUsbCard())
            assertEquals(2, closedChannels)
            Connection.inserted = true
            val reinserted = manager.pollUsbCard()
            assertTrue(reinserted.canOpen)
            assertNotEquals(replacement.connectionId, reinserted.connectionId)

            Connection.inserted = false
            manager.pollUsbCard()
            Connection.inserted = true
            supported = false
            assertEquals(UsbCardFailure.UNSUPPORTED_CARD, manager.pollUsbCard().failure)
            usb.removeUsbDevice(device)
            assertNull(manager.pollUsbCard().device)
            usb.addOrUpdateUsbDevice(device, false)
            val denied = manager.pollUsbCard()
            assertFalse(denied.canOpen)
            assertNull(denied.failure)
        } finally {
            manager.invalidate()
        }
    }
}
