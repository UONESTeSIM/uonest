package im.angry.easyeuicc

import android.content.Intent
import im.angry.openeuicc.ui.wizard.AddEsimActivity
import im.angry.openeuicc.util.LPAString

/** Shares acquisition UI, but never passes an activation code to the real download flow. */
class AddEsimPreviewActivity : AddEsimActivity() {
    override val isDemo = true
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val initialState = savedInstanceState ?: if (intent.getBooleanExtra("preview_usb_download", false))
            android.os.Bundle().apply { putString("draft", "LPA:1\$demo.invalid\$USB-DEMO") } else if (intent.getBooleanExtra("preview_manual", false))
            android.os.Bundle().apply { putString("screen", "manual") } else null
        super.onCreate(initialState)
        if (intent.getBooleanExtra("preview_usb_download", false)) {
            title = "添加 eSIM · 读卡器演示"
            for (id in intArrayOf(im.angry.openeuicc.common.R.id.add_esim_scan, im.angry.openeuicc.common.R.id.add_esim_image)) {
                findViewById<android.view.View>(id).setOnClickListener {
                    android.widget.Toast.makeText(this, "模拟识别二维码，不使用相机或图片", android.widget.Toast.LENGTH_SHORT).show()
                    continueWithCode(LPAString.parse("LPA:1\$demo.invalid\$USB-DEMO"), true)
                }
            }
        }
    }
    override fun continueWithCode(code: LPAString, directDownload: Boolean) {
        startActivity(Intent(this, DownloadAnimationPreviewActivity::class.java).apply {
            putExtra("preview_usb_download", intent.getBooleanExtra("preview_usb_download", false))
        })
        if (intent.getBooleanExtra("preview_usb_download", false)) finish()
    }
}
