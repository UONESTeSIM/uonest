package im.angry.easyeuicc

import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import im.angry.openeuicc.ui.UnprivilegedMainActivity
import im.angry.openeuicc.util.UnprivilegedPreferenceRepository
import im.angry.openeuicc.util.activeModemCountCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import im.angry.openeuicc.common.R as CommonR

/** Runs on a phone that has already passed startup detection and has no accessible eUICC. */
@RunWith(AndroidJUnit4::class)
class EmptySlotsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun emptySlotsCanBeSelectedAndRefreshedWithoutShowingDownload() {
        val context = instrumentation.targetContext
        assumeTrue(runBlocking { UnprivilegedPreferenceRepository(context).quickCompatibilityPassedFlow.first() })
        val slotCount = context.getSystemService(android.telephony.TelephonyManager::class.java).activeModemCountCompat
        assumeTrue(slotCount >= 2)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${context.packageName}/${UnprivilegedMainActivity::class.java.name}"
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        waitForHome()
        onHome { activity ->
            val tabs = activity.findViewById<TabLayout>(CommonR.id.main_tabs)
            assertTrue("Empty slots must remain selectable", tabs.isShown)
            assertEquals(slotCount, tabs.tabCount)
            repeat(slotCount) { index ->
                assertEquals(activity.getString(R.string.channel_name_format_unpriv, index + 1), tabs.getTabAt(index)!!.text)
            }
        }
        repeat(slotCount) { index ->
            onHome { it.findViewById<TabLayout>(CommonR.id.main_tabs).getTabAt(index)!!.select() }
            waitForHome(index)
            Thread.sleep(350) // Allow the selected background to reach the captured frame.
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                val file = java.io.File(context.getExternalFilesDir(null), "slot-${index + 1}.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            onHome { activity ->
                val tabs = activity.findViewById<TabLayout>(CommonR.id.main_tabs)
                val selected = tabs.getTabAt(index)!!.customView!!
                assertTrue("The current slot must have the selected background", selected.isSelected)
                val tabLocation = IntArray(2).also { tabs.getLocationOnScreen(it) }
                val selectedLocation = IntArray(2).also { selected.getLocationOnScreen(it) }
                assertTrue("The selected slot must keep its left inset",
                    selectedLocation[0] >= tabLocation[0] + tabs.paddingLeft)
                assertTrue("The selected slot must keep its right inset without clipping",
                    selectedLocation[0] + selected.width <= tabLocation[0] + tabs.width - tabs.paddingRight)
                val visible = android.graphics.Rect()
                assertTrue(selected.getGlobalVisibleRect(visible))
                assertEquals("The entire selected slot must remain visible", selected.width, visible.width())
                assertFalse("A placeholder must not offer profile download",
                    activity.findViewById<Toolbar>(CommonR.id.toolbar).menu.findItem(CommonR.id.minimal_add_action).isVisible)
            }
        }
        onHome { it.refreshSlots() }
        waitForHome(slotCount - 1)
        onHome { activity ->
            assertEquals("Refreshing must preserve the selected empty slot", slotCount - 1,
                activity.findViewById<TabLayout>(CommonR.id.main_tabs).selectedTabPosition)
            assertFalse(activity.findViewById<Toolbar>(CommonR.id.toolbar).menu.findItem(CommonR.id.minimal_add_action).isVisible)
        }
    }

    private fun onHome(block: (UnprivilegedMainActivity) -> Unit) = instrumentation.runOnMainSync {
        block(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<UnprivilegedMainActivity>().single())
    }

    private fun waitForHome(selected: Int? = null) {
        repeat(200) {
            var ready = false
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<UnprivilegedMainActivity>().firstOrNull()
                if (activity != null) {
                    val pager = activity.findViewById<ViewPager2>(CommonR.id.view_pager)
                    ready = pager.isShown && (selected == null || pager.currentItem == selected) &&
                        views(pager).filterIsInstance<TextView>().any {
                            it.isShown && it.text.toString() == activity.getString(R.string.empty_esim_title)
                        }
                }
            }
            if (ready) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(100)
        }
        fail("Empty home did not finish loading the selected slot")
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
}
