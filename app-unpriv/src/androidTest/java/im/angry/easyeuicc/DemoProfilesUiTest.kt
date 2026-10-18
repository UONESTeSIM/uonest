package im.angry.easyeuicc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.ProfileCardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DemoProfilesUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun card(activity: Activity, position: Int) = activity.findViewById<RecyclerView>(R.id.profile_list)
        .findViewHolderForAdapterPosition(position)!!.itemView as ProfileCardView
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(200) }
    private fun await(check: () -> Unit) {
        repeat(60) {
            if (runCatching(check).isSuccess) return
            SystemClock.sleep(100)
        }
        check()
    }
    private fun swipe(card: View, right: Boolean) {
        val time = SystemClock.uptimeMillis()
        val x = card.width / 2f
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val dx = if (i == 0) 0f else card.resources.displayMetrics.density * (if (right) 110f else -110f)
            MotionEvent.obtain(time, time + i * 60, action, x + dx, card.height / 2f, 0).also { card.dispatchTouchEvent(it); it.recycle() }
        }
    }

    private fun assertClosedCardHasNoActionColors(card: ProfileCardView) {
        assertEquals(View.INVISIBLE, card.getChildAt(0).visibility)
        assertEquals(View.INVISIBLE, card.getChildAt(1).visibility)
        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(android.graphics.Canvas(bitmap))
        val edge = (2 * card.resources.displayMetrics.density).toInt()
        for (y in 0 until bitmap.height) {
            for (x in (0 until edge) + (bitmap.width - edge until bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val blue = android.graphics.Color.blue(color)
                if (android.graphics.Color.alpha(color) > 0) {
                    assertTrue("Closed card must not leak blue/red action color at its edge", kotlin.math.abs(red - blue) < 20)
                }
            }
        }
        bitmap.recycle()
    }

    @Test fun swipeBackgroundAppearsOnlyOnTheRevealedSideAndHidesWhenClosed() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("demo_profiles", 0).edit().clear().commit()
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DemoProfilesActivity")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                val card = card(activity, 1)
                assertClosedCardHasNoActionColors(card)
                swipe(card, true)
            }
            settle()
            scenario.onActivity { activity ->
                val card = card(activity, 1)
                assertEquals(View.VISIBLE, card.getChildAt(0).visibility)
                assertEquals(View.INVISIBLE, card.getChildAt(1).visibility)
                card.close()
                assertClosedCardHasNoActionColors(card)
                swipe(card, false)
            }
            settle()
            scenario.onActivity { activity ->
                val card = card(activity, 1)
                assertEquals(View.INVISIBLE, card.getChildAt(0).visibility)
                assertEquals(View.VISIBLE, card.getChildAt(1).visibility)
                val time = SystemClock.uptimeMillis()
                MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, card.width / 2f, card.height / 2f, 0).also { card.dispatchTouchEvent(it); it.recycle() }
                MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_MOVE, card.width / 2f - 50, card.height / 2f, 0).also { card.dispatchTouchEvent(it); it.recycle() }
                MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_CANCEL, card.width / 2f - 50, card.height / 2f, 0).also { card.dispatchTouchEvent(it); it.recycle() }
            }
            settle()
            scenario.onActivity { activity -> assertClosedCardHasNoActionColors(card(activity, 1)) }
        }
    }

    @Test fun demoCardsSwitchEditDeleteAndPersistLocally() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("demo_profiles", 0).edit().clear().commit()
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DemoProfilesActivity")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settle()
            scenario.onActivity { activity ->
                assertEquals(3, activity.findViewById<RecyclerView>(R.id.profile_list).adapter!!.itemCount)
                assertTrue(card(activity, 0).toggle.isChecked)
                card(activity, 1).toggle.performClick()
            }
            await { scenario.onActivity { assertTrue(card(it, 1).toggle.isChecked) } }
            settle()
            scenario.onActivity { activity ->
                assertFalse(card(activity, 0).toggle.isChecked)
                assertTrue(card(activity, 1).toggle.isChecked)
                activity.findViewById<TabLayout>(R.id.main_tabs).getTabAt(1)!!.select()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.findViewById<RecyclerView>(R.id.profile_list).adapter!!.itemCount)
                assertTrue("Other slot must retain its own enabled profile", card(activity, 0).toggle.isChecked)
                activity.findViewById<TabLayout>(R.id.main_tabs).getTabAt(0)!!.select()
            }
            scenario.recreate()
            settle()
            scenario.onActivity { activity ->
                assertTrue("Demo choices survive recreation", card(activity, 1).toggle.isChecked)
                swipe(card(activity, 0), true)
            }
            settle()
            scenario.onActivity { activity ->
                val first = card(activity, 0)
                assertTrue("Right swipe reveals edit", first.getChildAt(2).translationX > 0)
                first.getChildAt(0).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
                val dialog = roots.single { views(it).any { child -> child is EditText } }
                views(dialog).filterIsInstance<EditText>().single().setText("演示改名")
                dialog.findViewById<View>(android.R.id.button1).performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals("演示改名", card(activity, 0).name.text.toString())
                swipe(card(activity, 0), false)
            }
            settle()
            scenario.onActivity { activity ->
                val first = card(activity, 0)
                assertTrue("Left swipe reveals delete", first.getChildAt(2).translationX < 0)
                first.getChildAt(1).performClick()
            }
            settle()
            instrumentation.runOnMainSync {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .mapNotNull { it.findViewById<View>(android.R.id.button1) }.single().performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.findViewById<RecyclerView>(R.id.profile_list).adapter!!.itemCount)
                assertTrue("Deleting disabled fixture preserves enabled profile", card(activity, 0).toggle.isChecked)
            }
            val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
            File(dir, "demo-profiles.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun fullPageDemoMenuRestoresFixturesAndKeepsDownloadPreview() {
        val context = instrumentation.targetContext
        context.getSharedPreferences("demo_profiles", 0).edit().clear().commit()
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DemoProfilesActivity")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            settle()
            scenario.onActivity { card(it, 1).onDelete() }
            settle()
            instrumentation.runOnMainSync {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .filter { it.hasWindowFocus() }
                    .mapNotNull { it.findViewById<View>(android.R.id.button1) }.single().performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.findViewById<RecyclerView>(R.id.profile_list).adapter!!.itemCount)
                views(activity.findViewById(R.id.minimal_more_action))
                    .filterIsInstance<android.widget.ImageButton>().single().performClick()
            }
            settle()
            fun clickMenu(title: String) {
                instrumentation.runOnMainSync {
                    val root = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
                    assertTrue("More must be a full-page scrollable menu", views(root).any { it is android.widget.ScrollView })
                    var target: View = views(root).filterIsInstance<android.widget.TextView>().single { it.text.toString() == title }
                    while (!target.isClickable) target = target.parent as View
                    target.performClick()
                }
                settle()
            }
            clickMenu("恢复演示卡片")
            scenario.onActivity { activity ->
                assertEquals(3, activity.findViewById<RecyclerView>(R.id.profile_list).adapter!!.itemCount)
                assertTrue(card(activity, 0).toggle.isChecked)
                views(activity.findViewById(R.id.minimal_more_action))
                    .filterIsInstance<android.widget.ImageButton>().single().performClick()
            }
            settle()
            val monitor = instrumentation.addMonitor(DownloadAnimationPreviewActivity::class.java.name, null, false)
            try {
                clickMenu("下载动画预览")
                val preview = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Demo menu must open the simulated download, not a real download", preview)
                instrumentation.runOnMainSync { preview.finish() }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }
}
