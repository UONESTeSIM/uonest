package im.angry.openeuicc.ui.wizard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.core.net.toUri
import android.widget.EditText
import org.robolectric.shadows.ShadowDialog
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
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

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class ScanDirectDownloadTest {
    private val code = "LPA:1\$smdp.example\$scan-token"

    @Test fun `obsolete review state returns to acquisition without starting a download`() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val saved = android.os.Bundle().apply {
            putString("screen", "review")
            putString("previous", "manual")
            putString("parsed", code)
            putString("draft", code)
        }
        val activity = controller.create(saved).start().resume().visible().get()
        try {
            assertEquals(activity.getString(R.string.add_esim_title), activity.title.toString())
            assertTrue(activity.findViewById<View>(R.id.add_esim_scan).isShown)
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertNull("Restoring an obsolete page must not download without a new user action",
                shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `manual entry expands inline and retains the draft after collapse and recreation`() {
        val app = RuntimeEnvironment.getApplication()
        var controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        var activity = controller.setup().visible().get()
        try {
            assertFalse(activity.findViewById<View>(R.id.add_esim_code).isShown)
            val scan = activity.findViewById<View>(R.id.add_esim_scan)
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            assertSame("Expanding must retain the acquisition choices on this page", scan,
                activity.findViewById<View>(R.id.add_esim_scan))
            assertTrue(scan.isShown)
            assertTrue(activity.findViewById<View>(R.id.add_esim_image).isShown)
            assertTrue(activity.findViewById<View>(R.id.add_esim_code).isShown)
            assertEquals(activity.getString(R.string.add_esim_title), activity.title.toString())
            activity.findViewById<EditText>(R.id.add_esim_code).setText(code)
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
            assertFalse(activity.findViewById<View>(R.id.add_esim_code).isShown)
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            val saved = android.os.Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = Robolectric.buildActivity(AddEsimActivity::class.java,
                AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
            controller.get().setTheme(R.style.Theme_OpenEUICC)
            controller.create(saved).start().restoreInstanceState(saved).resume().visible()
            activity = controller.get()
            assertTrue(activity.findViewById<View>(R.id.add_esim_code).isShown)
            assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString())
            assertNull("Expanding and restoring the draft must not begin downloading",
                shadowOf(activity).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `camera result skips review and preserves the home slot`() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            activity.findViewById<View>(R.id.add_esim_scan).performClick()
            val scan = shadowOf(activity).nextStartedActivityForResult
            activity.activityResultRegistry.dispatchResult(scan.requestCode, Activity.RESULT_OK,
                Intent().putExtra("SCAN_RESULT", code).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
            val download = shadowOf(activity).nextStartedActivity
            assertEquals(DownloadWizardActivity::class.java.name, download.component!!.className)
            assertEquals(code, download.data.toString())
            assertEquals(1 shl 16, download.getIntExtra("selectedLogicalSlot", -1))
            assertTrue(download.getBooleanExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, false))
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `invalid camera code cannot start a download`() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            activity.findViewById<View>(R.id.add_esim_scan).performClick()
            val scan = shadowOf(activity).nextStartedActivityForResult
            // Robolectric also records startActivityForResult in the normal launch queue.
            shadowOf(activity).nextStartedActivity
            activity.activityResultRegistry.dispatchResult(scan.requestCode, Activity.RESULT_OK,
                Intent().putExtra("SCAN_RESULT", "invalid").putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `image QR result skips review and preserves slot and confirmation requirement`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val imageCode = "$code\$\$1"
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(imageCode,
            com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(512 * 512) { index ->
            if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        val file = java.io.File(app.cacheDir, "confirmation-qr.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            activity.findViewById<View>(R.id.add_esim_image).performClick()
            val picker = shadowOf(activity).nextStartedActivityForResult
            assertEquals("image/*", picker.intent.type)
            shadowOf(activity).nextStartedActivity
            activity.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK,
                Intent().setData(android.net.Uri.fromFile(file)))
            awaitMainLooper { activity.isFinishing }
            val download = shadowOf(activity).nextStartedActivity
            assertEquals(DownloadWizardActivity::class.java.name, download.component!!.className)
            assertEquals(1 shl 16, download.getIntExtra("selectedLogicalSlot", -1))
            assertTrue(download.getBooleanExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, false))
            assertEquals(imageCode, download.data.toString())
            assertTrue(parseActivationInput(download.data.toString()).confirmationCodeRequired)
        } finally {
            controller.pause().stop().destroy()
            file.delete()
        }
    }

    @Test fun `manual continue skips review and preserves slot and confirmation requirement`() {
        val app = RuntimeEnvironment.getApplication()
        for (requiresConfirmation in listOf(false, true)) {
            val activationCode = if (requiresConfirmation) "$code\$\$1" else code
            val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
                AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
            controller.get().setTheme(R.style.Theme_OpenEUICC)
            val activity = controller.setup().get()
            try {
                activity.findViewById<View>(R.id.add_esim_manual).performClick()
                activity.findViewById<EditText>(R.id.add_esim_code).setText("  $activationCode  ")
                activity.findViewById<View>(R.id.add_esim_next).performClick()
                val download = shadowOf(activity).nextStartedActivity
                assertEquals(DownloadWizardActivity::class.java.name, download.component!!.className)
                assertEquals(activationCode, download.data.toString())
                assertEquals(1 shl 16, download.getIntExtra("selectedLogicalSlot", -1))
                assertTrue(download.getBooleanExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, false))
                assertEquals(requiresConfirmation, parseActivationInput(download.data.toString()).confirmationCodeRequired)
                assertTrue(activity.isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `invalid manual input stays editable and cannot start a download`() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java,
            AddEsimActivity.newIntent(app, 1, EuiccChannel.SecureElementId.DEFAULT))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            activity.findViewById<View>(R.id.add_esim_manual).performClick()
            val input = activity.findViewById<EditText>(R.id.add_esim_code)
            for (value in listOf("", "invalid", "LPA:1\$bad host\$token")) {
                input.setText(value)
                activity.findViewById<View>(R.id.add_esim_next).performClick()
                assertNull(shadowOf(activity).nextStartedActivity)
                assertEquals(activity.getString(R.string.add_esim_invalid), input.error.toString())
                assertFalse(activity.isFinishing)
                assertTrue(input.isEnabled)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun bindService(manager: EuiccChannelManager): EuiccChannelManagerService {
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        return service
    }

    private fun directIntent(activationCode: String = code) =
        DownloadWizardActivity.newIntent(RuntimeEnvironment.getApplication(), 1).apply {
            data = activationCode.toUri()
            putExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, true)
        }

    @Test fun `direct download uses selected logical slot and its physical port without selection pages`() = runBlocking {
        val lpa = MockLpa()
        val manager = MockEuiccChannelManager(MockEuiccChannel(1, 2, lpa))
        val service = bindService(manager)
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent())
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper {
                activity.supportFragmentManager.findFragmentById(R.id.step_fragment_container) is DownloadWizardProgressFragment &&
                    manager.logicalChannelRequests.size >= 2
            }
            shadowOf(Looper.getMainLooper()).idle()
            service.onStartCommand(Intent(), 0, 1)
            awaitMainLooper { lpa.downloadReturned.isCompleted }
            assertEquals("smdp.example", lpa.downloadInput!!.address)
            assertEquals("scan-token", lpa.downloadInput!!.matchingId)
            assertTrue(manager.logicalChannelRequests.all { it == 1 })
            assertTrue(manager.physicalChannelRequests.isNotEmpty())
            assertTrue(manager.physicalChannelRequests.all { it == 1 to 2 })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `unavailable selected slot stops without using another chip`() = runBlocking {
        val lpa = MockLpa()
        val base = MockEuiccChannelManager(MockEuiccChannel(0, 0, lpa))
        val requested = mutableListOf<Int>()
        val manager = object : EuiccChannelManager by base {
            override suspend fun <R> withEuiccChannel(logicalSlotId: Int, seId: EuiccChannel.SecureElementId,
                                                      fn: suspend (EuiccChannel) -> R): R {
                requested.add(logicalSlotId)
                throw EuiccChannelManager.EuiccChannelNotFoundException()
            }
        }
        bindService(manager)
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent())
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.isFinishing }
            assertEquals(listOf(1), requested)
            assertFalse(lpa.downloadStarted.isCompleted)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `confirmation dialog requires a code and continues on the original slot`() = runBlocking {
        val lpa = MockLpa()
        val manager = MockEuiccChannelManager(MockEuiccChannel(1, 2, lpa))
        val service = bindService(manager)
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent("$code\$\$1"))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper {
                activity.supportFragmentManager.findFragmentById(R.id.step_fragment_container) is DownloadWizardConfirmationFragment
            }
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.download_wizard_navigation).visibility)
            val input = dialog.findViewById<EditText>(R.id.download_confirmation_input)
            val next = dialog.findViewById<View>(android.R.id.button1)
            assertFalse(next.isEnabled)
            input.setText("   ")
            assertFalse(next.isEnabled)
            assertFalse(lpa.downloadStarted.isCompleted)
            input.setText("secret")
            assertTrue(next.isEnabled)
            next.performClick()
            next.performClick()
            awaitMainLooper { manager.logicalChannelRequests.size >= 3 }
            shadowOf(Looper.getMainLooper()).idle()
            service.onStartCommand(Intent(), 0, 1)
            awaitMainLooper { lpa.downloadReturned.isCompleted }
            assertEquals("secret", lpa.downloadInput!!.confirmationCode)
            assertEquals("scan-token", lpa.downloadInput!!.matchingId)
            assertTrue(manager.logicalChannelRequests.all { it == 1 })
            assertTrue(manager.physicalChannelRequests.all { it == 1 to 2 })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `cancelling the confirmation dialog never starts a download`() = runBlocking {
        val lpa = MockLpa()
        bindService(MockEuiccChannelManager(MockEuiccChannel(1, 2, lpa)))
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent("$code\$\$1"))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper {
                activity.supportFragmentManager.findFragmentById(R.id.step_fragment_container) is DownloadWizardConfirmationFragment
            }
            val dialog = ShadowDialog.getLatestDialog()
            assertNull(dialog.findViewById<View>(android.R.id.button2))
            dialog.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            assertFalse(lpa.downloadStarted.isCompleted)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `confirmation input survives recreation without starting a download`() = runBlocking {
        val lpa = MockLpa()
        bindService(MockEuiccChannelManager(MockEuiccChannel(1, 2, lpa)))
        var controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent("$code\$\$1"))
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        controller.setup()
        try {
            awaitMainLooper {
                controller.get().supportFragmentManager.findFragmentById(R.id.step_fragment_container) is DownloadWizardConfirmationFragment
            }
            ShadowDialog.getLatestDialog().findViewById<EditText>(R.id.download_confirmation_input).setText("keep-me")
            val saved = android.os.Bundle()
            controller.pause().saveInstanceState(saved).stop().destroy()
            controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent("$code\$\$1"))
            // Library tests do not inherit the host app's application theme.
            controller.get().setTheme(R.style.Theme_OpenEUICC)
            controller.create(saved).start().restoreInstanceState(saved).resume().visible()
            awaitMainLooper {
                controller.get().supportFragmentManager.findFragmentById(R.id.step_fragment_container) is DownloadWizardConfirmationFragment
            }
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.isShowing)
            assertEquals("keep-me", dialog.findViewById<EditText>(R.id.download_confirmation_input).text.toString())
            assertFalse(lpa.downloadStarted.isCompleted)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun `old wizard entry redirects to editable add flow without starting download`() = runBlocking {
        val lpa = MockLpa()
        bindService(MockEuiccChannelManager(MockEuiccChannel(1, 0, lpa)))
        val intent = Intent(RuntimeEnvironment.getApplication(), DownloadWizardActivity::class.java)
            .setData(code.toUri())
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, intent)
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.setup().get()
        try {
            awaitMainLooper { activity.isFinishing }
            val redirected = shadowOf(activity).nextStartedActivity
            assertEquals(DownloadTargetActivity::class.java.name, redirected.component!!.className)
            assertEquals(code, redirected.data.toString())
            assertFalse(lpa.downloadStarted.isCompleted)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `saved removed page cannot restart a download after update`() = runBlocking {
        val lpa = MockLpa()
        bindService(MockEuiccChannelManager(MockEuiccChannel(1, 0, lpa)))
        val saved = android.os.Bundle().apply {
            putString("currentStepFragmentClassName", "im.angry.openeuicc.ui.wizard.DownloadWizardDetailsFragment")
        }
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, directIntent())
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        val activity = controller.create(saved).start().restoreInstanceState(saved).resume().get()
        try {
            awaitMainLooper { activity.isFinishing }
            assertFalse(lpa.downloadStarted.isCompleted)
        } finally { controller.pause().stop().destroy() }
    }

}
