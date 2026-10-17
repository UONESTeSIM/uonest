package im.angry.easyeuicc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.angry.openeuicc.ui.wizard.DownloadAnimationView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadAnimationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    @Test fun animationStopsOffscreenAndShowsRealComponentResultStates() {
        val context = instrumentation.targetContext
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DownloadAnimationPreviewActivity")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val animation = children(activity.window.decorView).filterIsInstance<DownloadAnimationView>().single()
                assertEquals(DownloadAnimationView.Result.Downloading, animation.result)
                assertTrue("Visible downloading scene must animate", animation.isAnimating)
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                assertFalse("Background scene must not consume animation frames",
                    children(activity.window.decorView).filterIsInstance<DownloadAnimationView>().single().isAnimating)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            for ((label, result) in listOf(
                "下载中" to DownloadAnimationView.Result.Downloading,
                "成功" to DownloadAnimationView.Result.Success,
                "失败" to DownloadAnimationView.Result.Failure,
                "下载中" to DownloadAnimationView.Result.Downloading
            )) {
                scenario.onActivity { activity ->
                    val views = children(activity.window.decorView)
                    views.filterIsInstance<Button>().single { it.text == label }.performClick()
                    val animation = views.filterIsInstance<DownloadAnimationView>().single()
                    assertEquals(result, animation.result)
                    assertEquals("Motion must run only while downloading",
                        result == DownloadAnimationView.Result.Downloading, animation.isAnimating)
                    assertTrue("TalkBack must receive the current status", animation.contentDescription.isNotBlank())
                }
                instrumentation.waitForIdleSync()
                // Let the compositor present the invalidated result before capture.
                android.os.SystemClock.sleep(160)
                val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
                File(dir, "download-animation-$result.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
