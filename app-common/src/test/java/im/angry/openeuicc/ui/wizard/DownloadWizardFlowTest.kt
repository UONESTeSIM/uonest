package im.angry.openeuicc.ui.wizard

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.core.net.toUri
import org.robolectric.shadows.ShadowDialog
import android.widget.EditText
import android.widget.Button
import android.widget.ProgressBar
import im.angry.openeuicc.common.R
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.testutil.MockEuiccChannel
import im.angry.openeuicc.testutil.MockEuiccChannelManager
import im.angry.openeuicc.testutil.MockLpa
import im.angry.openeuicc.testutil.TestOpenEuiccApplication
import im.angry.openeuicc.testutil.awaitMainLooper
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.LocalProfileAssistant
import net.typeblog.lpac_jni.ProfileDownloadInput
import net.typeblog.lpac_jni.ProfileDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises confirmation, real service dispatch, progress, success and inline failure diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestOpenEuiccApplication::class)
class DownloadWizardFlowTest {

    private val input = ProfileDownloadInput(
        address = "smdp.example.com",
        matchingId = "matching-id",
        imei = null,
        confirmationCode = "conf-code"
    )

    private lateinit var activity: DownloadWizardActivity
    private lateinit var service: EuiccChannelManagerService
    private lateinit var channel: MockEuiccChannel
    private lateinit var manager: MockEuiccChannelManager
    private lateinit var homeComponent: ComponentName

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        homeComponent = ComponentName(app.packageName, "im.angry.openeuicc.ui.MainActivity")
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName),
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = homeComponent.packageName
                    name = homeComponent.className
                }
            }
        )
        channel = MockEuiccChannel(slotId = 1, portId = 2)
        manager = MockEuiccChannelManager(channel)
        TestOpenEuiccApplication.mockEuiccChannelManager = manager

        // Robolectric does not auto-create services on bindService(); register the
        // REAL EuiccChannelManagerService (with the mock manager below it) as the
        // binding target so that BaseEuiccAccessActivity connects to it in-process.
        service = Robolectric.buildService(EuiccChannelManagerService::class.java).get()
        val bindIntent = Intent(
            RuntimeEnvironment.getApplication(),
            EuiccChannelManagerService::class.java
        )
        shadowOf(RuntimeEnvironment.getApplication())
            .setComponentNameAndServiceForBindServiceForIntent(
                bindIntent,
                ComponentName(
                    RuntimeEnvironment.getApplication(),
                    EuiccChannelManagerService::class.java
                ),
                service.onBind(Intent())
            )

        val intent = DownloadWizardActivity.newIntent(
            RuntimeEnvironment.getApplication(),
            logicalSlotId = 1
        ).apply {
            data = "LPA:1${'$'}${input.address}${'$'}${input.matchingId}${'$'}${'$'}1".toUri()
            putExtra(DownloadWizardActivity.EXTRA_DIRECT_DOWNLOAD, true)
        }
        val controller = Robolectric.buildActivity(DownloadWizardActivity::class.java, intent)
        // The activity does not declare a theme itself; it inherits the host
        // app's application-level theme (Theme.OpenEUICC), which Robolectric
        // does not merge in for a library module. AppCompatActivity requires an
        // AppCompat theme, so apply it before onCreate() runs.
        controller.get().setTheme(R.style.Theme_OpenEUICC)
        activity = controller.setup().get()
        idle()

        // Wait until the in-process service connection is up and onInit() has run.
        runBlocking {
            awaitMainLooper { activity.euiccChannelManagerLoaded.isCompleted }
        }
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun currentFragment(): DownloadWizardActivity.DownloadWizardStepFragment? =
        activity.supportFragmentManager
            .findFragmentById(R.id.step_fragment_container)
            as? DownloadWizardActivity.DownloadWizardStepFragment

    /** Start only after the user supplies the required confirmation code. */
    private suspend fun startDownload() {
        awaitMainLooper { currentFragment() is DownloadWizardConfirmationFragment }
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        dialog.findViewById<EditText>(R.id.download_confirmation_input).setText(input.confirmationCode)
        dialog.findViewById<View>(android.R.id.button1).performClick()
        awaitMainLooper { currentFragment() is DownloadWizardProgressFragment }

        // The service self-starts via startForegroundService(); Robolectric does
        // not deliver that to onStartCommand() on its own (see
        // DownloadTaskLauncherTest), so simulate the system call once the task
        // has had a chance to subscribe to foregroundStarted. The mock manager
        // having resolved the logical slot proves the launch has been reached.
        awaitMainLooper { manager.logicalChannelRequests.isNotEmpty() }
        idle()
        service.onStartCommand(Intent(), 0, 1)
        idle()
    }

    private fun assertAnimationResult(result: DownloadAnimationView.Result) {
        idle()
        val animation = activity.findViewById<DownloadAnimationView>(R.id.download_animation)
        assertEquals(result, animation.result)
        assertEquals("The old bottom progress bar must remain hidden", View.GONE,
            activity.findViewById<ProgressBar>(R.id.progress).visibility)
        if (result != DownloadAnimationView.Result.Downloading) {
            assertTrue("Motion must stop when the real task completes", !animation.isAnimating)
        }
    }

    @Test
    fun `wizard runs the full flow through the real service to a completed download`() = runBlocking {
        val lpa = channel.lpa as MockLpa

        startDownload()

        // ---- The download must complete and the UI must reflect it ----
        awaitMainLooper { lpa.downloadReturned.isCompleted }
        awaitMainLooper { currentFragment()?.hasNext == true }

        // The LPA received the exact input the UI passed, and the metadata
        // confirmation was auto-approved through the back channel.
        assertEquals(input, lpa.downloadInput)
        assertEquals(true, lpa.awaitDownloadResult())

        // The service resolved the physical slot/port through the manager and
        // launched the download with them.
        assertEquals(1 to 2, manager.physicalChannelRequests.first())

        assertAnimationResult(DownloadAnimationView.Result.Success)
        // hasNext is true and no error -> the Next button must be visible so the
        // user can leave the (successful) download screen.
        assertEquals(
            View.VISIBLE,
            activity.findViewById<Button>(R.id.download_wizard_next).visibility
        )

        assertFalse("Keep the success check visible before returning home", activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1900))
        assertFalse("The success check should remain visible for 2 seconds", activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue("Success should return without requiring a button press", activity.isFinishing)
    }

    @Test
    fun `wizard streams intermediate progress while the download is still running`() = runBlocking {
        val lpa = channel.lpa as MockLpa
        // Simulate a real download that walks through Preparing, Connecting and
        // Authenticating before asking for metadata confirmation, and park it at
        // Authenticating so we can inspect the UI mid-download.
        lpa.preConfirmationStates = listOf(
            ProfileDownloadState.Preparing(),
            ProfileDownloadState.Connecting(),
            ProfileDownloadState.Authenticating(),
        )
        lpa.holdAt = ProfileDownloadState.Authenticating()

        startDownload()

        // Wait until the download has actually reached (and is parked at) the
        // authentication step.
        awaitMainLooper { lpa.isStateReached(ProfileDownloadState.Authenticating()) }

        assertAnimationResult(DownloadAnimationView.Result.Downloading)
        assertEquals(activity.getString(R.string.download_wizard_progress_step_authenticating),
            activity.findViewById<DownloadAnimationView>(R.id.download_animation).contentDescription)
        assertEquals(activity.getString(R.string.download_wizard_progress_step_authenticating),
            activity.findViewById<android.widget.TextView>(R.id.download_status).text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.download_return_home).visibility)
        assertEquals("The user cannot advance while a download is active", View.GONE,
            activity.findViewById<Button>(R.id.download_wizard_next).visibility)

        // Let the download continue; it must complete normally afterwards.
        lpa.releaseHold()
        awaitMainLooper { lpa.downloadReturned.isCompleted }
        awaitMainLooper { currentFragment()?.hasNext == true }
        assertEquals(true, lpa.awaitDownloadResult())
        assertAnimationResult(DownloadAnimationView.Result.Success)
    }

    @Test
    fun `wizard replaces the chip with failure and retains diagnostics when download fails`() = runBlocking {
        openFailedDiagnostics()
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("System back must leave diagnostics instead of returning to failure", activity.isFinishing)
        assertHomeOpened()
    }

    @Test
    fun `inline diagnostics copy includes the full error without leaving the failure state`() = runBlocking {
        openFailedDiagnostics()
        val expected = activity.findViewById<android.widget.TextView>(R.id.download_wizard_diagnostics_text).text.toString()
        activity.findViewById<View>(R.id.download_failure_diagnostics_copy).performClick()
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        org.junit.Assert.assertNull("Copy must not open a file picker", shadowOf(activity).nextStartedActivityForResult)
        assertTrue(currentFragment() is DownloadWizardProgressFragment)
        assertFalse(activity.isFinishing)
    }

    private fun assertHomeOpened() {
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Returning must open the app home even when launched externally", intent)
        assertEquals(homeComponent, intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test fun `failure home button exits to the launcher without retrying download`() = runBlocking {
        openFailedDiagnostics()
        activity.findViewById<View>(R.id.download_return_home).performClick()
        assertTrue(activity.isFinishing)
        assertHomeOpened()
    }

    private suspend fun openFailedDiagnostics() {
        val lpa = channel.lpa as MockLpa
        // The SM-DP+ rejects the device during authentication (step 3), i.e.
        // before any metadata confirmation happens -- this is the failure mode
        // that used to leave the progress UI stuck with no checkmarks at all.
        lpa.preConfirmationStates = listOf(
            ProfileDownloadState.Preparing(),
            ProfileDownloadState.Connecting(),
            ProfileDownloadState.Authenticating(),
        )
        lpa.failAfter = ProfileDownloadState.Authenticating()
        lpa.failure = LocalProfileAssistant.ProfileDownloadException(
            lpaErrorReason = "ES10B_ERROR_REASON_UNSUPPORTED_CRT_VALUES",
            lastHttpResponse = null,
            lastHttpException = null,
            lastApduResponse = null,
            lastApduException = null,
        )

        startDownload()

        // Failure exposes diagnostics beside the result instead of in bottom navigation.
        awaitMainLooper {
            activity.findViewById<View>(R.id.download_failure_diagnostics)?.visibility == View.VISIBLE
        }

        assertAnimationResult(DownloadAnimationView.Result.Failure)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse("Failure must stay visible for the user to inspect", activity.isFinishing)
        assertTrue(currentFragment() is DownloadWizardProgressFragment)
        assertEquals(View.GONE, activity.findViewById<Button>(R.id.download_wizard_next).visibility)
        assertEquals(activity.getString(R.string.download_wizard_error_unsupported_profile),
            activity.findViewById<android.widget.TextView>(R.id.download_failure_reason).text.toString())
        assertEquals(activity.getString(R.string.download_wizard_error_suggest_contact_carrier),
            activity.findViewById<android.widget.TextView>(R.id.download_failure_advice).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.download_return_home).visibility)
        assertTrue("Full error details must be available on the failure page without a second step",
            activity.findViewById<android.widget.TextView>(R.id.download_wizard_diagnostics_text)
                .text.contains("ES10B_ERROR_REASON_UNSUPPORTED_CRT_VALUES"))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.download_wizard_navigation).visibility)
        assertEquals(activity.getString(R.string.download_diagnostics_information),
            activity.findViewById<android.widget.TextView>(R.id.download_wizard_diagnostics_title).text.toString())
    }
}
