package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Looper
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.core.usb.UsbCardFailure
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class UsbCardAutoRefreshTest {
    @Test fun `insert and removal refresh USB pages automatically without flicker or stealing slot selection`() = runBlocking {
        val device = usbTestDevice()
        var result = EuiccChannelManager.UsbChannelOpenResult(device, false, UsbCardFailure.CARD_UNRESPONSIVE)
        var polls = 0
        val base = MockEuiccChannelManager(MockEuiccChannel(0, 0))
        val manager = object : EuiccChannelManager by base {
            override suspend fun pollUsbCard(): EuiccChannelManager.UsbChannelOpenResult {
                polls++
                return result
            }
            override suspend fun <R> withEuiccChannel(physicalSlotId: Int, portId: Int,
                seId: EuiccChannel.SecureElementId, fn: suspend (EuiccChannel) -> R): R =
                if (physicalSlotId == 99) fn(MockEuiccChannel(99, 0))
                else base.withEuiccChannel(physicalSlotId, portId, seId, fn)
        }
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        shadowOf(app.getSystemService(android.telephony.TelephonyManager::class.java)).apply {
            setPhoneCount(2); setActiveModemCount(2)
        }
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        val pager = activity.findViewById<ViewPager2>(R.id.view_pager)
        fun usbStatus() = activity.supportFragmentManager.fragments
            .filterIsInstance<UsbCcidReaderPermissionFragment>().firstOrNull { it.isResumed }
        fun usbProfiles() = activity.supportFragmentManager.fragments
            .filterIsInstance<EuiccManagementFragment>().firstOrNull { it.isResumed && pager.currentItem == 2 }
        fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))
        try {
            awaitMainLooper { usbStatus()?.view?.findViewById<TextView>(R.id.usb_reader_text)?.text ==
                activity.getString(R.string.usb_card_unresponsive) }
            assertEquals(3, pager.adapter!!.itemCount)
            val emptyPage = pager.adapter!!.getItemId(2)
            val count = polls
            tick()
            awaitMainLooper { polls > count }
            assertEquals("Unchanged state must preserve its page", emptyPage, pager.adapter!!.getItemId(2))

            result = EuiccChannelManager.UsbChannelOpenResult(device, true, cardIdentity = "card-a", connectionId = 1)
            tick()
            awaitMainLooper { usbProfiles() != null }
            val profilePage = pager.adapter!!.getItemId(2)
            tick()
            assertEquals("Healthy cards must not recreate their list every poll", profilePage, pager.adapter!!.getItemId(2))

            result = EuiccChannelManager.UsbChannelOpenResult(device, false, UsbCardFailure.CARD_UNRESPONSIVE)
            tick()
            awaitMainLooper { usbStatus()?.view?.findViewById<TextView>(R.id.usb_reader_text)?.text ==
                activity.getString(R.string.usb_card_unresponsive) }
            assertNull("Removed cards must not leave their old profile list visible", usbProfiles())

            result = EuiccChannelManager.UsbChannelOpenResult(device, true, cardIdentity = "card-b", connectionId = 2)
            tick()
            awaitMainLooper { usbProfiles() != null }
            assertNotEquals(profilePage, pager.adapter!!.getItemId(2))

            pager.setCurrentItem(1, false)
            val beforeRemoval = pager.adapter!!.getItemId(2)
            result = EuiccChannelManager.UsbChannelOpenResult(device, false, UsbCardFailure.UNSUPPORTED_CARD)
            tick()
            awaitMainLooper { pager.adapter!!.getItemId(2) != beforeRemoval }
            assertEquals("A USB change must not steal the selected phone slot", 1, pager.currentItem)

            controller.pause()
            val stoppedPolls = polls
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals("Background screens must stop polling", stoppedPolls, polls)
            controller.resume()
            pager.setCurrentItem(2, false)
            awaitMainLooper { usbStatus()?.view?.findViewById<TextView>(R.id.usb_reader_text)?.text ==
                activity.getString(R.string.usb_unsupported_card) }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
