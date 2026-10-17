package im.angry.easyeuicc

import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.tabs.TabLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.HomeGlass
import im.angry.openeuicc.ui.minimal.MinimalToolbar
import im.angry.openeuicc.ui.minimal.showUonestWordmark
import androidx.lifecycle.lifecycleScope
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileCardView
import im.angry.openeuicc.ui.minimal.UsbReaderStatusView
import im.angry.openeuicc.ui.minimal.UsbReaderStatusView.State
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local-only walkthrough. No USB manager, real profiles, or eUICC service is accessed. */
class UsbReaderPreviewActivity : AppCompatActivity() {
    private lateinit var ui: MinimalUi
    private lateinit var status: UsbReaderStatusView
    private lateinit var result: LinearLayout
    private lateinit var caption: TextView
    private lateinit var tabs: TabLayout
    private lateinit var internalSlot: TextView
    private var usbState = "detached"
    private var downloaded = false
    private var fail = false
    private var reading: Job? = null
    private var permissionDialog: ProfileEditorDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ui = MinimalUi(this)
        setContentView(R.layout.activity_main)
        HomeGlass.install(this)
        val toolbar = findViewById<MinimalToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.showUonestWordmark(demo = true)
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<View>(R.id.main_tabs_container).visibility = View.VISIBLE
        val pager = findViewById<View>(R.id.view_pager)
        val root = pager.parent as ViewGroup
        val params = pager.layoutParams
        root.removeView(pager)
        val page = ui.column().apply { id = R.id.view_pager }
        root.addView(page, params)
        caption = ui.text("", 13f, true).apply { gravity = Gravity.CENTER }
        page.addView(caption, LinearLayout.LayoutParams(-1, ui.dp(40)))
        val content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        status = UsbReaderStatusView(this)
        content.addView(status, FrameLayout.LayoutParams(-1, -1))
        internalSlot = ui.text("手机卡槽演示区域", 16f, true).apply { gravity = Gravity.CENTER }
        content.addView(internalSlot, FrameLayout.LayoutParams(-1, -1))
        result = ui.column().apply {
            clipChildren = false
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), 0)
            addView(ui.text("USB 读卡器", 16f).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, ui.dp(48)))
            addView(ProfileCardView(this@UsbReaderPreviewActivity).apply {
                name.text = "旅行 eSIM（演示）"
                provider.text = "示例运营商"
                setIccid("8900000000000000000")
                setCountry(null)
                profileClass.visibility = View.GONE
                toggle.isChecked = false
                toggle.isEnabled = false
                isEnabled = false
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
            addView(ui.text("以上为虚构号卡，仅展示读取成功后的卡片样式。", 13f, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
        }
        content.addView(result, FrameLayout.LayoutParams(-1, -1))
        val controls = ui.actions(page).apply { gravity = Gravity.CENTER }
        ui.button(controls, "模拟插入") { insertReader(false) }
        ui.button(controls, "失败流程") { insertReader(true) }
        ui.button(controls, "模拟拔出") { detachReader() }
        status.onGrantPermission = { requestDemoPermission() }
        tabs = findViewById(R.id.main_tabs)
        tabs.clipChildren = false
        tabs.clipToPadding = false
        (tabs.getChildAt(0) as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.isSelected = true
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) { tab.customView?.isSelected = false }
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        addSlot("卡槽 1")
        addSlot("卡槽 2")
        tabs.getTabAt(0)?.select()
        render()
        if (intent.getBooleanExtra("preview_ready", false)) {
            insertReader(false)
            usbState = "success"
            render()
        }
        if (intent.getBooleanExtra("preview_inserted", false)) insertReader(false)
        if (intent.getBooleanExtra("preview_authorization", false)) {
            insertReader(false)
            page.post { requestDemoPermission() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.layoutParams = toolbar.layoutParams.apply { height = ui.dp(60) + bars.top }
            toolbar.setPadding(ui.dp(18) + bars.left, ui.dp(8) + bars.top, ui.dp(18) + bars.right, ui.dp(8))
            page.setPadding(bars.left, 0, bars.right, bars.bottom + ui.dp(8))
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, R.id.minimal_add_action, 0, "添加 eSIM（演示）").apply {
            setIcon(R.drawable.ic_minimal_plus)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.minimal_add_action)?.isVisible = usbState == "success" && tabs.selectedTabPosition == 2
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.minimal_add_action) {
            startActivity(Intent(this, AddEsimPreviewActivity::class.java).putExtra("preview_usb_download", true))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("preview_usb_downloaded", false) && !downloaded) {
            downloaded = true
            result.addView(ProfileCardView(this).apply {
                name.text = "新下载 eSIM（演示）"
                provider.text = "示例运营商"
                setIccid("8900000000000000001")
                setCountry(null)
                profileClass.visibility = View.GONE
                toggle.isEnabled = false
            }, 2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
            usbState = "success"
            tabs.getTabAt(2)?.select()
            render()
        }
    }

    private fun addSlot(label: String, isReader: Boolean = false) {
        val colors = AppCompatResources.getColorStateList(this, R.color.minimal_tab_content)
        val tab = tabs.newTab().setText(label)
        tab.customView = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.minimal_tab)
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@UsbReaderPreviewActivity, R.animator.minimal_tab_elevation)
            outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
            outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
            addView(ImageView(this@UsbReaderPreviewActivity).apply {
                setImageResource(if (isReader) R.drawable.ic_minimal_usb_reader else R.drawable.ic_minimal_sim)
                isDuplicateParentStateEnabled = true
                imageTintList = colors
            }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).apply { marginEnd = ui.dp(7) })
            addView(ui.text(label, 14f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                isDuplicateParentStateEnabled = true
                setTextColor(colors)
            })
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42))
        }
        tabs.addTab(tab, false)
        (tab.customView?.parent as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
    }

    private fun insertReader(failure: Boolean) {
        reading?.cancel()
        permissionDialog?.dismiss()
        fail = failure
        usbState = "permission"
        if (tabs.tabCount == 2) addSlot(getString(R.string.usb_tab_title), isReader = true)
        tabs.getTabAt(2)?.select()
        render()
    }

    private fun detachReader() {
        reading?.cancel()
        permissionDialog?.dismiss()
        usbState = "detached"
        if (tabs.tabCount > 2) tabs.removeTabAt(2)
        tabs.getTabAt(0)?.select()
        render()
    }

    private fun render() {
        val usbSelected = tabs.selectedTabPosition == 2
        internalSlot.visibility = if (usbSelected) View.GONE else View.VISIBLE
        status.visibility = if (usbSelected && usbState != "success") View.VISIBLE else View.GONE
        result.visibility = if (usbSelected && usbState == "success") View.VISIBLE else View.GONE
        status.showState(when (usbState) {
            "loading" -> State.LOADING
            "failure" -> State.FAILURE
            else -> State.PERMISSION
        })
        invalidateOptionsMenu()
        caption.text = when {
            usbState == "detached" -> "演示 · 点击底部“模拟插入”开始"
            !usbSelected -> "演示 · 读卡器已插入，可切换 USB 选项"
            usbState == "permission" -> "演示 · 已插入读卡器，请授予权限"
            usbState == "loading" -> "演示 · 正在读取 eSIM…"
            usbState == "success" -> if (downloaded) "演示 · 新号卡已添加到读卡器" else "演示 · 已读取号卡，点右上角＋添加 eSIM"
            else -> "演示 · 连接失败，可重新模拟插入"
        }
    }

    private fun requestDemoPermission() {
        if (permissionDialog?.isShowing == true) return
        val dialog = ProfileEditorDialog(this)
        permissionDialog = dialog
        val content = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(12))
            setBackgroundResource(R.drawable.minimal_card)
        }
        content.addView(ui.text("USB 授权演示", 16f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 12)
        content.addView(ui.text("允许 uonest 访问读卡器？", 13f, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 6)
        content.addView(ui.text("仅模拟授权，不申请系统权限。", 12f, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 18)
        val actions = ui.actions(content)
        ui.button(actions, "取消") {
            caption.text = "已取消模拟授权，可再次点击授权按钮"
            dialog.dismiss()
        }.apply {
            textSize = 14f
            insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(44), 1f)
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_tint))
            setTextColor(ui.color(R.color.minimal_secondary))
        }
        ui.button(actions, "允许（演示）") {
            if (usbState == "permission") {
                dialog.dismiss()
                usbState = "loading"
                render()
                reading = lifecycleScope.launch {
                    delay(1800)
                    usbState = if (fail) "failure" else "success"
                    render()
                }
            }
        }.apply {
            textSize = 14f
            insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { marginStart = ui.dp(12) }
            backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(Color.WHITE)
        }
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        super.onDestroy()
    }
}
