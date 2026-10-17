package im.angry.openeuicc.ui

import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.LocalProfileNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class NotificationsActivityTest {
    @Test fun `failed delivery is not marked sent and retry targets the selected slot and record`() = runBlocking {
        var succeeds = false
        var networkFailure = false
        val requests = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        val notification = LocalProfileNotification(42, LocalProfileNotification.Operation.Enable,
            "carrier.example", "test-iccid")
        val records = mutableListOf(notification, LocalProfileNotification(43,
            LocalProfileNotification.Operation.Disable, "second.carrier.example", "second-iccid"))
        val lpa = object : LocalProfileAssistant by MockLpa() {
            override val notifications get() = records.toList()
            override fun handleNotification(seqNumber: Long): Boolean {
                requests.add(seqNumber)
                if (networkFailure) throw java.io.IOException("Network unavailable")
                return succeeds
            }
            override fun deleteNotification(seqNumber: Long): Boolean {
                deleted.add(seqNumber)
                records.removeAll { it.seqNumber == seqNumber }
                return true
            }
        }
        val channel = MockEuiccChannel(1, 0, lpa)
        val manager = MockEuiccChannelManager(channel)
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        val controller = Robolectric.buildActivity(NotificationsActivity::class.java,
            Intent(app, NotificationsActivity::class.java).putExtra("logicalSlotId", 1))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        fun find(id: Int): TextView? = views(activity.window.decorView).filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == activity.getString(id) }
        suspend fun verifyReopened(resultId: Int) {
            val reopened = Robolectric.buildActivity(NotificationsActivity::class.java,
                Intent(app, NotificationsActivity::class.java).putExtra("logicalSlotId", 1))
            reopened.get().setTheme(R.style.Theme_OpenEUICC)
            val newPage = reopened.setup().get()
            try {
                awaitMainLooper { views(newPage.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == newPage.getString(resultId) } }
            } finally { reopened.pause().stop().destroy() }
        }
        try {
            awaitMainLooper { find(R.string.minimal_send) != null }
            assertNotNull(find(R.string.notification_delivery_unknown))
            val card = activity.findViewById<ViewGroup>(R.id.notifications_card)
            assertEquals("Slot header and all notifications must share one card", 1, (card.parent as ViewGroup).childCount)
            for (value in listOf("test-iccid", "second-iccid", "carrier.example", "second.carrier.example")) {
                val detail = views(card).filterIsInstance<TextView>().first { it.text.toString().contains(": $value\u00A0") }
                val text = detail.text as android.text.Spanned
                val copy = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).single()
                copy.onClick(detail)
                assertEquals(value, activity.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
                assertNull("Copy must stay on the notification list", shadowOf(activity).nextStartedActivity)
            }
            find(R.string.minimal_send)!!.performClick()
            awaitMainLooper { ShadowToast.getTextOfLatestToast() == activity.getString(R.string.minimal_error) }
            assertNull(find(R.string.notification_delivery_success))
            assertNotNull(find(R.string.notification_delivery_failed))
            assertNull(find(R.string.minimal_resend))
            verifyReopened(R.string.notification_delivery_failed)
            succeeds = true
            find(R.string.minimal_send)!!.performClick()
            awaitMainLooper { views(activity.window.decorView).filterIsInstance<TextView>()
                .none { it.text.toString().contains(": test-iccid") } }
            assertNull(find(R.string.notification_delivery_success))
            assertNull(find(R.string.notification_delivery_failed))
            assertNotNull("An unconfirmed notification stays visible", find(R.string.notification_delivery_unknown))
            verifyReopened(R.string.notification_delivery_unknown)
            assertEquals(listOf(42L, 42L), requests)
            assertEquals(listOf(42L), deleted)
            assertTrue(manager.logicalChannelRequests.isNotEmpty())
            assertTrue(manager.logicalChannelRequests.all { it == 1 })
            networkFailure = true
            find(R.string.minimal_send)!!.performClick()
            awaitMainLooper { find(R.string.notification_delivery_failed) != null }
            assertNull(find(R.string.minimal_resend))
            verifyReopened(R.string.notification_delivery_failed)
            assertEquals(listOf(42L, 42L, 43L), requests)
            assertEquals(listOf(42L), deleted)
            find(R.string.minimal_delete_record)!!.performClick()
            val cancelled = ShadowDialog.getLatestDialog()
            assertEquals(listOf(42L), deleted)
            cancelled.findViewById<View>(android.R.id.button2).performClick()
            awaitMainLooper { !cancelled.isShowing }
            assertEquals(listOf(42L), deleted)
            find(R.string.minimal_delete_record)!!.performClick()
            val confirmation = ShadowDialog.getLatestDialog()
            confirmation.findViewById<View>(android.R.id.button1).performClick()
            confirmation.findViewById<View>(android.R.id.button1).performClick()
            awaitMainLooper { find(R.string.minimal_empty_notifications) != null }
            assertEquals(listOf(42L, 43L), deleted)
            val emptyCard = activity.findViewById<ViewGroup>(R.id.notifications_card)
            assertEquals(1, (emptyCard.parent as ViewGroup).childCount)
            assertTrue(views(emptyCard).contains(find(R.string.minimal_empty_notifications)!!))
        } finally { controller.pause().stop().destroy() }
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
}
