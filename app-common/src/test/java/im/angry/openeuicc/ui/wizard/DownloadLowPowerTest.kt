package im.angry.openeuicc.ui.wizard

import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.net.toUri
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.MockEuiccChannel
import im.angry.openeuicc.testutil.MockEuiccChannelManager
import im.angry.openeuicc.testutil.MockLpa
import im.angry.openeuicc.testutil.TestOpenEuiccApplication
import im.angry.openeuicc.testutil.awaitMainLooper
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.ProfileDownloadCallback
import net.typeblog.lpac_jni.ProfileDownloadInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class, qualifiers = "zh-rCN")
class DownloadLowPowerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var lpa: MockLpa
    private lateinit var manager: MockEuiccChannelManager
    private lateinit var service: EuiccChannelManagerService
    private val downloadCalls = AtomicInteger()
    private var controller: ActivityController<DownloadWizardActivity>? = null
    private val activity get() = controller!!.get()
    private val dialog get() = ShadowDialog.getLatestDialog()

    @Before fun setUp() {
        // Robolectric does not grant this manifest-declared signature permission on API 28.
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        lpa = MockLpa()
        manager = MockEuiccChannelManager(MockEuiccChannel(1, 2, object : LocalProfileAssistant by lpa {
            override fun downloadProfile(input: ProfileDownloadInput, callback: ProfileDownloadCallback) {
                downloadCalls.incrementAndGet()
                lpa.downloadProfile(input, callback)
            }
        }))
        TestOpenEuiccApplication.mockEuiccChannelManager = manager
        service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(app, EuiccChannelManagerService::class.java),
            ComponentName(app, EuiccChannelManagerService::class.java), service.onBind(Intent()))
        battery(15)
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun battery(level: Int, scale: Int = 100, charging: Boolean = false) {
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
            putExtra(BatteryManager.EXTRA_STATUS, if (charging) BatteryManager.BATTERY_STATUS_CHARGING
                else BatteryManager.BATTERY_STATUS_DISCHARGING)
        })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saver(enabled: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(enabled)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun intent(confirmation: Boolean) = DownloadWizardActivity.newIntent(app, 1).apply {
        data = ("LPA:1\$smdp.example\$power-token" + if (confirmation) "\$\$1" else "").toUri()
        putExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, true)
    }

    private suspend fun launch(confirmation: Boolean = false) {
        controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, intent(confirmation)).apply {
            get().setTheme(R.style.Theme_OpenEUICC)
            setup()
        }
        awaitMainLooper { currentFragment() is DownloadWizardLowPowerFragment && dialog?.isShowing == true }
    }

    private fun currentFragment() = activity.supportFragmentManager.findFragmentById(R.id.step_fragment_container)
    private fun label(id: Int) = dialog.findViewById<TextView>(id).text.toString()

    @Test fun `low power requires a decision and hides the old wizard controls`() = runBlocking {
        launch()
        assertEquals("电量较低", label(android.R.id.title))
        assertEquals("当前电量 15%", label(android.R.id.summary))
        assertEquals(activity.getString(R.string.download_wizard_low_power_content), label(android.R.id.message))
        assertEquals("继续下载", label(android.R.id.button1))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.download_wizard_navigation).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.progress).visibility)
        assertFalse("Showing the warning must not dispatch a download", lpa.downloadStarted.isCompleted)
        dialog.findViewById<View>(android.R.id.button2).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertFalse(lpa.downloadStarted.isCompleted)
    }

    @Test fun `dismissal exits without downloading`() = runBlocking {
        launch()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertFalse(lpa.downloadStarted.isCompleted)
    }

    @Test @Config(sdk = [28, 35])
    fun `charging changes the advice without automatically approving download`() = runBlocking {
        launch()
        battery(15, charging = true)
        assertEquals("当前电量 15% · 正在充电", label(android.R.id.summary))
        assertEquals(activity.getString(R.string.download_low_power_charging_content), label(android.R.id.message))
        battery(15)
        assertEquals(activity.getString(R.string.download_wizard_low_power_content), label(android.R.id.message))
        assertFalse(lpa.downloadStarted.isCompleted)
        assertTrue(currentFragment() is DownloadWizardLowPowerFragment)
    }

    @Test fun `battery saver at normal charge is not described as low battery`() = runBlocking {
        battery(80)
        saver(true)
        launch()
        assertEquals("已开启省电模式", label(android.R.id.title))
        assertEquals(activity.getString(R.string.download_low_power_saver_content), label(android.R.id.message))
        saver(false)
        assertEquals("电源状态", label(android.R.id.title))
        assertFalse("Turning off battery saver must not approve a download", lpa.downloadStarted.isCompleted)
        assertTrue(dialog.isShowing)
    }

    @Test fun `battery scale and unavailable readings cannot produce false low battery warnings`() {
        battery(30, scale = 200)
        assertTrue(DownloadWizardLowPowerFragment.isBatteryLow(app))
        battery(20)
        assertTrue(DownloadWizardLowPowerFragment.isBatteryLow(app))
        battery(21)
        assertFalse(DownloadWizardLowPowerFragment.isBatteryLow(app))
        battery(-1)
        assertFalse(DownloadWizardLowPowerFragment.isBatteryLow(app))
        battery(0, scale = 0)
        assertFalse(DownloadWizardLowPowerFragment.isBatteryLow(app))
        saver(true)
        assertTrue("An unavailable battery reading must not hide battery saver", DownloadWizardLowPowerFragment.isBatteryLow(app))
    }

    @Test fun `continuing still requires the carrier confirmation code`() = runBlocking {
        launch(confirmation = true)
        assertEquals("继续", label(android.R.id.button1))
        dialog.findViewById<View>(android.R.id.button1).performClick()
        awaitMainLooper { currentFragment() is DownloadWizardConfirmationFragment }
        assertNotNull(dialog.findViewById<EditText>(R.id.download_confirmation_input))
        assertFalse(dialog.findViewById<Button>(android.R.id.button1).isEnabled)
        assertFalse("Accepting the power notice must not bypass the confirmation code", lpa.downloadStarted.isCompleted)
    }

    @Test fun `continue dispatches on the original slot and cannot be clicked twice`() = runBlocking {
        launch()
        val next = dialog.findViewById<Button>(android.R.id.button1)
        next.performClick()
        assertFalse(next.isEnabled)
        next.performClick()
        awaitMainLooper {
            currentFragment() is DownloadWizardProgressFragment && manager.logicalChannelRequests.size >= 3
        }
        service.onStartCommand(Intent(), 0, 1)
        awaitMainLooper { lpa.downloadReturned.isCompleted }
        assertEquals("power-token", lpa.downloadInput!!.matchingId)
        assertTrue(manager.physicalChannelRequests.isNotEmpty())
        assertTrue(manager.physicalChannelRequests.all { it == 1 to 2 })
        assertEquals("Repeated clicks must dispatch exactly one download", 1, downloadCalls.get())
    }

    @Test fun `recreation retains the power decision without starting a download`() = runBlocking {
        launch()
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, intent(false)).apply {
            get().setTheme(R.style.Theme_OpenEUICC)
            create(saved).start().restoreInstanceState(saved).resume().visible()
        }
        awaitMainLooper { currentFragment() is DownloadWizardLowPowerFragment && dialog.isShowing }
        assertEquals("当前电量 15%", label(android.R.id.summary))
        assertFalse(lpa.downloadStarted.isCompleted)
        battery(15, charging = true)
        assertEquals("当前电量 15% · 正在充电", label(android.R.id.summary))
    }

    @Test @Config(sdk = [35], fontScale = 1.8f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `large text can scroll on short screens without clipping actions`() = runBlocking {
        launch()
        val content = dialog.findViewById<android.widget.FrameLayout>(android.R.id.content)
        val scroll = content.getChildAt(0) as android.widget.ScrollView
        val density = activity.resources.displayMetrics.density
        scroll.measure(View.MeasureSpec.makeMeasureSpec((284 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
        assertTrue("The full notice and actions must remain available by scrolling",
            scroll.getChildAt(0).height > scroll.height)
        val next = dialog.findViewById<Button>(android.R.id.button1)
        val cancel = dialog.findViewById<Button>(android.R.id.button2)
        assertTrue("Large labels must fall back to separate rows", next.top >= cancel.bottom)
        for (id in listOf(android.R.id.button1, android.R.id.button2)) {
            val button = dialog.findViewById<Button>(id)
            assertTrue("Large button labels must fit within their touch targets",
                button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            for (line in 0 until button.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
        }
    }

    @Test @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `actions share a balanced row and stack when their labels no longer fit`() = runBlocking {
        launch()
        val content = dialog.findViewById<android.widget.FrameLayout>(android.R.id.content)
        val scroll = content.getChildAt(0) as android.widget.ScrollView
        val density = activity.resources.displayMetrics.density
        val next = dialog.findViewById<Button>(android.R.id.button1)
        val cancel = dialog.findViewById<Button>(android.R.id.button2)
        fun measure(width: Int) {
            scroll.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((480 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
        }
        measure(284)
        assertEquals(cancel.top, next.top)
        assertEquals((12 * density).toInt(), next.left - cancel.right)
        assertTrue(kotlin.math.abs(cancel.width - next.width) <= 1)
        measure(200)
        assertTrue("Narrow screens must keep both actions readable", next.top >= cancel.bottom)
        measure(284)
        assertEquals("Restoring space must restore the horizontal row", cancel.top, next.top)
        next.text = "Continue with the eSIM download"
        measure(284)
        assertTrue("Long translations must stack instead of being squeezed into half a row", next.top >= cancel.bottom)
        assertTrue(next.layout.height <= next.height - next.compoundPaddingTop - next.compoundPaddingBottom)
    }
}
