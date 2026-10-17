package im.angry.easyeuicc

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import im.angry.openeuicc.ui.minimal.ProfileSwitchDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.UnprivilegedMainActivity
import im.angry.openeuicc.ui.minimal.MinimalToolbar
import im.angry.openeuicc.ui.minimal.showUonestWordmark
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.ui.minimal.ProfileCardView
import im.angry.openeuicc.ui.minimal.ProfileDeleteView
import im.angry.openeuicc.ui.minimal.ProfileEditorView
import im.angry.openeuicc.ui.minimal.ProfileEditorDialog
import org.json.JSONArray
import org.json.JSONObject

/** Debug fixtures only. This activity never connects to an eUICC manager or service. */
class DemoProfilesActivity : AppCompatActivity() {
    private data class DemoProfile(
        val id: String, val slot: Int, var name: String, val provider: String,
        var country: String?, var enabled: Boolean
    )
    private val prefs by lazy { getSharedPreferences("demo_profiles", MODE_PRIVATE) }
    private val profiles = mutableListOf<DemoProfile>()
    private lateinit var ui: MinimalUi
    private lateinit var list: RecyclerView
    private lateinit var tabs: TabLayout
    private var selectedSlot = 1
    private val adapter = DemoAdapter()
    private var switching = false
    private var switchDialog: ProfileSwitchDialog? = null
    private val networkLogoPreview get() = intent.getBooleanExtra("preview_network_logos", false)
    private val visibleProfiles get() = profiles.filter { it.slot == selectedSlot }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ui = MinimalUi(this)
        selectedSlot = savedInstanceState?.getInt("slot") ?: prefs.getInt("slot", 1)
        load()
        setContentView(R.layout.activity_main)
        im.angry.openeuicc.ui.minimal.HomeGlass.install(this)
        val toolbar = findViewById<MinimalToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.showUonestWordmark(demo = true)
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<View>(R.id.main_tabs_container).visibility = View.VISIBLE

        // Reuse the real home layout and real profile cards, but bind only local fixtures.
        val pager = findViewById<View>(R.id.view_pager)
        val parent = pager.parent as ViewGroup
        val params = pager.layoutParams
        parent.removeView(pager)
        list = RecyclerView(this).apply {
            id = R.id.profile_list
            layoutManager = LinearLayoutManager(this@DemoProfilesActivity)
            adapter = this@DemoProfilesActivity.adapter
            clipToPadding = false
            setPadding(0, ui.dp(6), 0, ui.dp(18))
        }
        val refresh = SwipeRefreshLayout(this).apply {
            id = R.id.view_pager
            ui.styleHomeRefresh(this)
            addView(list)
            setOnRefreshListener {
                // Give the local-only demo enough time to show the refresh animation.
                postDelayed({ render(); isRefreshing = false }, 600)
            }
        }
        parent.addView(refresh, params)
        tabs = findViewById(R.id.main_tabs)
        tabs.clipChildren = false
        tabs.clipToPadding = false
        (tabs.getChildAt(0) as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
        val colors = AppCompatResources.getColorStateList(this, R.color.minimal_tab_content)
        for (slot in 1..2) {
            val tab = tabs.newTab().setText("卡槽 $slot")
            tab.customView = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.minimal_tab)
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@DemoProfilesActivity, R.animator.minimal_tab_elevation)
                outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
                outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
                addView(ImageView(this@DemoProfilesActivity).apply {
                    setImageResource(R.drawable.ic_minimal_sim)
                    isDuplicateParentStateEnabled = true
                    imageTintList = colors
                }, LinearLayout.LayoutParams(ui.dp(17), ui.dp(17)).apply { marginEnd = ui.dp(7) })
                addView(ui.text("卡槽 $slot", 14f).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    isDuplicateParentStateEnabled = true
                    setTextColor(colors)
                })
                layoutParams = LinearLayout.LayoutParams(-1, ui.dp(42))
            }
            tabs.addTab(tab, false)
            (tab.customView?.parent as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.isSelected = true
                selectedSlot = tab.position + 1
                prefs.edit().putInt("slot", selectedSlot).apply()
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) { tab.customView?.isSelected = false }
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(selectedSlot - 1)?.select()
        ViewCompat.setOnApplyWindowInsetsListener(parent) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.layoutParams = toolbar.layoutParams.apply { height = ui.dp(60) + bars.top }
            toolbar.setPadding(ui.dp(18) + bars.left, ui.dp(8) + bars.top, ui.dp(18) + bars.right, ui.dp(8))
            list.setPadding(bars.left, ui.dp(6), bars.right, ui.dp(18) + bars.bottom)
            insets
        }
        showRequestedPreview()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showRequestedPreview()
    }

    override fun onDestroy() {
        switchDialog?.dismiss()
        super.onDestroy()
    }

    private fun switchDemoProfile(profile: DemoProfile, enabled: Boolean) {
        if (switching) return
        switching = true
        val dialog = ProfileSwitchDialog(this)
        switchDialog = dialog
        dialog.show()
        lifecycleScope.launch {
            try {
                delay(3200) // Local fixture only; real switching waits for the service and state read.
                if (enabled) profiles.filter { it.slot == profile.slot }.forEach { it.enabled = false }
                profile.enabled = enabled
                save(); render()
            } finally {
                dialog.dismiss()
                switchDialog = null
                switching = false
            }
        }
    }

    private fun showRequestedPreview() {
        if (networkLogoPreview) {
            reset()
            selectedSlot = 1
            tabs.getTabAt(0)?.select()
            render()
            return
        }
        if (intent.getBooleanExtra("preview_switch", false)) {
            intent.removeExtra("preview_switch")
            list.post { visibleProfiles.firstOrNull()?.let { switchDemoProfile(it, !it.enabled) } }
            return
        }
        if (!intent.getBooleanExtra("preview_real_delete", false)) return
        intent.removeExtra("preview_real_delete")
        list.post {
            val name = "日常主卡"
            val dialog = ProfileEditorDialog(this)
            val content = ProfileDeleteView(this, getString(R.string.profile_delete_confirm, name),
                title = getString(R.string.profile_delete_title),
                onDelete = {
                    android.widget.Toast.makeText(this, "演示完成，未删除任何号卡", android.widget.Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }, onCancel = { dialog.dismiss() })
            content.deleteButton.setText(R.string.profile_delete_action)
            content.titleView.typeface = android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.BOLD)
            content.messageView.apply {
                gravity = Gravity.CENTER
                textSize = 14f
            }
            dialog.setContentView(ui.scroll(content))
            dialog.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("slot", selectedSlot)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        findViewById<MinimalToolbar>(R.id.toolbar).apply {
            overflowIcon = overflowIcon?.mutate()?.apply { setTint(ui.color(R.color.minimal_text)) }
        }
        menu.add(Menu.NONE, R.id.minimal_add_action, 0, getString(R.string.profile_download)).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = android.widget.FrameLayout(this@DemoProfilesActivity).apply {
                layoutParams = ViewGroup.LayoutParams(ui.dp(48), ui.dp(40))
                addView(android.widget.ImageButton(this@DemoProfilesActivity).apply {
                    contentDescription = getString(R.string.profile_download)
                    setImageResource(R.drawable.ic_minimal_plus)
                    imageTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_text))
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(ui.color(R.color.minimal_blue_soft))
                    }
                    setOnClickListener {
                        startActivity(Intent(this@DemoProfilesActivity, AddEsimPreviewActivity::class.java))
                    }
                }, android.widget.FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.END))
            }
        }
        menu.add("恢复演示卡片").setOnMenuItemClickListener { reset(); save(); render(); true }
        menu.add("下载动画预览").setOnMenuItemClickListener {
            startActivity(Intent(this, DownloadAnimationPreviewActivity::class.java)); true
        }
        menu.add("返回真实首页").setOnMenuItemClickListener {
            startActivity(Intent(this, UnprivilegedMainActivity::class.java)); finish(); true
        }
        return true
    }

    private fun reset() {
        profiles.clear()
        if (networkLogoPreview) {
            profiles.addAll(listOf(
                DemoProfile("0001", 1, "Vodafone", "英国套餐 · 演示", "GB", true),
                DemoProfile("0002", 1, "T-Mobile", "美国套餐 · 演示", "US", false),
                DemoProfile("0003", 1, "Orange", "法国套餐 · 演示", "FR", false),
                DemoProfile("0004", 1, "默认图标", "无运营商 Logo · 演示", null, false)
            ))
            return
        }
        profiles.addAll(listOf(
            DemoProfile("0001", 1, "日常主卡", "国内套餐 · 演示", null, true),
            DemoProfile("0002", 1, "日本旅行", "日本流量 · 演示", "JP", false),
            DemoProfile("0003", 1, "美国流量", "美国套餐 · 演示", "US", false),
            DemoProfile("0004", 2, "新加坡出差", "新加坡流量 · 演示", "SG", true),
            DemoProfile("0005", 2, "英国旅行", "英国套餐 · 演示", "GB", false)
        ))
    }

    private fun load() {
        if (networkLogoPreview) { reset(); return }
        val stored = prefs.getString("profiles", null)
        if (stored == null) { reset(); save(); return }
        runCatching {
            val array = JSONArray(stored)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                profiles.add(DemoProfile(item.getString("id"), item.getInt("slot"), item.getString("name"),
                    item.getString("provider"), item.optString("country").ifBlank { null }, item.getBoolean("enabled")))
            }
        }.onFailure { reset(); save() }
    }

    private fun save() {
        if (networkLogoPreview) return
        val array = JSONArray()
        profiles.forEach { profile -> array.put(JSONObject().apply {
            put("id", profile.id); put("slot", profile.slot); put("name", profile.name)
            put("provider", profile.provider); put("country", profile.country.orEmpty()); put("enabled", profile.enabled)
        }) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun render() { adapter.notifyDataSetChanged() }

    private fun edit(profile: DemoProfile) {
        val dialog = ProfileEditorDialog(this)
        lateinit var editor: ProfileEditorView
        editor = ProfileEditorView(this, profile.name, onSave = {
            val value = editor.nameEditor.text.toString().trim()
            if (value.isEmpty()) editor.nameEditor.error = "请输入名称" else {
                profile.name = value; save(); render(); dialog.dismiss()
            }
        }, onCancel = { dialog.dismiss() })
        dialog.setContentView(ui.scroll(editor))
        dialog.show()
    }

    private fun delete(profile: DemoProfile) {
        if (profile.enabled) return
        val dialog = ProfileEditorDialog(this)
        val content = ProfileDeleteView(this,
            "仅移除本地演示卡片“${profile.name}”，可在更多中恢复。",
            onDelete = {
                profiles.remove(profile); save(); render(); dialog.dismiss()
            }, onCancel = { dialog.dismiss() })
        dialog.setContentView(ui.scroll(content))
        dialog.show()
    }

    private inner class DemoAdapter : RecyclerView.Adapter<DemoHolder>() {
        override fun getItemCount() = visibleProfiles.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DemoHolder(ProfileCardView(parent.context))
        override fun onBindViewHolder(holder: DemoHolder, position: Int) { holder.bind(visibleProfiles[position]) }
    }

    private inner class DemoHolder(private val card: ProfileCardView) : RecyclerView.ViewHolder(card) {
        fun bind(profile: DemoProfile) {
            card.close()
            card.name.text = profile.name
            card.provider.text = profile.provider
            val demoIccid = "8900000000000000${profile.id}"
            card.setIccid(demoIccid)
            card.profileClass.visibility = View.GONE
            if (networkLogoPreview && profile.id == "0004") {
                card.setCountry(null)
            } else if (networkLogoPreview) {
                val (asset, chinese, english) = when (profile.id) {
                    "0001" -> Triple("vodafone", "沃达丰", "Vodafone")
                    "0002" -> Triple("tmobile", "T-Mobile", "USA")
                    else -> Triple("orange", "Orange", "France")
                }
                val logo = assets.open("operator_logos/$asset.png").use(android.graphics.BitmapFactory::decodeStream)
                card.setBranding(logo, profile.country, chinese, english)
            } else card.setCountry(profile.country)
            card.toggle.setOnCheckedChangeListener(null)
            card.toggle.isChecked = profile.enabled
            card.toggle.contentDescription = "${profile.name}，演示开关"
            card.canDelete = !profile.enabled
            card.toggle.setOnCheckedChangeListener { _, checked ->
                card.toggle.setOnCheckedChangeListener(null)
                card.toggle.isChecked = profile.enabled
                switchDemoProfile(profile, checked)
            }
            card.onEdit = { edit(profile) }
            card.onDelete = { delete(profile) }
        }
    }
}
