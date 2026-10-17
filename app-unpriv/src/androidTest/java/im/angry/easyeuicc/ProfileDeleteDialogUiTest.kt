package im.angry.easyeuicc

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.ui.ProfileDeleteFragment
import im.angry.openeuicc.ui.minimal.ProfileDeleteView
import im.angry.openeuicc.common.R as CommonR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileDeleteDialogUiTest {
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun profileDeletionShowsTheTargetWithoutNameEntryAndCanBeCancelled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent().setClassName(context, "im.angry.easyeuicc.DemoProfilesActivity")
        ActivityScenario.launch<AppCompatActivity>(intent).use { scenario ->
            lateinit var fragment: ProfileDeleteFragment
            scenario.onActivity { activity ->
                fragment = ProfileDeleteFragment.newInstance(0, 0, EuiccChannel.SecureElementId.DEFAULT,
                    "test-only", "日常主卡")
                fragment.showNow(activity.supportFragmentManager, ProfileDeleteFragment.TAG)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
            File(dir, "profile-delete.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            scenario.onActivity { activity ->
                val dialog = fragment.requireDialog()
                val children = views(dialog.window!!.decorView)
                val content = children.filterIsInstance<ProfileDeleteView>().single()
                assertTrue("Deletion no longer requires typing the profile name",
                    children.none { it is EditText })
                assertEquals(activity.getString(CommonR.string.profile_delete_title), content.titleView.text.toString())
                assertEquals(activity.getString(CommonR.string.profile_delete_confirm, "日常主卡"),
                    content.messageView.text.toString())
                assertEquals(activity.getString(CommonR.string.profile_delete_action), content.deleteButton.text.toString())
                assertTrue("Opening the prompt must not dismiss it or start deletion", dialog.isShowing)
                assertTrue(content.deleteButton.isEnabled)
                content.cancelButton.performClick()
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            scenario.onActivity { assertFalse(fragment.isAdded) }
        }
    }
}
