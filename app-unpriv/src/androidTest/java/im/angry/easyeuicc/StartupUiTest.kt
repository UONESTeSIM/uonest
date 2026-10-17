package im.angry.easyeuicc

import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import im.angry.openeuicc.ui.QuickCompatibilityActivity
import im.angry.openeuicc.ui.UnprivilegedMainActivity
import im.angry.openeuicc.util.UnprivilegedPreferenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Hardware-dependent cases select a suitable device; never modifies a real card. */
@RunWith(AndroidJUnit4::class)
class StartupUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val passed get() = UnprivilegedPreferenceRepository(context).quickCompatibilityPassedFlow

    @Test fun successfulManualCheckStaysVisibleUntilUserReturns() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        bringAppToForeground()
        val intent = Intent(context, QuickCompatibilityActivity::class.java)
            .putExtra(QuickCompatibilityActivity.EXTRA_MANUAL_CHECK, true)
        runBlocking { passed.updatePreference(false) }
        ActivityScenario.launch<QuickCompatibilityActivity>(intent).use { scenario ->
            waitForPassedResult()
            // Wait beyond the automatic startup transition to catch accidental dismissal.
            Thread.sleep(1800)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val button = descendants(activity.window.decorView).filterIsInstance<Button>().single()
                assertTrue(button.isEnabled)
                assertEquals(activity.getString(R.string.startup_return_home), button.text.toString())
                assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() == activity.getString(R.string.startup_entering) })
                val texts = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .filter { it.isShown }.map { it.text.toString() }
                assertTrue("The original ISD-R summary row must be present",
                    activity.getString(R.string.startup_isdr) in texts)
                assertFalse("Per-slot access diagnostics were replaced by the original summary",
                    texts.any { it.startsWith("ISD-R ·") })
                val hasReaders = activity.getString(R.string.startup_not_found) !in texts
                assertTrue("Compatibility follows reader presence, matching the original app",
                    activity.getString(if (hasReaders) R.string.startup_ready else R.string.startup_usb_ready) in texts)
            }
            capture("manual-passed")
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<Button>().single().performClick()
            }
            waitForCheckExit(scenario)
        }
    }

    @Test fun successfulStartupCheckStillReturnsAutomatically() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        bringAppToForeground()
        val intent = Intent(context, QuickCompatibilityActivity::class.java)
        runBlocking { passed.updatePreference(false) }
        ActivityScenario.launch<QuickCompatibilityActivity>(intent).use { scenario ->
            waitForCheckExit(scenario)
            assertTrue(runBlocking { passed.first() })
        }
    }

    private fun bringAppToForeground() {
        // Some devices block activity launches from background instrumentation.
        runBlocking { passed.updatePreference(true) }
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${UnprivilegedMainActivity::class.java.name}"
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        waitForActivity(UnprivilegedMainActivity::class.java)
        Thread.sleep(1500)
    }

    private fun waitForCheckExit(scenario: ActivityScenario<QuickCompatibilityActivity>) {
        repeat(180) {
            if (scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED) return
            Thread.sleep(100)
        }
        fail("The check did not close after completing its return action")
    }

    private fun waitForPassedResult() {
        repeat(180) {
            if (runBlocking { passed.first() }) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(100)
        }
        fail("Expected a successful compatibility check")
    }

    @Test fun cachedPassOpensHomeButManualRecheckInvalidatesItAndFailureChecksAgain() {
        assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_SE_OMAPI_UICC))
        runBlocking { passed.updatePreference(true) }
        try {
            ActivityScenario.launch<UnprivilegedMainActivity>(Intent(context, UnprivilegedMainActivity::class.java)).use { scenario ->
                waitForActivity(UnprivilegedMainActivity::class.java)
                // Let service binding and the asynchronous preference read finish.
                Thread.sleep(1800)
                waitForActivity(UnprivilegedMainActivity::class.java)
                scenario.onActivity { activity ->
                    val menu = android.widget.PopupMenu(activity, activity.window.decorView).menu
                    val item = menu.add(0, im.angry.openeuicc.common.R.id.minimal_more_action, 0, "More")
                    assertTrue(activity.onOptionsItemSelected(item))
                }
                instrumentation.waitForIdleSync()
                capture("more-recheck")
                var node = instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(context.getString(R.string.startup_recheck))
                    .first { it.text?.toString() == context.getString(R.string.startup_recheck) }
                while (!node.isClickable) node = requireNotNull(node.parent)
                assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitForActivity(QuickCompatibilityActivity::class.java)
                waitForFailedActivity()
                assertFalse("Failed manual checks must invalidate the saved pass", runBlocking { passed.first() })
                instrumentation.runOnMainSync { resumed()!!.finish() }
                waitForHomeExit()
            }
            ActivityScenario.launch<UnprivilegedMainActivity>(Intent(context, UnprivilegedMainActivity::class.java)).use {
                waitForActivity(QuickCompatibilityActivity::class.java)
                waitForFailedActivity()
                instrumentation.runOnMainSync { resumed()!!.finish() }
                waitForHomeExit()
            }
        } finally {
            runBlocking { passed.updatePreference(false) }
        }
    }

    private fun resumed(): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull()

    private fun waitForHomeExit() {
        repeat(100) {
            var alive = false
            instrumentation.runOnMainSync {
                alive = Stage.entries.filter { it != Stage.DESTROYED }.any { stage ->
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                        .any { it is UnprivilegedMainActivity }
                }
            }
            if (!alive) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(100)
        }
        fail("Cancelling the check must close home instead of bypassing the check")
    }

    private fun waitForActivity(type: Class<out Activity>) {
        repeat(180) {
            var found = false
            instrumentation.runOnMainSync { found = type.isInstance(resumed()) }
            if (found) return
            Thread.sleep(100)
        }
        fail("Expected resumed activity ${type.simpleName}")
    }

    private fun waitForFailedActivity() {
        repeat(180) {
            var failed = false
            instrumentation.runOnMainSync {
                val activity = resumed() as? QuickCompatibilityActivity
                failed = activity != null && descendants(activity.window.decorView)
                    .filterIsInstance<Button>().singleOrNull()?.isEnabled == true
            }
            if (failed) return
            Thread.sleep(100)
        }
        fail("Failed check must stay visible with a retry button")
    }

    @Test fun failedCheckStaysOnTheCheckScreenAndRetryRestartsChecking() {
        assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_SE_OMAPI_UICC))
        ActivityScenario.launch<QuickCompatibilityActivity>(Intent(context, QuickCompatibilityActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            waitForFailure(scenario)
            assertFalse(runBlocking { passed.first() })
            capture("failed")
            scenario.onActivity { activity ->
                val button = descendants(activity.window.decorView).filterIsInstance<Button>().single()
                button.performClick()
                assertFalse(button.isEnabled)
                assertEquals(activity.getString(R.string.startup_busy), button.text.toString())
            }
            capture("checking")
            waitForFailure(scenario)
            scenario.recreate()
            waitForFailure(scenario)
        }
    }

    private fun waitForFailure(scenario: ActivityScenario<QuickCompatibilityActivity>) {
        repeat(180) {
            var ready = false
            scenario.onActivity { activity ->
                assertFalse("A failed check must not enter home", activity.isFinishing)
                val button = descendants(activity.window.decorView).filterIsInstance<Button>().single()
                ready = button.isEnabled
                if (ready) {
                    assertEquals(activity.getString(im.angry.openeuicc.common.R.string.minimal_retry), button.text.toString())
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .any { it.text.toString() == activity.getString(R.string.startup_unavailable) })
                }
            }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Compatibility check did not complete within its timeout")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "startup-verification").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
