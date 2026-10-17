package im.angry.openeuicc.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IsdrAccessProbeTest {
    private class Session(val open: (ByteArray) -> AutoCloseable?) : IsdrProbeSession {
        var closed = false
        override fun openChannel(aid: ByteArray) = open(aid)
        override fun close() { closed = true }
    }

    @Test fun checksOnlyTheOriginalStandardAidAndClosesChannelAndSession() = runBlocking {
        var channelClosed = false
        var attempts = 0
        val session = Session { aid ->
            attempts++
            assertEquals("A0000005591010FFFFFFFF8900000100", aid.joinToString("") { "%02X".format(it) })
            AutoCloseable { channelClosed = true }
        }
        assertTrue(probeIsdrCompatibility { session })
        assertEquals(1, attempts)
        assertTrue(channelClosed)
        assertTrue(session.closed)
    }

    @Test fun accessDenialStillListsTheSlotLikeTheOriginalApp() = runBlocking {
        val session = Session { throw SecurityException() }
        assertTrue(probeIsdrCompatibility { session })
        assertTrue(session.closed)
        assertTrue(probeIsdrCompatibility { throw SecurityException() })
    }

    @Test fun nullChannelStillListsTheSlotLikeTheOriginalApp() = runBlocking {
        val session = Session { null }
        assertTrue(probeIsdrCompatibility { session })
        assertTrue(session.closed)
    }

    @Test fun otherSelectionFailuresExcludeTheSlotButCloseTheSession() = runBlocking {
        for (error in listOf(NoSuchElementException(), IOException(), UnsupportedOperationException())) {
            val session = Session { throw error }
            assertFalse(probeIsdrCompatibility { session })
            assertTrue(session.closed)
        }
        assertFalse(probeIsdrCompatibility { throw IOException() })
    }

    @Test fun cancellationIsNotCountedAsCompatibilityEvidence() = runBlocking {
        val cancelled = CancellationException("screen closed")
        val session = Session { throw cancelled }
        try {
            probeIsdrCompatibility { session }
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
        assertTrue(session.closed)
    }

    @Test fun expiredCheckCannotProduceSuccessAndStillClosesSession() = runBlocking {
        val session = Session {
            runBlocking { delay(60) }
            null
        }
        try {
            withTimeout(20) { probeIsdrCompatibility { session } }
            fail("Timeout must propagate")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            assertTrue(session.closed)
        }
    }
}
