package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class MoreNavigationTest {
    @Test fun `chip pages retain more and both back actions finish only the child`() = runBlocking {
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(MockEuiccChannel(0, 0))
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        fun children(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        try {
            awaitMainLooper {
                activity.supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>()
                    .any { it.view?.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)?.isRefreshing == false }
            }
            val menu = PopupMenu(activity, activity.findViewById(R.id.toolbar)).menu
            activity.onOptionsItemSelected(menu.add(0, R.id.minimal_more_action, 0, "More"))
            val more = ShadowDialog.getLatestDialog()
            for ((title, destination) in listOf(
                R.string.euicc_info to EuiccInfoActivity::class.java,
                R.string.profile_notifications_show to NotificationsActivity::class.java
            )) {
                for (toolbarBack in listOf(true, false)) {
                    val label = children(more.window!!.decorView).filterIsInstance<TextView>()
                        .single { it.text.toString() == activity.getString(title) }
                    var row: View = label
                    while (!row.isClickable) row = row.parent as View
                    row.performClick()
                    val intent = shadowOf(activity).nextStartedActivity
                    assertEquals(destination.name, intent.component!!.className)
                    assertTrue("More must remain behind the child page", more.isShowing)
                    val childController = Robolectric.buildActivity(destination, intent)
                    childController.get().setTheme(R.style.Theme_OpenEUICC)
                    val child = childController.setup().get()
                    try {
                        if (toolbarBack) {
                            val back = PopupMenu(child, child.findViewById(R.id.toolbar)).menu
                                .add(0, android.R.id.home, 0, "Back")
                            child.onOptionsItemSelected(back)
                        } else child.onBackPressedDispatcher.onBackPressed()
                        assertTrue(child.isFinishing)
                        assertFalse(activity.isFinishing)
                        assertTrue(more.isShowing)
                    } finally {
                        childController.pause().stop().destroy()
                    }
                }
            }
            more.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
