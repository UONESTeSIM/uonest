package im.angry.openeuicc.testutil

import android.os.Looper
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.milliseconds

/**
 * The service runs its foreground-task machinery on Dispatchers.Main (the
 * Robolectric main looper), which is paused by default. Tests must pump the
 * looper while they wait for service-side effects; this helper idles the main
 * looper until [condition] becomes true (or [timeoutMillis] elapses).
 */
suspend fun awaitMainLooper(
    timeoutMillis: Long = 10_000,
    condition: () -> Boolean
) {
    withTimeout(timeoutMillis.milliseconds) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }
}
