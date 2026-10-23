package im.angry.openeuicc.service

import android.content.Intent
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService.ForegroundTaskState
import im.angry.openeuicc.testutil.MockEuiccChannel
import im.angry.openeuicc.testutil.MockEuiccChannelManager
import im.angry.openeuicc.testutil.MockLpa
import im.angry.openeuicc.testutil.TestOpenEuiccApplication
import im.angry.openeuicc.testutil.awaitMainLooper
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the real EuiccChannelManagerService (Robolectric) with only the manager /
 * channel / LPA mocked -- i.e. the exact service that runs in production.
 *
 * These tests pin the profile-download confirmation contract that regressed
 * during the "merge back-communication channels" refactor: the task MUST block
 * at ProfileDownloadState.ConfirmingDownload until a Boolean is sent on the
 * handle's back channel. `true` continues the download, `false` cancels it.
 * UI callers are expected to send that signal through launchProfileDownload()
 * (see DownloadTaskLauncherTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class EuiccChannelManagerServiceTest {

    private val seId = EuiccChannel.SecureElementId.DEFAULT
    private val input = ProfileDownloadInput(
        address = "smdp.example.com",
        matchingId = "matching-id",
        imei = "imei",
        confirmationCode = "conf-code"
    )

    private lateinit var service: EuiccChannelManagerService
    private lateinit var lpa: MockLpa

    private suspend fun switchWithLostReply(enable: Boolean, actualEnabled: Boolean,
                                           replacedChip: Boolean = false): ForegroundTaskState.Done {
        var connected = true
        var reconnected = false
        var switchCalls = 0
        val switchingLpa = object : LocalProfileAssistant by MockLpa() {
            override val eID get() = if (reconnected && replacedChip) "other-chip" else "original-chip"
            override val profiles get(): List<LocalProfileInfo> {
                check(reconnected) { "Must reconnect before checking the actual state" }
                return listOf(LocalProfileInfo("target", if (actualEnabled) LocalProfileInfo.State.Enabled
                    else LocalProfileInfo.State.Disabled, "Test", "", "", "", ProfileClass.Operational))
            }
            override fun enableProfile(iccid: String, refresh: Boolean): Boolean {
                switchCalls++
                connected = false
                return false
            }
            override fun disableProfile(iccid: String, refresh: Boolean) = enableProfile(iccid, refresh)
        }
        val channel = object : EuiccChannel by MockEuiccChannel(1, 2, switchingLpa) {
            override val valid get() = connected
        }
        val manager = object : EuiccChannelManager by MockEuiccChannelManager(channel) {
            override suspend fun waitForReconnect(physicalSlotId: Int, portId: Int, timeoutMillis: Long) {
                assertEquals(1, physicalSlotId)
                assertEquals(2, portId)
                connected = true
                reconnected = true
            }
        }
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
        service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        val handle = service.launchProfileSwitchTask(1, 2, seId, "target", enable, 100)
        startService()
        val done = awaitTaskDone(handle)
        assertTrue(reconnected)
        assertEquals("A lost reply must not cause a duplicate switch on a disconnected channel", 1, switchCalls)
        return done
    }

    @Test fun `lost enable reply succeeds only after reconnect confirms the enabled profile`() = runBlocking {
        assertNull(switchWithLostReply(true, true).error)
    }

    @Test fun `lost disable reply succeeds only after reconnect confirms the disabled profile`() = runBlocking {
        assertNull(switchWithLostReply(false, false).error)
    }

    @Test fun `lost reply with unchanged profile remains a failure`() = runBlocking {
        assertNotNull(switchWithLostReply(true, false).error)
    }

    @Test fun `replacement chip must never confirm the original switch`() = runBlocking {
        assertNotNull(switchWithLostReply(true, true, replacedChip = true).error)
    }

    @Before
    fun setUp() {
        val channel = MockEuiccChannel(slotId = 1, portId = 2)
        lpa = channel.lpa as MockLpa
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(channel)
        service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
    }

    /**
     * The service self-starts via startForegroundService(); Robolectric does not
     * deliver that to onStartCommand() on its own, so simulate the system call.
     * Must be invoked after the launch coroutine has had a chance to subscribe to
     * foregroundStarted (otherwise the emission is dropped -- foregroundStarted
     * has no replay). In production the system delivers the start asynchronously,
     * so the subscriber is always ready by then.
     */
    private fun startService() {
        shadowOf(Looper.getMainLooper()).idle()
        service.onStartCommand(Intent(), 0, 1)
    }

    private suspend fun awaitTaskDone(
        handle: EuiccChannelManagerService.ForegroundTaskHandle
    ): ForegroundTaskState.Done = coroutineScope {
        val states = java.util.concurrent.CopyOnWriteArrayList<ForegroundTaskState>()
        val collector = async(Dispatchers.Default) { handle.stateFlow.collect { states += it } }
        try {
            awaitMainLooper {
                states.any { it is ForegroundTaskState.Done }
            }
        } finally {
            collector.cancel()
        }
        states.last() as ForegroundTaskState.Done
    }

    @Test
    fun `download task blocks at ConfirmingDownload until the subscriber sends true`() = runBlocking {
        val handle = service.launchProfileDownloadTask(1, 2, seId, input)
        startService()

        // The task must reach the LPA and stall at the confirmation step.
        awaitMainLooper { lpa.downloadStarted.isCompleted }
        assertFalse(
            "download must be waiting for confirmation",
            lpa.downloadReturned.isCompleted
        )

        // A subscriber (e.g. DownloadWizardProgressFragment) confirms via the back channel.
        handle.backChannel.send(true)

        // The LPA callback must have been told to continue.
        assertEquals(true, lpa.awaitDownloadResult())

        // The task must then complete successfully.
        val done = awaitTaskDone(handle)
        assertNull(done.error)
    }

    @Test
    fun `download task cancels when the subscriber sends false`() = runBlocking {
        val handle = service.launchProfileDownloadTask(1, 2, seId, input)
        startService()

        awaitMainLooper { lpa.downloadStarted.isCompleted }
        assertFalse(
            "download must be waiting for confirmation",
            lpa.downloadReturned.isCompleted
        )

        // A subscriber decides to cancel.
        handle.backChannel.send(false)

        // The LPA callback must have been told to cancel.
        assertEquals(false, lpa.awaitDownloadResult())

        // The task itself still completes (the cancellation is communicated via the
        // callback result, not an exception).
        val done = awaitTaskDone(handle)
        assertNull(done.error)
    }

    @Test
    fun `USB polling yields to downloads and cannot reopen a reader while the task is running`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var polls = 0
        val base = MockEuiccChannelManager(MockEuiccChannel(1, 2, lpa))
        TestOpenEuiccApplication.mockEuiccChannelManager = object : EuiccChannelManager by base {
            override suspend fun pollUsbCard(): EuiccChannelManager.UsbChannelOpenResult {
                polls++
                if (polls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return EuiccChannelManager.UsbChannelOpenResult(null, false)
            }
        }
        service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        val probe = async(Dispatchers.Default) { service.pollUsbCard() }
        entered.await()
        val handle = service.launchProfileDownloadTask(1, 2, seId, input)
        startService()
        try {
            assertFalse("A new download must wait for an in-flight probe", lpa.downloadStarted.isCompleted)
            assertNull("A reserved foreground task prevents another probe", service.pollUsbCard())
            release.complete(Unit)
            probe.await()
            awaitMainLooper { lpa.downloadStarted.isCompleted }
            assertNull("Do not probe while download is waiting for confirmation", service.pollUsbCard())
            assertEquals(1, polls)
            handle.backChannel.send(false)
            assertNull(awaitTaskDone(handle).error)
            assertNotNull(service.pollUsbCard())
            assertEquals(2, polls)
        } finally {
            release.complete(Unit)
            handle.backChannel.trySend(false)
        }
    }
}
