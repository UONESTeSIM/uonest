package im.angry.easyeuicc

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog
import im.angry.openeuicc.ui.minimal.ProfileEditorView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileEditorDialogUiTest {
    @Test fun dialogKeepsItsHorizontalCenterThroughoutOpeningAndClosing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DemoProfilesActivity")
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            lateinit var dialog: ProfileEditorDialog
            val centers = mutableListOf<Float>()
            var screenCenter = 0f
            scenario.onActivity { activity ->
                dialog = ProfileEditorDialog(activity)
                val editor = ProfileEditorView(activity, "动画检查", onSave = {}, onCancel = { dialog.dismiss() })
                dialog.setContentView(MinimalUi(activity).scroll(editor))
                val decor = dialog.window!!.decorView
                screenCenter = activity.resources.displayMetrics.widthPixels / 2f
                decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        val location = IntArray(2)
                        decor.getLocationOnScreen(location)
                        centers.add(location[0] + decor.width * decor.scaleX / 2f)
                        return true
                    }
                })
                dialog.show()
                // No system window movement may be layered over the content animation.
                assertEquals(0, dialog.window!!.attributes.windowAnimations)
                assertEquals(Gravity.CENTER, dialog.window!!.attributes.gravity)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            scenario.onActivity {
                assertEquals(1f, dialog.window!!.decorView.scaleX, 0.001f)
                assertEquals(1f, dialog.window!!.decorView.alpha, 0.001f)
                dialog.dismiss()
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            scenario.onActivity {
                assertFalse(dialog.isShowing)
                assertTrue("Animation must render frames", centers.isNotEmpty())
                centers.forEach { center ->
                    assertEquals("Opening and closing must not slide horizontally", screenCenter, center, 3f)
                }
            }
        }
    }
}
