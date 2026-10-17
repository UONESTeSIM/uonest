package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageButton
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class EuiccInfoActionsTest {
    @Test fun `erase requires exact confirmation and prevents duplicate actions`() = runBlocking {
        var resets = 0
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override val eID = "89000000000000000000000001234567"
            override fun euiccMemoryReset() { resets++ }
        }
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(MockEuiccChannel(0, 0, lpa))
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        val controller = Robolectric.buildActivity(EuiccInfoActivity::class.java)
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        fun children(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        try {
            val buttons = children(activity.findViewById(R.id.toolbar)).filterIsInstance<AppCompatImageButton>()
            val erase = buttons.single { it.contentDescription == activity.getString(R.string.minimal_erase) }
            val refresh = buttons.single { it.contentDescription == activity.getString(R.string.minimal_refresh) }
            awaitMainLooper { erase.isEnabled && refresh.isEnabled }
            refresh.performClick()
            assertFalse(erase.isEnabled)
            awaitMainLooper { erase.isEnabled && refresh.isEnabled }
            erase.performClick()
            activity.supportFragmentManager.executePendingTransactions()
            val confirmation = activity.supportFragmentManager.findFragmentByTag(EuiccMemoryResetFragment.TAG)
                as EuiccMemoryResetFragment
            val dialog = confirmation.requireDialog()
            val input = dialog.findViewById<android.widget.EditText>(R.id.euicc_erase_input)
            val confirm = dialog.findViewById<View>(android.R.id.button1)
            assertFalse(confirm.isEnabled)
            input.setText("0123456")
            assertFalse(confirm.isEnabled)
            input.setText("99999999")
            assertFalse(confirm.isEnabled)
            confirm.performClick()
            assertNotNull(input.error)
            assertEquals(0, resets)
            assertTrue(dialog.isShowing)
            input.setText("999999999")
            assertEquals(8, input.text.length)
            assertFalse(confirm.isEnabled)
            input.setText(lpa.eID.takeLast(8))
            assertTrue(confirm.isEnabled)
            input.text.delete(7, 8)
            assertFalse(confirm.isEnabled)
            input.setText(lpa.eID.takeLast(8))
            assertTrue(confirm.isEnabled)
            confirm.performClick()
            assertFalse(confirm.isEnabled)
            assertFalse(input.isEnabled)
            assertFalse(dialog.findViewById<View>(android.R.id.button2).isEnabled)
            // Robolectric does not deliver the service's self-start command.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            service.onStartCommand(Intent(), 0, 1)
            awaitMainLooper { resets == 1 }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
