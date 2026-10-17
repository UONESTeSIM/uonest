package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.descendants
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import im.angry.openeuicc.ui.minimal.ProfileSwitchDialog
import im.angry.openeuicc.ui.minimal.ProfileDeleteView
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.util.isEnabled
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileInfo
import net.typeblog.lpac_jni.ProfileClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class ProfileSwitchUiTest {
    @Test fun `enable waits for actual state and prevents duplicate requests`() = exercise(true, false)
    @Test fun `disable waits for actual state and prevents duplicate requests`() = exercise(false, false)
    @Test fun `failure closes waiting dialog without changing confirmed state`() = exercise(true, true)

    @Test fun `deletion keeps the animation until the card disappears and prevents duplicates`() = exercise(true, false, true)
    @Test fun `failed deletion closes the animation and retains the card`() = exercise(true, true, true)

    private fun exercise(enable: Boolean, fail: Boolean, delete: Boolean = false) = runBlocking {
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        var enabled = !enable
        var removed = false
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override val profiles get() = if (removed) emptyList() else listOf(LocalProfileInfo("target",
                if (enabled) LocalProfileInfo.State.Enabled else LocalProfileInfo.State.Disabled,
                "Test", "", "Provider", "", ProfileClass.Operational))
            override fun enableProfile(iccid: String, refresh: Boolean): Boolean {
                calls.incrementAndGet()
                check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                if (fail) error("Simulated switch failure")
                enabled = enable
                return true
            }
            override fun disableProfile(iccid: String, refresh: Boolean) = enableProfile(iccid, refresh)
            override fun deleteProfile(iccid: String): Boolean {
                calls.incrementAndGet()
                check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                if (fail) error("Simulated deletion failure")
                removed = true
                return true
            }
        }
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(MockEuiccChannel(0, 0, lpa))
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(android.telephony.TelephonyManager::class.java)).apply {
            setPhoneCount(2)
            setActiveModemCount(2)
        }
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper {
                activity.supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>().any {
                    it.view?.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)?.isRefreshing == false
                }
            }
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>().first()
            val originalView = fragment.requireView()
            val indicator = originalView.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
            val switch = EuiccManagementFragment::class.java.getDeclaredMethod("enableOrDisableProfile",
                String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val dialogField = EuiccManagementFragment::class.java.getDeclaredField("switchDialog").apply { isAccessible = true }
            val stateField = EuiccManagementFragment::class.java.getDeclaredField("enabledProfile").apply { isAccessible = true }
            if (delete) {
                fun showDeletePrompt(): ProfileDeleteFragment = ProfileDeleteFragment.newInstance(
                    0, 0, EuiccChannel.SecureElementId.DEFAULT, "target", "Test").also {
                    it.showNow(fragment.childFragmentManager, ProfileDeleteFragment.TAG)
                }
                val cancelled = showDeletePrompt()
                val root = cancelled.requireDialog().window!!.decorView as ViewGroup
                assertTrue("Deletion must not bring back the old name-entry step",
                    root.descendants.none { it is EditText })
                val content = root.descendants.filterIsInstance<ProfileDeleteView>().single()
                assertEquals(activity.getString(R.string.profile_delete_confirm, "Test"),
                    content.messageView.text.toString())
                assertEquals("Opening the prompt must not delete a profile", 0, calls.get())
                content.cancelButton.performClick()
                fragment.childFragmentManager.executePendingTransactions()
                assertFalse(cancelled.isAdded)
                assertEquals("Cancelling must leave the profile untouched", 0, calls.get())
                val confirmed = showDeletePrompt()
                confirmed.requireDialog().findViewById<android.view.View>(android.R.id.button1).performClick()
            } else switch.invoke(fragment, "target", enable)
            shadowOf(Looper.getMainLooper()).idle()
            service.onStartCommand(Intent(), 0, 1)
            awaitMainLooper { calls.get() == 1 }
            val dialog = dialogField.get(fragment) as ProfileSwitchDialog
            assertTrue(dialog.isShowing)
            assertFalse("Only the modal should indicate switching", indicator.isRefreshing)
            assertSame(originalView, fragment.view)
            assertEquals(!enable, (stateField.get(fragment) as? LocalProfileInfo)?.isEnabled == true)
            // Modem broadcasts also refresh the activity's slots while the modal is visible.
            app.sendBroadcast(Intent("android.intent.action.SIM_STATE_CHANGED"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
            val refreshingField = MainActivity::class.java.getDeclaredField("refreshing").apply { isAccessible = true }
            awaitMainLooper { refreshingField.getBoolean(activity) }
            assertFalse("SIM broadcasts must not add a second loading indicator", indicator.isRefreshing)
            if (delete) fragment.deleteProfile("target") else switch.invoke(fragment, "target", enable)
            assertEquals(1, calls.get())
            gate.countDown()
            awaitMainLooper {
                assertFalse("Automatic slot and profile refresh stays silent through completion", indicator.isRefreshing)
                dialogField.get(fragment) == null && !refreshingField.getBoolean(activity)
            }
            assertFalse(dialog.isShowing)
            assertEquals(if (delete) false else if (fail) !enable else enable,
                (stateField.get(fragment) as? LocalProfileInfo)?.isEnabled == true)
            if (delete) {
                val list = originalView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profile_list)
                assertEquals("Only a successful deletion replaces the card with the empty state",
                    if (fail) 0 else 1, list.adapter!!.getItemViewType(0))
            }
            assertEquals(1, calls.get())
            assertSame(originalView, fragment.view)
        } finally {
            gate.countDown()
            controller.pause().stop().destroy()
            service.onDestroy()
        }
    }
}
