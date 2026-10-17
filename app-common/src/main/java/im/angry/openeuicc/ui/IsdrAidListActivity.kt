package im.angry.openeuicc.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class IsdrAidListActivity : AppCompatActivity() {
    private lateinit var ui: MinimalUi
    private lateinit var editor: EditText
    private lateinit var addButton: View
    private lateinit var resetButton: View
    private val adapter = AidAdapter()
    private var aids = emptyList<String>()
    private var busy = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        ui = MinimalUi(this, glassCards = true)
        val page = ui.page().apply { clipChildren = true }
        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        page.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        editor = EditText(this).apply {
            id = R.id.isdr_aid_list_editor
            textSize = 14f
            setTextColor(ui.color(R.color.minimal_text))
            setHintTextColor(ui.color(R.color.minimal_secondary))
            setBackgroundResource(R.drawable.minimal_language_selector)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            hint = getString(R.string.aid_input_hint)
            contentDescription = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            minHeight = ui.dp(48)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(savedInstanceState?.getString("draft").orEmpty())
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { addAid(); true } else false
            }
        }
        inputRow.addView(editor, LinearLayout.LayoutParams(0, -2, 1f))
        addButton = iconButton(R.string.aid_add, R.drawable.ic_aid_add) { addAid() }.apply {
            id = R.id.aid_add
            imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ui.color(R.color.minimal_blue))
            }
        }
        inputRow.addView(addButton, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginStart = ui.dp(12) })
        val list = RecyclerView(this).apply {
            id = R.id.aid_entries
            layoutManager = LinearLayoutManager(this@IsdrAidListActivity)
            clipToPadding = true
            setPadding(0, ui.dp(3), 0, ui.dp(3))
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, 0)
        }
        resetButton = ui.button(actions, getString(R.string.aid_restore)) {
            if (!busy) save(emptyList(), clearDraft = true, reset = true)
        }.apply {
            id = R.id.reset
            textSize = 14f
            setIconResource(R.drawable.ic_minimal_refresh)
            iconSize = ui.dp(16)
            iconPadding = ui.dp(6)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(ui.color(R.color.minimal_red))
            iconTint = ColorStateList.valueOf(ui.color(R.color.minimal_red))
            rippleColor = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(ui.color(R.color.minimal_red), 20))
        }
        list.adapter = ConcatAdapter(adapter, object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(actions) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        })
        requireViewById<FrameLayout>(R.id.settings_container).addView(page)
        setupRootViewSystemBarInsets(window.decorView.rootView,
            arrayOf(this::activityToolbarInsetHandler, ui.pageInsets(page)))
        setBusy(true)
        lifecycleScope.launch {
            try {
                aids = displayAids(preferenceRepository.isdrAidListFlow.first())
                adapter.notifyDataSetChanged()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                Toast.makeText(this@IsdrAidListActivity, R.string.minimal_error, Toast.LENGTH_LONG).show()
                finish()
            } finally { setBusy(false) }
        }
    }

    private fun displayAids(raw: String) = parseIsdrAidList(raw)
        .map { it.encodeHex().uppercase(Locale.ROOT) }.distinct()

    private fun setBusy(value: Boolean) {
        busy = value
        addButton.isEnabled = !value
        resetButton.isEnabled = !value
        editor.isEnabled = !value
    }

    private fun addAid() {
        if (busy) return
        val aid = editor.text.toString().replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
        if (aid.length % 2 != 0 || !aid.matches(Regex("[0-9A-F]{10,32}"))) {
            editor.error = getString(R.string.minimal_aid_invalid)
            return
        }
        if (aid in aids) {
            editor.error = getString(R.string.aid_duplicate)
            return
        }
        save(aids + aid, clearDraft = true)
    }

    private fun deleteAid(aid: String) {
        if (busy || aid == EUICC_DEFAULT_ISDR_AID) { adapter.notifyDataSetChanged(); return }
        save(aids - aid)
    }

    private fun save(updated: List<String>, clearDraft: Boolean = false, reset: Boolean = false) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                if (reset) preferenceRepository.isdrAidListFlow.removePreference()
                else preferenceRepository.isdrAidListFlow.updatePreference(updated.joinToString("\n"))
                aids = if (reset) displayAids(preferenceRepository.isdrAidListFlow.first()) else updated
                if (clearDraft) { editor.text.clear(); editor.error = null }
                if (reset) Toast.makeText(this@IsdrAidListActivity, R.string.aid_restored, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                Toast.makeText(this@IsdrAidListActivity, R.string.minimal_error, Toast.LENGTH_LONG).show()
            } finally {
                adapter.notifyDataSetChanged()
                setBusy(false)
            }
        }
    }

    private fun iconButton(title: Int, icon: Int, action: () -> Unit) = AppCompatImageButton(this).apply {
        setImageResource(icon)
        setPadding(ui.dp(13), ui.dp(13), ui.dp(13), ui.dp(13))
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = getString(title)
        tooltipText = contentDescription
        ui.styleIconPress(this)
        setOnClickListener { action() }
    }

    private inner class AidHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card)
    private inner class AidAdapter : RecyclerView.Adapter<AidHolder>() {
        override fun getItemCount() = aids.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AidHolder {
            val holder = ui.column()
            val card = ui.card(holder, small = true)
            holder.removeView(card)
            card.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
            return AidHolder(card)
        }
        override fun onBindViewHolder(holder: AidHolder, position: Int) {
            val aid = aids[position]
            holder.card.removeAllViews()
            val row = LinearLayout(this@IsdrAidListActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = ui.column()
            labels.addView(ui.text(platformName(aid), 13f).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            ui.gap(labels, 6)
            labels.addView(ui.text(aid, 13f).apply {
                id = R.id.aid_value
                textDirection = View.TEXT_DIRECTION_LTR
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
            }, LinearLayout.LayoutParams(-1, -2))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iconButton(R.string.profile_delete, R.drawable.ic_minimal_trash) { deleteAid(aid) }.apply {
                id = R.id.aid_delete
                setPadding(ui.dp(26), ui.dp(13), 0, ui.dp(13))
                isEnabled = aid != EUICC_DEFAULT_ISDR_AID
                imageTintList = ColorStateList.valueOf(ui.color(if (isEnabled) R.color.minimal_red else R.color.minimal_secondary))
                alpha = if (isEnabled) 1f else .35f
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            holder.card.addView(row)
        }
    }

    private fun platformName(aid: String): String = when (aid) {
        EUICC_DEFAULT_ISDR_AID -> getString(R.string.aid_standard)
        "A0000005591010000000008900000300" -> "eSIM.me"
        "A0000005591010FFFFFFFF8900050500" -> "5ber.eSIM"
        "A0000005591010FFFFFFFF8900000177" -> "Xesim"
        "A000000559104C696E6B736669656C64" -> "LinksField"
        ESTKme.ESTK_SE0_AID.encodeHex().uppercase(Locale.ROOT) -> "ESTKme SE0"
        ESTKme.ESTK_SE1_AID.encodeHex().uppercase(Locale.ROOT) -> "ESTKme SE1"
        "A06573746B6D65FFFFFFFF4953442D52" -> "ESTKme AUX"
        else -> getString(R.string.aid_custom)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", editor.text.toString())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }
}
