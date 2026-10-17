package im.angry.easyeuicc

import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.wizard.DownloadAnimationView
import im.angry.openeuicc.ui.wizard.DownloadProgressView
import im.angry.openeuicc.common.R
import im.angry.openeuicc.util.setupRootViewSystemBarInsets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only visual preview. Never binds the eUICC service or downloads a profile. */
class DownloadAnimationPreviewActivity : AppCompatActivity() {
    private lateinit var animation: DownloadAnimationView
    private lateinit var progressView: DownloadProgressView
    private var returnHomeJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("preview_confirmation", false)) {
            showConfirmationPreview(savedInstanceState?.getString("confirmation").orEmpty())
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (animation.result == DownloadAnimationView.Result.Failure) returnHome() else finish()
            }
        })
        if (intent.getBooleanExtra("preview_usb_download", false)) {
            showFailurePreview()
            progressView.showResult(DownloadAnimationView.Result.Downloading)
            lifecycleScope.launch {
                for (stage in intArrayOf(R.string.download_wizard_progress_step_connecting,
                    R.string.download_wizard_progress_step_authenticating,
                    R.string.download_wizard_progress_step_downloading,
                    R.string.download_wizard_progress_step_finalizing)) {
                    progressView.showStage("${getString(stage)}（读卡器演示）")
                    delay(1800)
                }
                showResult(DownloadAnimationView.Result.Success)
            }
            return
        }
        if (intent.getBooleanExtra("preview_failure", false)) {
            showFailurePreview()
            return
        }
        if (intent.getBooleanExtra("preview_download", false)) {
            showFailurePreview()
            progressView.showResult(DownloadAnimationView.Result.Downloading)
            progressView.showStage(getString(R.string.download_wizard_progress_step_connecting))
            return
        }
        if (intent.getBooleanExtra("preview_success", false)) {
            showFailurePreview()
            showResult(DownloadAnimationView.Result.Success)
            return
        }
        val ui = MinimalUi(this)
        val page = ui.column()
        page.addView(ui.text("下载动画预览 · 不执行实际下载", 13f, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, ui.dp(44)))
        progressView = DownloadProgressView(this)
        animation = progressView.animation
        page.addView(progressView, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = ui.actions(page).apply { gravity = Gravity.CENTER }
        ui.button(controls, "下载中") { showResult(DownloadAnimationView.Result.Downloading) }
        ui.button(controls, "成功") { showResult(DownloadAnimationView.Result.Success) }
        ui.button(controls, "失败") { showResult(DownloadAnimationView.Result.Failure) }
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            page.setPadding(bars.left, bars.top, bars.right, bars.bottom + ui.dp(8))
            insets
        }
    }

    private var confirmationForm: im.angry.openeuicc.ui.minimal.ConfirmationCodeView? = null
    private var confirmationDialog: im.angry.openeuicc.ui.minimal.ProfileEditorDialog? = null

    private fun showConfirmationPreview(initialCode: String) {
        val ui = MinimalUi(this)
        setContentView(FrameLayout(this).apply {
            addView(ui.text("确认码弹窗演示 · 不执行实际下载", 13f, true).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, ui.dp(80)).apply { topMargin = ui.dp(48) })
        })
        val dialog = im.angry.openeuicc.ui.minimal.ProfileEditorDialog(this)
        val form = im.angry.openeuicc.ui.minimal.ConfirmationCodeView(this, initialCode,
            onCodeChanged = {},
            onContinue = {
                dialog.dismiss()
                startActivity(Intent(this, DownloadAnimationPreviewActivity::class.java))
                finish()
            })
        confirmationForm = form
        confirmationDialog = dialog
        dialog.setContentView(ui.scroll(form))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { finish() }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        confirmationForm?.let { outState.putString("confirmation", it.input.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        confirmationDialog?.setOnCancelListener(null)
        confirmationDialog?.dismiss()
        super.onDestroy()
    }

    private fun showFailurePreview() {
        setContentView(R.layout.activity_download_wizard)
        val container = findViewById<FrameLayout>(R.id.step_fragment_container)
        val progress = DownloadProgressView(this).apply {
            showResult(DownloadAnimationView.Result.Failure,
                net.typeblog.lpac_jni.LocalProfileAssistant.ProfileDownloadException(
                    "ES10B_ERROR_REASON_UNDEFINED", null, java.net.SocketTimeoutException("Demo timeout"), null, null))
            returnHome.setOnClickListener { returnHome() }
        }
        progressView = progress
        animation = progress.animation
        container.addView(progress)
        findViewById<View>(R.id.progress).visibility = View.GONE
        findViewById<View>(R.id.download_wizard_navigation).visibility = View.GONE
        findViewById<View>(R.id.download_wizard_back).visibility = View.GONE
        val next = findViewById<Button>(R.id.download_wizard_next)
        next.visibility = View.GONE
        // Android may reset android.R.id.content padding on a later inset pass.
        // Handle insets at the window root and pad our own layout instead.
        val page = container.parent as View
        setupRootViewSystemBarInsets(window.decorView.rootView, arrayOf({ bars ->
            page.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }))
        ViewCompat.requestApplyInsets(window.decorView.rootView)
    }

    private fun returnHome() {
        startActivity(Intent(this, DemoProfilesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun showResult(result: DownloadAnimationView.Result) {
        returnHomeJob?.cancel()
        if (result == DownloadAnimationView.Result.Failure) {
            intent.putExtra("preview_failure", true)
            showFailurePreview()
            return
        }
        progressView.showResult(result)
        if (result == DownloadAnimationView.Result.Downloading)
            progressView.showStage(getString(R.string.download_wizard_progress_step_downloading))
        if (result == DownloadAnimationView.Result.Success) {
            returnHomeJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    delay(2000)
                    val target = if (intent.getBooleanExtra("preview_usb_download", false))
                        UsbReaderPreviewActivity::class.java else DemoProfilesActivity::class.java
                    startActivity(Intent(this@DownloadAnimationPreviewActivity, target)
                        .putExtra("preview_usb_downloaded", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); if (this::animation.isInitialized) animation.setActive(true) }
    override fun onPause() { if (this::animation.isInitialized) animation.setActive(false); super.onPause() }
}
