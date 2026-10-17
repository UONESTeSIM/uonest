package im.angry.openeuicc.testutil

import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.util.FakeUiccCardInfoCompat
import im.angry.openeuicc.util.FakeUiccPortInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.typeblog.lpac_jni.ApduInterface
import net.typeblog.lpac_jni.EuiccInfo2
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.LocalProfileNotification
import net.typeblog.lpac_jni.ProfileDownloadCallback
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState

/**
 * Mocks for the dependencies *below* EuiccChannelManagerService (manager →
 * channel → LPA), used by Robolectric tests that exercise the real service.
 *
 * The service itself is never mocked; tests build it with
 * Robolectric.buildService() and only swap out what the service talks to.
 */

/**
 * A LocalProfileAssistant whose downloadProfile() simulates a real download:
 * it reports the states in [preConfirmationStates] in order, then reports
 * ProfileDownloadState.ConfirmingDownload and blocks until the caller resolves
 * the confirmation (which, in production, the service does by waiting on the
 * task's back channel). The callback's return value is recorded so tests can
 * assert whether the download was confirmed or cancelled.
 *
 * By default (no pre-confirmation states) it jumps straight to
 * ConfirmingDownload, matching the historical behavior of this mock.
 *
 * Optional controls to simulate realistic downloads:
 *  - [failAfter]: throw a ProfileDownloadException right after emitting this
 *    state, simulating e.g. the SM-DP+ rejecting the device during
 *    authentication (before any metadata confirmation happens).
 *  - [holdAt]: park downloadProfile() (blocking the service's IO thread) after
 *    emitting this state until [releaseHold] is called, so tests can inspect
 *    the UI mid-download.
 */
class MockLpa(
    // Mutable so tests sharing a common setUp() can configure the mock right
    // before starting the download.
    var preConfirmationStates: List<ProfileDownloadState> = emptyList(),
    var failAfter: ProfileDownloadState? = null,
    var holdAt: ProfileDownloadState? = null,
) : LocalProfileAssistant {
    /** Completes as soon as downloadProfile() is entered (task reached the LPA). */
    val downloadStarted = CompletableDeferred<Unit>()

    /** Completes with the callback result once downloadProfile() returns. */
    val downloadReturned = CompletableDeferred<Boolean>()

    /** The input passed to downloadProfile(). */
    var downloadInput: ProfileDownloadInput? = null

    /** Completes once the given state class has been emitted to the callback. */
    private val stateReached = mutableMapOf<kotlin.reflect.KClass<out ProfileDownloadState>, CompletableDeferred<Unit>>()

    /** Released by the test to let a download parked at [holdAt] continue. */
    private val holdRelease = CompletableDeferred<Unit>()

    /** The exception thrown when [failAfter] is reached; mutable so tests can inject a realistic one. */
    var failure: LocalProfileAssistant.ProfileDownloadException = LocalProfileAssistant.ProfileDownloadException(
        lpaErrorReason = "ES10B_ERROR_REASON_UNDEFINED",
        lastHttpResponse = null,
        lastHttpException = null,
        lastApduResponse = null,
        lastApduException = null,
    )

    override fun downloadProfile(input: ProfileDownloadInput, callback: ProfileDownloadCallback) {
        downloadInput = input
        downloadStarted.complete(Unit)
        for (state in preConfirmationStates) {
            // The callback is invoked first so that the service can record the
            // progress update before the mock advances or fails.
            callback.onStatusUpdate(state)
            stateReached.getOrPut(state::class) { CompletableDeferred() }.complete(Unit)
            // ProfileDownloadState subclasses are plain classes without equals(),
            // so compare by class, not by instance.
            if (failAfter != null && state::class == failAfter!!::class) {
                throw failure
            }
            if (holdAt != null && state::class == holdAt!!::class) {
                // Park the download here (on the service's IO thread) so the test
                // can inspect intermediate UI state; the test must call releaseHold().
                kotlinx.coroutines.runBlocking { holdRelease.await() }
            }
        }
        // This blocks the caller (the service's IO thread) until the back channel
        // receives a Boolean -- exactly like a real download reaching the
        // ConfirmingDownload step.
        val result = callback.onStatusUpdate(ProfileDownloadState.ConfirmingDownload(null))
        downloadReturned.complete(result)
    }

    /** Whether the given state class has already been emitted by downloadProfile(). */
    fun isStateReached(state: ProfileDownloadState): Boolean =
        stateReached[state::class]?.isCompleted == true

    /** Releases a download parked at [holdAt]. */
    fun releaseHold() {
        holdRelease.complete(Unit)
    }

    suspend fun awaitDownloadResult(): Boolean = downloadReturned.await()

    // ---- trivial implementations for the rest of the LPA surface ----
    override val valid = true
    override val profiles = emptyList<LocalProfileInfo>()
    override val notifications = emptyList<LocalProfileNotification>()
    override val eID = "mock-eid"
    override val euiccInfo2: EuiccInfo2? = null
    override fun setEs10xMss(mss: Byte) {}
    override fun enableProfile(iccid: String, refresh: Boolean): Boolean = true
    override fun disableProfile(iccid: String, refresh: Boolean): Boolean = true
    override fun deleteProfile(iccid: String): Boolean = true
    override fun deleteNotification(seqNumber: Long): Boolean = true
    override fun handleNotification(seqNumber: Long): Boolean = true
    override fun euiccMemoryReset() {}
    override fun setNickname(iccid: String, nickname: String) {}
    override fun close() {}
}

/**
 * A EuiccChannel that always "finds" the given LPA and physical slot/port.
 */
class MockEuiccChannel(
    override val slotId: Int,
    override val portId: Int,
    override val lpa: LocalProfileAssistant = MockLpa()
) : EuiccChannel {
    override val type = "mock"
    override val port = FakeUiccPortInfoCompat(FakeUiccCardInfoCompat(slotId))
    override val logicalSlotId = slotId
    override val seId = EuiccChannel.SecureElementId.DEFAULT
    override var hasMultipleSE = false
    override val valid = true
    override val atr: ByteArray? = null

    override val apduInterface = object : ApduInterface {
        override fun connect() {}
        override fun disconnect() {}
        override fun logicalChannelOpen(aid: ByteArray): Int = 1
        override fun logicalChannelClose(handle: Int) {}
        override fun transmit(handle: Int, tx: ByteArray): ByteArray = ByteArray(0)
        override val valid = true
    }

    override val isdrAid = ByteArray(0)
    override fun close() {}
}

/**
 * A EuiccChannelManager that hands out the same [channel] for every request and
 * records how it was asked to resolve channels (slot/port pairs).
 */
class MockEuiccChannelManager(val channel: EuiccChannel) : EuiccChannelManager {
    /** Every withEuiccChannel(physical slot/port) request, in order. */
    val physicalChannelRequests = mutableListOf<Pair<Int, Int>>()

    /** Every withEuiccChannel(logical slot) request, in order. */
    val logicalChannelRequests = mutableListOf<Int>()

    override fun flowInternalEuiccPorts(): Flow<Pair<Int, Int>> =
        flowOf(Pair(channel.slotId, channel.portId))

    override fun flowAllOpenEuiccPorts(): Flow<Pair<Int, Int>> =
        flowOf(Pair(channel.slotId, channel.portId))

    override fun flowEuiccSecureElements(slotId: Int, portId: Int): Flow<EuiccChannel.SecureElementId> =
        flowOf(channel.seId)

    override suspend fun tryOpenUsbEuiccChannel(): Pair<android.hardware.usb.UsbDevice?, Boolean> =
        null to true

    override suspend fun waitForReconnect(physicalSlotId: Int, portId: Int, timeoutMillis: Long) {}

    override suspend fun findFirstAvailablePort(physicalSlotId: Int): Int = channel.portId

    override suspend fun findAvailablePorts(physicalSlotId: Int): List<Int> = listOf(channel.portId)

    override suspend fun <R> withEuiccChannel(
        physicalSlotId: Int,
        portId: Int,
        seId: EuiccChannel.SecureElementId,
        fn: suspend (EuiccChannel) -> R
    ): R {
        physicalChannelRequests += physicalSlotId to portId
        return fn(channel)
    }

    override suspend fun <R> withEuiccChannel(
        logicalSlotId: Int,
        seId: EuiccChannel.SecureElementId,
        fn: suspend (EuiccChannel) -> R
    ): R {
        logicalChannelRequests += logicalSlotId
        return fn(channel)
    }

    override fun invalidate() {}
}
