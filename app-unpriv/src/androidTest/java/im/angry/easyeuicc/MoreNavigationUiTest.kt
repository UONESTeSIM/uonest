package im.angry.easyeuicc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.UnprivilegedMainActivity
import im.angry.openeuicc.util.UnprivilegedPreferenceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Emulator-only navigation fixture; restores the startup and developer preferences. */
@RunWith(AndroidJUnit4::class)
class MoreNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun movedPagesReturnToMoreAndDeveloperUnlockIsPreserved() {
        val prefs = UnprivilegedPreferenceRepository(context)
        val passed = runBlocking { prefs.quickCompatibilityPassedFlow.first() }
        val developer = runBlocking { prefs.developerOptionsEnabledFlow.first() }
        try {
            runBlocking {
                prefs.quickCompatibilityPassedFlow.updatePreference(true)
                prefs.developerOptionsEnabledFlow.updatePreference(false)
            }
            ActivityScenario.launch<UnprivilegedMainActivity>(Intent(context, UnprivilegedMainActivity::class.java)).use { scenario ->
                await { scenario.onActivity { activity ->
                    check(activity.findViewById<View>(R.id.minimal_more_action)?.isShown == true)
                } }
                scenario.onActivity { views(it.findViewById(R.id.minimal_more_action)).first { view -> view is android.widget.ImageButton }.performClick() }
                waitFor(R.string.pref_advanced)
                instrumentation.runOnMainSync { assertNull(find(R.string.pref_developer)) }
                click(R.string.minimal_notification_settings)
                waitFor(R.string.pref_notifications_download)
                instrumentation.runOnMainSync {
                    assertNull(find(R.string.pref_advanced))
                    assertNull(find(R.string.minimal_about))
                    assertNull(find(R.string.pref_developer))
                }
                back()
                waitFor(R.string.minimal_more)
                click(R.string.pref_advanced)
                waitFor(R.string.pref_advanced_language)
                click(R.string.pref_advanced_http_proxy)
                waitFor(R.string.minimal_save)
                back()
                waitFor(R.string.pref_advanced_language)
                back()
                waitFor(R.string.minimal_more)
                click(R.string.minimal_about)
                waitFor(R.string.pref_info_app_version)
                instrumentation.runOnMainSync {
                    var version: View = requireNotNull(find(R.string.pref_info_app_version))
                    while (!version.isClickable) version = version.parent as View
                    repeat(7) { version.performClick() }
                }
                runBlocking { kotlinx.coroutines.withTimeout(5000) { prefs.developerOptionsEnabledFlow.first { it } } }
                instrumentation.runOnMainSync { assertNull(find(R.string.pref_developer)) }
                back()
                waitFor(R.string.pref_developer)
                click(R.string.pref_developer)
                waitFor(R.string.pref_developer_es10x_mss)
                back()
                waitFor(R.string.minimal_more)
                instrumentation.runOnMainSync {
                    val cards = listOf(R.string.minimal_notification_settings, R.string.pref_advanced, R.string.minimal_about, R.string.pref_developer)
                        .map { id ->
                            val label = requireNotNull(find(id))
                            ((label.parent as View).parent as View).parent
                        }
                    assertEquals("Each entry must have its own card", 4, cards.distinct().size)
                    assertNull(find(R.string.pref_settings))
                }
                val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
                File(dir, "more-navigation.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            runBlocking {
                prefs.quickCompatibilityPassedFlow.updatePreference(passed)
                prefs.developerOptionsEnabledFlow.updatePreference(developer)
            }
        }
    }

    private fun resumed(): Activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun find(id: Int): TextView? = WindowInspector.getGlobalWindowViews()
        .filter { it.hasWindowFocus() }.flatMap { views(it) }.filterIsInstance<TextView>()
        .firstOrNull { it.isShown && it.text.toString() == resumed().getString(id) }
    private fun click(id: Int) {
        instrumentation.runOnMainSync {
            var view: View = requireNotNull(find(id))
            while (!view.isClickable) view = view.parent as View
            view.performClick()
        }
        instrumentation.waitForIdleSync()
    }
    private fun back() {
        instrumentation.runOnMainSync { (resumed() as AppCompatActivity).onBackPressedDispatcher.onBackPressed() }
        instrumentation.waitForIdleSync()
    }
    private fun waitFor(id: Int) = await { instrumentation.runOnMainSync { check(find(id) != null) { "Missing ${context.getString(id)}; focused texts: ${WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.flatMap { views(it) }.filterIsInstance<TextView>().map { it.text }}" } } }
    private fun await(check: () -> Unit) {
        repeat(50) {
            if (runCatching(check).isSuccess) return
            Thread.sleep(100)
        }
        check()
    }
}
