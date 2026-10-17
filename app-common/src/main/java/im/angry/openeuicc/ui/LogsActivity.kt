package im.angry.openeuicc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class LogsActivity : AppCompatActivity() {
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var scrollView: ScrollView
    private lateinit var logText: TextView
    private var logStr = ""
    private val save = setupLogSaving(
        { "EasyEUICC-logs-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}.txt" },
        { buildLogText() },
        offerShare = false
    )

    private fun buildLogText() = buildString {
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("SDK Version: ${Build.VERSION.SDK_INT}")
        appendLine("App Version: $selfAppVersion")
        appendLine("-".repeat(10))
        appendLine(logStr)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.pref_advanced_logs)
        // Align the visible arrow with the code block's 12dp margin.
        requireViewById<im.angry.openeuicc.ui.minimal.MinimalToolbar>(R.id.toolbar)
            .navigationIconStartOffsetDp = -6

        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(this)
        val page = ui.page().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(24)) }
        val card = ui.card(page).apply {
            id = R.id.logs_code_block
            (layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                height = 0
                weight = 1f
            }
            (background.mutate() as android.graphics.drawable.GradientDrawable).apply {
                setColor(ui.color(R.color.minimal_code_body))
                cornerRadius = ui.dp(12).toFloat()
            }
            setPadding(0, 0, 0, 0)
            clipToOutline = true
            elevation = 0f
            foreground = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.minimal_code_outline)
        }
        val header = android.widget.LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.color(R.color.minimal_code_header))
            setPadding(ui.dp(10), 0, 0, 0)
        }
        header.addView(ui.text(getString(R.string.pref_advanced_logs), 14f),
            android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        fun action(id: Int, title: Int, icon: Int, onClick: () -> Unit) {
            header.addView(androidx.appcompat.widget.AppCompatImageButton(this).apply {
                this.id = id
                setImageResource(icon)
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                ui.styleIconPress(this)
                contentDescription = getString(title)
                tooltipText = contentDescription
                setOnClickListener { if (!swipeRefresh.isRefreshing) onClick() }
            }, android.widget.LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        }
        action(R.id.logs_refresh, R.string.minimal_refresh, R.drawable.ic_minimal_refresh) {
            lifecycleScope.launch { reload() }
        }
        action(R.id.save, R.string.minimal_save, R.drawable.ic_minimal_save) { save() }
        action(R.id.logs_copy, R.string.minimal_copy, R.drawable.ic_minimal_copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.pref_advanced_logs), buildLogText()))
            Toast.makeText(this@LogsActivity, R.string.minimal_copied, Toast.LENGTH_SHORT).show()
        }
        card.addView(header)
        card.addView(View(this).apply {
            setBackgroundColor(ui.color(R.color.minimal_code_divider))
        }, android.widget.LinearLayout.LayoutParams(-1, ui.dp(1)))
        logText = ui.text("", 11f).apply {
            id = R.id.logs_text
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(ui.dp(10), ui.dp(14), ui.dp(10), ui.dp(14))
            setTextIsSelectable(true)
        }
        scrollView = ui.scroll(logText)
        swipeRefresh = SwipeRefreshLayout(this).apply { addView(scrollView) }
        card.addView(swipeRefresh, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        requireViewById<android.widget.FrameLayout>(R.id.settings_container).addView(page)

        setupRootViewSystemBarInsets(
            window.decorView.rootView, arrayOf(
                this::activityToolbarInsetHandler,
                { insets ->
                    page.setPadding(ui.dp(12) + insets.left, ui.dp(8),
                        ui.dp(12) + insets.right, ui.dp(24) + insets.bottom)
                }
            )
        )

        swipeRefresh.setOnRefreshListener {
            lifecycleScope.launch {
                reload()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            reload()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun updateActions() {
        val ready = !swipeRefresh.isRefreshing
        listOf(R.id.logs_refresh, R.id.save, R.id.logs_copy).forEach { id ->
            requireViewById<View>(id).apply {
                isEnabled = ready
                alpha = if (ready) 1f else 0.4f
            }
        }
    }

    private suspend fun reload() = withContext(Dispatchers.Main) {
        swipeRefresh.isRefreshing = true
        updateActions()

        logStr = intent.extras?.getString("log") ?: readSelfLog()

        logText.text = withContext(Dispatchers.IO) {
            // Limit the UI to display only 256 lines
            logStr.lines().takeLast(256).joinToString("\n")
        }

        swipeRefresh.isRefreshing = false
        updateActions()

        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
