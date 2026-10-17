package im.angry.openeuicc.ui

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import im.angry.openeuicc.ui.minimal.ProfileCardView
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class ProfileIccidClickTest {
    @Test fun `bound ICCID stays on home while its copy icon copies the current complete value`() = runBlocking {
        TestOpenEuiccApplication.mockEuiccChannelManager = MockEuiccChannelManager(MockEuiccChannel(0, 0))
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
            val card = ProfileCardView(activity)
            val holder = fragment.ProfileViewHolder(card)
            assertFalse("Creating a holder must not make ICCID a navigation link", card.iccid.performClick())
            // Rebinding a recycled card must neither restore navigation nor copy the old profile's ICCID.
            for (iccid in listOf("89852342022498371227", "89852342022498371228")) {
                holder.setProfile(LocalProfileInfo(iccid, LocalProfileInfo.State.Disabled,
                    "Test", "", "Provider", "", ProfileClass.Operational))
                assertFalse(card.iccid.performClick())
                assertNull(shadowOf(activity).nextStartedActivity)
                val copy = (card as ViewGroup).descendants.single {
                    it.contentDescription == "${activity.getString(R.string.minimal_copy)} ICCID"
                }
                assertTrue(copy.performClick())
                assertEquals(iccid, activity.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        } finally {
            controller.pause().stop().destroy()
            service.onDestroy()
        }
    }
}
