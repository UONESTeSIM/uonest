package im.angry.easyeuicc

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.LogsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LogsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun codeHeaderCopiesAndSavesFullLogWithoutExportOrSharePage() {
        val fixture = (0 until 300).joinToString("\n") { "log-${it.toString().padStart(3, '0')}" }
        ActivityScenario.launch<LogsActivity>(Intent(context, LogsActivity::class.java).putExtra("log", fixture)).use { scenario ->
            await { scenario.onActivity { activity ->
                check(activity.findViewById<View>(R.id.logs_copy)?.isEnabled == true)
                check(activity.findViewById<TextView>(R.id.logs_text).text.endsWith("log-299"))
            } }
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.logs_text)
                assertEquals(11f, text.textSize / activity.resources.displayMetrics.scaledDensity, 0.05f)
                assertEquals(256, text.text.lines().size)
                val card = activity.findViewById<ViewGroup>(R.id.logs_code_block)
                assertNotNull("The log must use the code block background", card.background)
                assertNotNull("The whole code block must have a faint outline", card.foreground)
                assertNull("The log must not introduce a nested input background", text.background)
                assertFalse("No footer actions should remain below the log", views(card).any { it is android.widget.Button })
                val header = card.getChildAt(0)
                assertTrue("The code block header must stay visible with long logs", header.getGlobalVisibleRect(Rect()))
                assertTrue("Long logs must scroll inside the code block", text.height > (text.parent as View).height)
                val toolbar = Rect().also { activity.findViewById<View>(R.id.toolbar).getGlobalVisibleRect(it) }
                val headerBounds = Rect().also { header.getGlobalVisibleRect(it) }
                val title = (header as ViewGroup).getChildAt(0)
                val titleBounds = Rect().also { title.getGlobalVisibleRect(it) }
                var previousRight = titleBounds.right
                listOf(R.id.logs_refresh, R.id.save, R.id.logs_copy).forEach { id ->
                    val action = activity.findViewById<View>(id)
                    val bounds = Rect().also { action.getGlobalVisibleRect(it) }
                    assertSame("Log actions must share the code block title row", header, action.parent)
                    assertTrue("Log actions must stay within the code block header", headerBounds.contains(bounds))
                    assertFalse("Log actions must no longer appear in the navigation bar", toolbar.intersects(bounds.left, bounds.top, bounds.right, bounds.bottom))
                    assertTrue("The title and actions must not overlap", bounds.left >= previousRight)
                    assertEquals("Actions must be vertically centered with the title", titleBounds.centerY().toFloat(), bounds.centerY().toFloat(), 2f)
                    previousRight = bounds.right
                }
                val textBounds = Rect().also { card.getGlobalVisibleRect(it) }
                val toolbarView = activity.findViewById<ViewGroup>(R.id.toolbar)
                val back = (0 until toolbarView.childCount).map { toolbarView.getChildAt(it) }
                    .filterIsInstance<android.widget.ImageButton>().single()
                val backEdge = floatArrayOf(back.drawable.bounds.left + back.drawable.bounds.width() * 8.1f / 24f, 0f)
                back.imageMatrix.mapPoints(backEdge)
                val backLocation = IntArray(2).also { back.getLocationOnScreen(it) }
                assertEquals("The back arrow must align with the code block's left edge",
                    textBounds.left.toFloat(), backLocation[0] + back.paddingLeft + backEdge[0], 2f)
                assertTrue("The back button's full touch target must remain inside the toolbar",
                    toolbar.contains(Rect().also { back.getGlobalVisibleRect(it) }))
                activity.intent.putExtra("log", "$fixture\nrefreshed-marker")
                activity.findViewById<View>(R.id.logs_refresh).performClick()
            }
            await { scenario.onActivity { activity ->
                check(activity.findViewById<TextView>(R.id.logs_text).text.endsWith("refreshed-marker"))
                check(activity.findViewById<View>(R.id.save).isEnabled)
            } }
            scenario.onActivity { activity ->
                var view: View = activity.findViewById(R.id.logs_text)
                while (view !is android.widget.ScrollView) view = view.parent as View
                view.scrollTo(0, 0)
            }
            instrumentation.waitForIdleSync()
            val screenshots = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
            File(screenshots, "logs-code-header.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            var copied = ""
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.logs_copy).performClick()
                copied = activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                assertTrue("Copy must include lines omitted from the 256-line display", copied.contains(fixture))
                assertTrue(copied.contains("Manufacturer:"))
                assertTrue(copied.contains("refreshed-marker"))
            }

            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File.createTempFile("logs-ui-test-", ".txt", dir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val saveFilter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addDataType("text/plain")
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val monitor = instrumentation.addMonitor(saveFilter,
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
            try {
                scenario.onActivity { it.findViewById<View>(R.id.save).performClick() }
                await { check(file.readText() == copied) }
                assertEquals("Save must go directly to the system document picker", 1, monitor.hits)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(activity.hasWindowFocus())
                    val allText = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .flatMap { views(it) }.filterIsInstance<TextView>().map { it.text.toString() }
                    assertFalse("Saving logs must not show a share prompt", allText.contains(activity.getString(R.string.logs_saved_message)))
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                file.delete()
            }
        }
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun await(check: () -> Unit) {
        repeat(50) {
            if (runCatching(check).isSuccess) return
            Thread.sleep(100)
        }
        check()
    }
}
