package im.angry.openeuicc.ui.wizard

import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.*
import kotlinx.coroutines.flow.flowOf
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
class DownloadTargetActivityTest {
    private val code = "LPA:1\$smdp.example\$test-token"
    private var available = listOf(0, 1, EuiccChannelManager.USB_CHANNEL_ID)
    private val channels = available.associateWith { MockEuiccChannel(it, 0) }
    private val base = MockEuiccChannelManager(channels.getValue(0))
    private val manager = object : EuiccChannelManager by base {
        override fun flowAllOpenEuiccPorts() = flowOf(*available.map { it to 0 }.toTypedArray())
        override suspend fun <R> withEuiccChannel(physicalSlotId: Int, portId: Int,
            seId: EuiccChannel.SecureElementId, fn: suspend (EuiccChannel) -> R): R =
            withEuiccChannel(physicalSlotId, seId, fn)
        override suspend fun <R> withEuiccChannel(logicalSlotId: Int,
            seId: EuiccChannel.SecureElementId, fn: suspend (EuiccChannel) -> R): R {
            if (logicalSlotId !in available) throw EuiccChannelManager.EuiccChannelNotFoundException()
            return fn(channels.getValue(logicalSlotId))
        }
    }

    private fun bind() {
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
    }

    private fun controller(intent: Intent = Intent()) = Robolectric.buildActivity(DownloadTargetActivity::class.java, intent).also {
        it.get().setTheme(R.style.Theme_OpenEUICC)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun option(activity: DownloadTargetActivity, slot: Int): View {
        val title = if (slot == EuiccChannelManager.USB_CHANNEL_ID) activity.getString(R.string.usb_tab_title)
            else (RuntimeEnvironment.getApplication() as TestOpenEuiccApplication)
                .appContainer.customizableTextProvider.formatNonUsbChannelName(slot)
        return views(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == title }.parent as View
    }

    @Test fun `shortcut opens methods inline without a dialog and switches targets without losing draft`() = runBlocking {
        bind()
        val controller = controller()
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            assertNull(ShadowDialog.getLatestDialog())
            assertNotNull(activity.findViewById<View>(R.id.add_esim_image))
            assertNotNull(activity.findViewById<View>(R.id.add_esim_manual))
            assertTrue(option(activity, 0).isSelected)
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            activity.findViewById<EditText>(R.id.add_esim_code).setText(code)
            option(activity, 1).performClick()
            assertTrue(option(activity, 1).isSelected)
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            activity.findViewById<View>(R.id.add_esim_next).performClick()
            awaitMainLooper { activity.isFinishing }
            val download = shadowOf(activity).nextStartedActivity
            assertEquals(DownloadWizardActivity::class.java.name, download.component!!.className)
            assertEquals(1 shl 16, download.getIntExtra("selectedLogicalSlot", -1))
            assertEquals(code, download.data.toString())
            assertTrue(download.getBooleanExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, false))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `last used reader is selected and camera result keeps that target`() = runBlocking {
        bind()
        RuntimeEnvironment.getApplication().getSharedPreferences("download_target", 0).edit()
            .putInt("lastTarget", EuiccChannelManager.USB_CHANNEL_ID shl 16).commit()
        val controller = controller()
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            assertTrue(option(activity, EuiccChannelManager.USB_CHANNEL_ID).isSelected)
            activity.findViewById<View>(R.id.add_esim_scan).performClick()
            val scan = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).nextStartedActivity
            activity.activityResultRegistry.dispatchResult(scan.requestCode, android.app.Activity.RESULT_OK,
                Intent().putExtra("SCAN_RESULT", code).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
            awaitMainLooper { activity.isFinishing }
            assertEquals(EuiccChannelManager.USB_CHANNEL_ID shl 16,
                shadowOf(activity).nextStartedActivity.getIntExtra("selectedLogicalSlot", -1))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `unavailable previous target falls back only when starting a new add flow`() = runBlocking {
        available = listOf(1)
        bind()
        RuntimeEnvironment.getApplication().getSharedPreferences("download_target", 0).edit()
            .putInt("lastTarget", EuiccChannelManager.USB_CHANNEL_ID shl 16).commit()
        val controller = controller()
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            assertTrue(option(activity, 1).isSelected)
            assertFalse(option(activity, 0).isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `removed target preserves code and requires explicit reselection instead of downloading elsewhere`() = runBlocking {
        bind()
        val controller = controller()
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            activity.findViewById<EditText>(R.id.add_esim_code).setText(code)
            available = listOf(1)
            activity.findViewById<View>(R.id.add_esim_next).performClick()
            awaitMainLooper { views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == activity.getString(R.string.download_target_choose) } }
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertFalse(option(activity, 1).isSelected)
            assertFalse(activity.findViewById<View>(R.id.add_esim_next).isEnabled)
            assertNull(shadowOf(activity).nextStartedActivity)
            option(activity, 1).performClick()
            assertTrue(activity.findViewById<View>(R.id.add_esim_next).isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `rotation retains inline selection and activation draft without downloading`() = runBlocking {
        bind()
        var controller = controller()
        var activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            option(activity, 1).performClick()
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            activity.findViewById<EditText>(R.id.add_esim_code).setText(code)
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = controller()
            activity = controller.create(saved).start().restoreInstanceState(saved).resume().get()
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            assertTrue(option(activity, 1).isSelected)
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `no available card disables acquisition without opening a dialog`() = runBlocking {
        available = emptyList()
        bind()
        val controller = controller()
        val activity = controller.setup().get()
        try {
            awaitMainLooper { views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == activity.getString(R.string.download_target_none) } }
            assertFalse(activity.findViewById<View>(R.id.add_esim_scan).isEnabled)
            assertFalse(activity.findViewById<View>(R.id.add_esim_image).isEnabled)
            assertFalse(activity.findViewById<View>(R.id.add_esim_next).isEnabled)
            assertNull(ShadowDialog.getLatestDialog())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `external activation links resolve directly to the new add page`() {
        val app = RuntimeEnvironment.getApplication()
        for (scheme in listOf("lpa", "LPA")) {
            val link = Intent(Intent.ACTION_VIEW, "$scheme:${code.substringAfter(':')}".toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
            val resolved = app.packageManager.resolveActivity(link, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            assertEquals(DownloadTargetActivity::class.java.name, resolved?.activityInfo?.name)
        }
    }

    @Test fun `external link waits for start then uses selected slot and confirmation dialog`() = runBlocking {
        bind()
        val confirmationCode = "$code\$\$1"
        val controller = controller(Intent(Intent.ACTION_VIEW, confirmationCode.toUri()))
        val activity = controller.setup().visible().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_next).isEnabled }
            assertTrue(activity.findViewById<EditText>(R.id.add_esim_code).isShown)
            assertEquals(confirmationCode, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertNull(activity.findViewById<View>(R.id.add_esim_scan))
            assertNull(activity.findViewById<View>(R.id.add_esim_image))
            assertNull(activity.findViewById<View>(R.id.add_esim_manual))
            assertNull(ShadowDialog.getLatestDialog())
            assertNull("Opening a link must not start downloading", shadowOf(activity).nextStartedActivity)
            option(activity, 1).performClick()
            activity.findViewById<View>(R.id.add_esim_next).performClick()
            awaitMainLooper { activity.isFinishing }
            val download = shadowOf(activity).nextStartedActivity
            assertEquals(1 shl 16, download.getIntExtra("selectedLogicalSlot", -1))
            assertEquals(confirmationCode, download.data.toString())
            val wizard = Robolectric.buildActivity(DownloadWizardActivity::class.java, download)
            wizard.get().setTheme(R.style.Theme_OpenEUICC)
            val page = wizard.setup().get()
            try {
                awaitMainLooper { page.supportFragmentManager.findFragmentById(R.id.step_fragment_container) is
                    DownloadWizardConfirmationFragment }
                val dialog = ShadowDialog.getLatestDialog()
                assertTrue(dialog.isShowing)
                assertFalse(dialog.findViewById<View>(android.R.id.button1).isEnabled)
                assertTrue(channels.values.all { !(it.lpa as MockLpa).downloadStarted.isCompleted })
            } finally { wizard.pause().stop().destroy() }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `recreating external link keeps edited code rather than filling original again`() = runBlocking {
        bind()
        val link = Intent(Intent.ACTION_VIEW, code.toUri())
        var controller = controller(link)
        var activity = controller.setup().get()
        val edited = "LPA:1\$smdp.example\$edited-token"
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_next).isEnabled }
            activity.findViewById<EditText>(R.id.add_esim_code).setText(edited)
            option(activity, 1).performClick()
            val saved = Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = controller(link)
            activity = controller.create(saved).start().restoreInstanceState(saved).resume().get()
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_next).isEnabled }
            assertNull(activity.findViewById<View>(R.id.add_esim_scan))
            assertNull(activity.findViewById<View>(R.id.add_esim_image))
            assertNull(activity.findViewById<View>(R.id.add_esim_manual))
            assertEquals(edited, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertTrue(option(activity, 1).isSelected)
            assertNull(shadowOf(activity).nextStartedActivity)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue("Back leaves the link page instead of collapsing its only input", activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `malformed external link shows an error and cannot auto start`() = runBlocking {
        bind()
        val controller = controller(Intent(Intent.ACTION_VIEW, "lpa:1\$bad%20host\$token".toUri()))
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.findViewById<View>(R.id.add_esim_scan).isEnabled }
            assertNotNull(activity.findViewById<View>(R.id.add_esim_image))
            assertNotNull(activity.findViewById<View>(R.id.add_esim_manual))
            assertEquals(activity.getString(R.string.add_esim_invalid),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            assertEquals("", activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertNull(ShadowDialog.getLatestDialog())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `legacy external component alias opens the new add page`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app.packageName, "im.angry.openeuicc.ui.DirectProfileDownloadActivity"), 0)
        assertTrue(info.exported)
        assertEquals(DownloadTargetActivity::class.java.name, info.targetActivity)
    }

}
