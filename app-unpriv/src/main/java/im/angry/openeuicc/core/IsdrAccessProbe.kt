package im.angry.openeuicc.core

import im.angry.openeuicc.util.EUICC_DEFAULT_ISDR_AID
import im.angry.openeuicc.util.decodeHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface IsdrProbeSession : AutoCloseable {
    fun openChannel(aid: ByteArray): AutoCloseable?
}

/** Matches the original device compatibility heuristic, not card access authorization. */
internal suspend fun probeIsdrCompatibility(openSession: () -> IsdrProbeSession): Boolean {
    var session: IsdrProbeSession? = null
    return try {
        currentCoroutineContext().ensureActive()
        session = openSession()
        // The original check only selects the standard AID and includes null channels.
        session.openChannel(EUICC_DEFAULT_ISDR_AID.decodeHex())?.close()
        currentCoroutineContext().ensureActive()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        // Original EasyEUICC counts access denial as device compatibility evidence.
        currentCoroutineContext().ensureActive()
        true
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        false
    } finally {
        // Release our session without changing the outcome of the original SELECT check.
        runCatching { session?.close() }
    }
}
