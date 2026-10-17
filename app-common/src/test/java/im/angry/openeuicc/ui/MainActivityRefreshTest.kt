package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.runBlocking
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
class MainActivityRefreshTest {
    @Test fun `refresh preserves content until results arrive and recovers after failure`() = runBlocking {
        val base = MockEuiccChannelManager(MockEuiccChannel(0, 0))
        var gate: CompletableDeferred<Unit>? = null
        var entered = false
        var fail = false
        var present = true
        var profileReadFails = false
        val manager = object : EuiccChannelManager by base {
            override suspend fun <R> withEuiccChannel(physicalSlotId: Int, portId: Int,
                seId: EuiccChannel.SecureElementId, fn: suspend (EuiccChannel) -> R): R {
                if (profileReadFails) throw EuiccChannelManager.EuiccChannelNotFoundException()
                return base.withEuiccChannel(physicalSlotId, portId, seId, fn)
            }
            override fun flowInternalEuiccPorts() = flow {
                entered = true
                gate?.await()
                if (fail) error("Simulated slot read failure")
                if (present) emitAll(base.flowInternalEuiccPorts())
            }
        }
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
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
        val pager = activity.findViewById<ViewPager2>(R.id.view_pager)
        val tabs = activity.findViewById<View>(R.id.main_tabs_container)
        val loading = activity.findViewById<View>(R.id.loading)
        try {
            awaitMainLooper {
                pager.visibility == View.VISIBLE &&
                    activity.supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>()
                        .any { it.view?.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)?.isRefreshing == false }
            }
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<EuiccManagementFragment>().first()
            val content = fragment.requireView()
            val indicator = content.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
            val originalId = pager.adapter!!.getItemId(0)
            val menu = androidx.appcompat.view.menu.MenuBuilder(activity)
            activity.onCreateOptionsMenu(menu)
            val add = menu.findItem(R.id.minimal_add_action)
            val more = menu.findItem(R.id.minimal_more_action)
            val moreView = more.actionView
            activity.onPrepareOptionsMenu(menu)
            assertTrue(add.isVisible)
            activity.onOptionsItemSelected(add)
            val addIntent = shadowOf(activity).nextStartedActivity
            assertEquals(im.angry.openeuicc.ui.wizard.AddEsimActivity::class.java.name, addIntent.component!!.className)
            assertEquals(0, addIntent.getIntExtra("selectedLogicalSlot", -1))
            // An unreadable second slot must remain available beside the working eUICC.
            assertEquals(2, pager.adapter!!.itemCount)
            val emptySlotId = pager.adapter!!.getItemId(1)
            // Switching away and back must preserve the loaded profile page.
            pager.setCurrentItem(1, false)
            awaitMainLooper { !fragment.isResumed }
            activity.onPrepareOptionsMenu(menu)
            assertFalse("The usable first slot must not expose Add on the empty second slot", add.isVisible)
            assertTrue(more.isVisible)
            assertSame(moreView, more.actionView)
            activity.onOptionsItemSelected(add)
            assertNull("Unavailable slots must never fall back to the old target picker", shadowOf(activity).nextStartedActivity)
            pager.setCurrentItem(0, false)
            awaitMainLooper { fragment.isResumed }
            assertSame(content, fragment.view)
            awaitMainLooper { !indicator.isRefreshing }
            activity.onPrepareOptionsMenu(menu)
            assertTrue(add.isVisible)
            for (shouldFail in listOf(true, false)) {
                fail = shouldFail
                entered = false
                gate = CompletableDeferred()
                activity.refreshSlots()
                awaitMainLooper { entered && indicator.isRefreshing }
                assertEquals(View.VISIBLE, pager.visibility)
                assertEquals(View.VISIBLE, tabs.visibility)
                assertEquals(View.GONE, loading.visibility)
                assertSame(content, fragment.view)
                gate!!.complete(Unit)
                awaitMainLooper { !indicator.isRefreshing }
                assertSame(content, fragment.view)
                assertEquals(originalId, pager.adapter!!.getItemId(0))
                assertEquals(2, pager.adapter!!.itemCount)
                assertEquals(emptySlotId, pager.adapter!!.getItemId(1))
            }
            // A transient OMAPI disconnect during the fragment's own refresh must not crash to logs.
            profileReadFails = true
            fragment.onEuiccProfilesChanged()
            awaitMainLooper { !indicator.isRefreshing }
            assertEquals(activity.getString(R.string.profile_read_temporarily_failed),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertSame(content, fragment.view)
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.onPrepareOptionsMenu(menu)
            assertFalse("An unreadable channel must hide Add", add.isVisible)
            profileReadFails = false
            fragment.onEuiccProfilesChanged()
            awaitMainLooper { !indicator.isRefreshing }
            assertSame(content, fragment.view)
            activity.onPrepareOptionsMenu(menu)
            assertTrue("A successful retry restores Add", add.isVisible)
            // A removed chip must replace the retained profile page after discovery completes.
            present = false
            entered = false
            gate = CompletableDeferred()
            activity.refreshSlots()
            awaitMainLooper { entered && indicator.isRefreshing }
            assertEquals(originalId, pager.adapter!!.getItemId(0))
            gate!!.complete(Unit)
            awaitMainLooper { pager.adapter!!.getItemId(0) != originalId }
            assertEquals(2, pager.adapter!!.itemCount)
            assertEquals(emptySlotId, pager.adapter!!.getItemId(1))
            assertEquals(View.VISIBLE, pager.visibility)
        } finally {
            gate?.complete(Unit)
            controller.pause().stop().destroy()
        }
    }
}
