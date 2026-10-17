package im.angry.openeuicc.util

import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class NotificationDeliveryStoreTest {
    private val item = LocalProfileNotification(42, LocalProfileNotification.Operation.Enable,
        "carrier.example", "test-iccid")

    @Test fun `automatic and manual sends keep durable results when cleanup is blocked`() = runBlocking {
        val records = mutableListOf(item.copy(seqNumber = 41))
        val requests = mutableListOf<Long>()
        var succeeds = true
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override fun deleteNotification(seqNumber: Long) = false
            override val notifications get() = records.toList()
            override fun handleNotification(seqNumber: Long): Boolean {
                requests.add(seqNumber)
                return succeeds
            }
        }
        val channel = MockEuiccChannel(0, 0, lpa)
        val manager = MockEuiccChannelManager(channel)
        val context = RuntimeEnvironment.getApplication()
        val store = NotificationDeliveryStore(context)
        manager.beginTrackedOperation(0, 0, channel.seId, store) { records.add(item); true }
        assertEquals(listOf(42L), requests)
        val reopened = NotificationDeliveryStore(context)
        assertEquals(true, reopened.results(channel, records)[42])
        assertNull(reopened.results(channel, records)[41])
        succeeds = false
        assertFalse(reopened.send(channel, item))
        assertEquals(false, NotificationDeliveryStore(context).results(channel, records)[42])
        succeeds = true
        assertTrue(store.send(channel, item))
        assertEquals(true, reopened.results(channel, records)[42])
        store.remove(channel, item)
        assertNull(reopened.results(channel, records)[42])
    }

    @Test fun `automatic and manual successes delete only delivered records`() = runBlocking {
        val unknown = item.copy(seqNumber = 41)
        val records = mutableListOf(unknown)
        val requests = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        var succeeds = true
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override val notifications get() = records.toList()
            override fun handleNotification(seqNumber: Long): Boolean {
                requests.add(seqNumber)
                return succeeds
            }
            override fun deleteNotification(seqNumber: Long): Boolean {
                deleted.add(seqNumber)
                return records.removeAll { it.seqNumber == seqNumber }
            }
        }
        val channel = MockEuiccChannel(0, 0, lpa)
        val store = NotificationDeliveryStore(RuntimeEnvironment.getApplication())
        val manager = MockEuiccChannelManager(channel)
        manager.beginTrackedOperation(0, 0, channel.seId, store) { records.add(item); true }
        assertEquals(listOf(42L), requests)
        assertEquals(listOf(42L), deleted)
        assertEquals(listOf(unknown), records)
        assertNull(store.results(channel, listOf(item))[42])
        val retry = item.copy(seqNumber = 43)
        records.add(retry)
        succeeds = false
        assertFalse(store.send(channel, retry))
        assertEquals(listOf(unknown, retry), store.removeDelivered(channel, records.toList()))
        assertEquals(false, NotificationDeliveryStore(RuntimeEnvironment.getApplication()).results(channel, records)[43])
        assertEquals(listOf(42L), deleted)
        succeeds = true
        assertTrue(store.send(channel, retry))
        assertEquals(listOf(42L, 43L), deleted)
        assertEquals(listOf(unknown), records)
        assertNull(store.results(channel, records)[41])
    }

    @Test fun `cleanup retries saved successes without resending or deleting failures and unknowns`() = runBlocking {
        val unknown = item.copy(seqNumber = 41)
        val failed = item.copy(seqNumber = 43)
        val records = mutableListOf(unknown, item, failed)
        val requests = mutableListOf<Long>()
        var deleteFails = true
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override val notifications get() = records.toList()
            override fun handleNotification(seqNumber: Long): Boolean {
                requests.add(seqNumber)
                return seqNumber == 42L
            }
            override fun deleteNotification(seqNumber: Long): Boolean {
                assertEquals("Only a confirmed success may be deleted", 42L, seqNumber)
                if (deleteFails) throw java.io.IOException("Card temporarily unavailable")
                return records.removeAll { it.seqNumber == seqNumber }
            }
        }
        val channel = MockEuiccChannel(0, 0, lpa)
        val context = RuntimeEnvironment.getApplication()
        val store = NotificationDeliveryStore(context)
        assertTrue("Cleanup failure must not change the successful send result", store.send(channel, item))
        assertFalse(store.send(channel, failed))
        assertEquals(true, store.results(channel, records)[42])
        assertEquals(listOf(unknown, item, failed), store.removeDelivered(channel, records.toList()))
        deleteFails = false
        val reopened = NotificationDeliveryStore(context)
        assertEquals(listOf(unknown, failed), reopened.removeDelivered(channel, records.toList()))
        assertEquals(listOf(42L, 43L), requests)
        assertEquals(listOf(unknown, failed), records)
        assertNull(reopened.results(channel, listOf(item))[42])
        assertEquals(false, reopened.results(channel, records)[43])
        assertNull(reopened.results(channel, records)[41])
    }

    @Test fun `result follows chip between slots but never leaks to another chip or notification`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = NotificationDeliveryStore(context)
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override fun deleteNotification(seqNumber: Long) = false
            override fun handleNotification(seqNumber: Long) = true
        }
        val first = MockEuiccChannel(0, 0, lpa)
        store.send(first, item)
        assertEquals(true, store.results(MockEuiccChannel(1, 0, lpa), listOf(item))[42])
        val otherChip = MockEuiccChannel(0, 0, object : LocalProfileAssistant by lpa {
            override val eID = "another-eid"
        })
        assertNull(store.results(otherChip, listOf(item))[42])
        val otherSe = object : EuiccChannel by first {
            override val seId = EuiccChannel.SecureElementId.createFromInt(1)
        }
        assertNull(store.results(otherSe, listOf(item))[42])
        for (changed in listOf(item.copy(iccid = "other-iccid"),
            item.copy(notificationAddress = "other.example"),
            item.copy(profileManagementOperation = LocalProfileNotification.Operation.Disable))) {
            assertNull(store.results(first, listOf(changed))[42])
        }
    }

    @Test fun `automatic exceptions persist failure and cancellation never keeps stale success`() = runBlocking {
        var mode = "success"
        val records = mutableListOf<LocalProfileNotification>()
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override fun deleteNotification(seqNumber: Long) = false
            override val notifications get() = records.toList()
            override fun handleNotification(seqNumber: Long): Boolean = when (mode) {
                "failure" -> throw java.io.IOException("Connection lost")
                "cancel" -> throw CancellationException()
                else -> true
            }
        }
        val channel = MockEuiccChannel(0, 0, lpa)
        val store = NotificationDeliveryStore(RuntimeEnvironment.getApplication())
        val manager = MockEuiccChannelManager(channel)
        mode = "failure"
        manager.beginTrackedOperation(0, 0, channel.seId, store) { records.add(item); true }
        assertEquals(false, store.results(channel, records)[42])
        mode = "success"
        store.send(channel, item)
        mode = "cancel"
        try { store.send(channel, item); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertNull(store.results(channel, records)[42])
    }
}
