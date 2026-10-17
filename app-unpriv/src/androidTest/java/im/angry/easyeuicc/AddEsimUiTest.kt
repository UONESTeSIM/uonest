package im.angry.easyeuicc

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import im.angry.openeuicc.common.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddEsimUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val code = "LPA:1\$demo.example\$TEST-TOKEN"
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun launch() = ActivityScenario.launch<AddEsimPreviewActivity>(Intent(context, AddEsimPreviewActivity::class.java))
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(300) }
    private fun screenshot(name: String) {
        settle()
        val dir = File(context.getExternalFilesDir(null), "ui-verification").apply { mkdirs() }
        File(dir, name).outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun assertReview(scenario: ActivityScenario<AddEsimPreviewActivity>) {
        repeat(40) {
            var ready = false
            scenario.onActivity { activity ->
                ready = views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "demo.example" }
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        fail("Decoded activation information must reach review")
    }

    @Test fun manualEntryRejectsInvalidCodeAndDemoOnlyLaunchesAnimation() {
        launch().use { scenario ->
            screenshot("add-esim-methods.png")
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.add_esim_scan))
                assertNotNull(activity.findViewById<View>(R.id.add_esim_image))
                activity.findViewById<View>(R.id.add_esim_manual).performClick()
                activity.findViewById<EditText>(R.id.add_esim_code).setText("invalid")
                activity.findViewById<View>(R.id.add_esim_next).performClick()
                assertNotNull(activity.findViewById<EditText>(R.id.add_esim_code).error)
                activity.findViewById<EditText>(R.id.add_esim_code).setText(code)
                assertNull("Editing the code must clear the stale error marker", activity.findViewById<EditText>(R.id.add_esim_code).error)
            }
            scenario.recreate()
            scenario.onActivity { activity -> assertEquals(code, activity.findViewById<EditText>(R.id.add_esim_code).text.toString()) }
            screenshot("add-esim-manual.png")
            scenario.onActivity { it.findViewById<View>(R.id.add_esim_next).performClick() }
            assertReview(scenario)
            screenshot("add-esim-review.png")
            val preview = instrumentation.addMonitor(DownloadAnimationPreviewActivity::class.java.name,
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            val real = instrumentation.addMonitor("im.angry.openeuicc.ui.wizard.DownloadWizardActivity",
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                scenario.onActivity { it.findViewById<View>(R.id.add_esim_next).performClick() }
                assertEquals(1, preview.hits)
                assertEquals("Demo must not start a real download wizard", 0, real.hits)
            } finally {
                instrumentation.removeMonitor(preview); instrumentation.removeMonitor(real)
            }
        }
    }

    @Test fun scanResultIsDecodedBeforeReview() {
        val monitor = instrumentation.addMonitor("com.journeyapps.barcodescanner.CaptureActivity",
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().putExtra("SCAN_RESULT", code)), true)
        try {
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.add_esim_scan).performClick() }
                assertReview(scenario)
                assertEquals(1, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun selectedImageIsActuallyDecodedAndPickerCancellationStaysOnMethods() {
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (y in 0 until 512) for (x in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "test-activation-qr.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val filter = IntentFilter(Intent.ACTION_GET_CONTENT).apply { addDataType("image/*"); addCategory(Intent.CATEGORY_OPENABLE) }
        val cancel = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.add_esim_image).performClick() }
                settle()
                scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.add_esim_manual)) }
            }
        } finally { instrumentation.removeMonitor(cancel) }
        val result = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
        try {
            launch().use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.add_esim_image).performClick() }
                assertReview(scenario)
                assertEquals(1, result.hits)
            }
        } finally { instrumentation.removeMonitor(result); file.delete() }
    }
}
