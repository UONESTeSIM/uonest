package im.angry.openeuicc.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileNotification

class NotificationsActivity : BaseEuiccAccessActivity(), OpenEuiccContextMarker {
    private val ui by lazy { MinimalUi(this, rowTitleSize = 16f, rowDetailSize = 13f, glassCards = true) }
    private lateinit var page: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var logicalSlotId = -1
    private var seId = EuiccChannel.SecureElementId.DEFAULT
    private var busy = false
    private val notificationDeliveryStore by lazy { NotificationDeliveryStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(requireViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.profile_notifications)
        page = ui.page()
        swipeRefresh = SwipeRefreshLayout(this).apply { addView(ui.scroll(page)) }
        ui.styleHomeRefresh(swipeRefresh)
        requireViewById<FrameLayout>(R.id.settings_container).addView(swipeRefresh)
        logicalSlotId = intent.getIntExtra("logicalSlotId", 0)
        @Suppress("DEPRECATION")
        seId = intent.getParcelableExtra("seId") ?: EuiccChannel.SecureElementId.DEFAULT
        swipeRefresh.setOnRefreshListener { load() }
        setupRootViewSystemBarInsets(window.decorView.rootView,
            arrayOf(this::activityToolbarInsetHandler, ui.pageInsets(page)))
    }
    override fun onInit() { load() }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false // Help lives inside the notification card.
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        else -> super.onOptionsItemSelected(item)
    }
    private fun showHelp() {
        val dialog = im.angry.openeuicc.ui.minimal.ProfileEditorDialog(this)
        val content = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            setBackgroundResource(R.drawable.minimal_card)
        }
        content.addView(ui.text(getString(R.string.minimal_help), 16f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        ui.gap(content, 14)
        content.addView(ui.text(getString(R.string.profile_notifications_help), 13f, true).apply {
            setLineSpacing(0f, 1.4f)
        })
        ui.gap(content, 18)
        ui.button(content, getString(R.string.minimal_usb_understood)) { dialog.dismiss() }.apply {
            textSize = 14f
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_blue))
            setTextColor(android.graphics.Color.WHITE)
            cornerRadius = ui.dp(10)
            insetTop = ui.dp(4); insetBottom = ui.dp(4)
            minHeight = ui.dp(44); minimumHeight = ui.dp(44)
            layoutParams = LinearLayout.LayoutParams(ui.dp(88), ui.dp(44)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
        }
        dialog.setContentView(ui.scroll(content))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
    private fun load(operation: (suspend (EuiccChannel) -> Unit)? = null) {
        if (busy) return
        busy = true
        swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                euiccChannelManagerLoaded.await()
                euiccChannelManagerService.waitForForegroundTask()
                euiccChannelManager.withEuiccChannel(logicalSlotId, seId) { channel ->
                    operation?.invoke(channel)
                    val notifications = notificationDeliveryStore.removeDelivered(channel, channel.lpa.notifications)
                    val results = notificationDeliveryStore.results(channel, notifications)
                    val slot = if (logicalSlotId == EuiccChannelManager.USB_CHANNEL_ID) {
                        if (channel.hasMultipleSE) getString(R.string.channel_name_format_usb_se, seId.id)
                        else getString(R.string.channel_name_format_usb)
                    } else if (channel.hasMultipleSE) appContainer.customizableTextProvider.formatNonUsbChannelNameWithSeId(logicalSlotId, seId)
                        else appContainer.customizableTextProvider.formatNonUsbChannelName(logicalSlotId)
                    withContext(Dispatchers.Main) { render(slot, notifications, results) }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                Toast.makeText(this@NotificationsActivity, R.string.minimal_error, Toast.LENGTH_LONG).show()
            } finally { busy = false; swipeRefresh.isRefreshing = false }
        }
    }
    private fun showDeleteNotification(item: LocalProfileNotification) {
        val dialog = im.angry.openeuicc.ui.minimal.ProfileEditorDialog(this)
        var confirmed = false
        val content = im.angry.openeuicc.ui.minimal.ProfileDeleteView(
            this, getString(R.string.minimal_notification_delete_confirm),
            onDelete = {
                if (!confirmed && !busy) {
                    confirmed = true
                    dialog.dismiss()
                    load { channel ->
                        check(channel.lpa.deleteNotification(item.seqNumber))
                        notificationDeliveryStore.remove(channel, item)
                    }
                }
            },
            onCancel = { dialog.dismiss() }
        )
        listOf(content.cancelButton, content.deleteButton).forEach { button ->
            button.textSize = 14f
            button.insetTop = ui.dp(4); button.insetBottom = ui.dp(4)
            button.minHeight = ui.dp(44); button.minimumHeight = ui.dp(44)
            button.layoutParams.height = ui.dp(44)
        }
        dialog.setContentView(ui.scroll(content))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }
    private fun render(slot: String, notifications: List<LocalProfileNotification>,
                       results: Map<Long, Boolean?>) {
        page.removeAllViews()
        val card = ui.card(page).apply { id = R.id.notifications_card }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ui.text(getString(R.string.notification_card_title, slot), 16f),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(androidx.appcompat.widget.AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_minimal_info)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = null
            contentDescription = getString(R.string.minimal_help)
            setOnClickListener { showHelp() }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply {
            topMargin = -ui.dp(10); bottomMargin = -ui.dp(10); marginEnd = -ui.dp(12)
        })
        card.addView(header)
        if (notifications.isEmpty()) {
            ui.gap(card, 14)
            card.addView(ui.text(getString(R.string.minimal_empty_notifications), 14f, true).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        notifications.forEach { item ->
            ui.divider(card, verticalMargin = 12)
            val entry = ui.column()
            card.addView(entry, LinearLayout.LayoutParams(-1, -2))
            val operation = getString(when (item.profileManagementOperation) {
                LocalProfileNotification.Operation.Install -> R.string.profile_notification_operation_download
                LocalProfileNotification.Operation.Delete -> R.string.profile_notification_operation_delete
                LocalProfileNotification.Operation.Enable -> R.string.profile_notification_operation_enable
                LocalProfileNotification.Operation.Disable -> R.string.profile_notification_operation_disable
            })
            val heading = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            heading.addView(ui.text(operation, 14f).apply {
                setTextColor(android.graphics.Color.rgb(55, 65, 81))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val wasSent = results[item.seqNumber] == true
            val hasFailed = results[item.seqNumber] == false
            val statusLabel = ui.text(getString(when {
                wasSent -> R.string.notification_delivery_success
                hasFailed -> R.string.notification_delivery_failed
                else -> R.string.notification_delivery_unknown
            }), 13f, true).apply {
                setPadding(ui.dp(7), ui.dp(4), ui.dp(7), ui.dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ui.dp(6).toFloat()
                    setColor(ui.color(when {
                        wasSent -> R.color.minimal_notification_success_background
                        hasFailed -> R.color.minimal_notification_failure_background
                        else -> R.color.minimal_notification_unknown_background
                    }))
                }
                setTextColor(ui.color(when {
                    wasSent -> R.color.minimal_notification_success_text
                    hasFailed -> R.color.minimal_red
                    else -> R.color.minimal_secondary
                }))
            }
            heading.addView(statusLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) })
            entry.addView(heading)
            ui.gap(entry, 8)
            addCopyDetail(entry, getString(R.string.profile_iccid).removeSuffix(":"), item.iccid)
            ui.gap(entry, 4)
            addCopyDetail(entry, getString(R.string.notification_server), item.notificationAddress)
            ui.gap(entry, 8)
            val actions = ui.actions(entry)
            ui.button(actions, getString(R.string.minimal_delete_record)) {
                if (!busy) showDeleteNotification(item)
            }.apply {
                textSize = 13f
                setTextColor(ui.color(R.color.minimal_red))
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                icon = androidx.core.content.ContextCompat.getDrawable(this@NotificationsActivity, R.drawable.ic_minimal_trash)
                iconTint = android.content.res.ColorStateList.valueOf(ui.color(R.color.minimal_red))
                iconSize = ui.dp(14)
                setPadding(0, 0, 0, 0)
            }
            actions.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            ui.button(actions, getString(if (wasSent) R.string.minimal_resend else R.string.minimal_send)) {
                load { channel ->
                    val delivered = notificationDeliveryStore.send(channel, item)
                    if (!delivered) withContext(Dispatchers.Main) {
                        Toast.makeText(this@NotificationsActivity, R.string.minimal_error, Toast.LENGTH_LONG).show()
                        // Preserve the failed result even if reloading the chip fails.
                        render(slot, notifications, results + (item.seqNumber to false))
                    }
                }
            }.apply {
                textSize = 13f
                icon = androidx.core.content.ContextCompat.getDrawable(this@NotificationsActivity,
                    if (wasSent) R.drawable.ic_minimal_refresh else R.drawable.ic_minimal_send)
                statusLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
                statusLabel.typeface = typeface
                statusLabel.letterSpacing = letterSpacing
                iconSize = ui.dp(13)
                iconPadding = ui.dp(4)
                setPaddingRelative(ui.dp(8), 0, 0, 0)
                insetTop = ui.dp(8)
                insetBottom = ui.dp(8)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            }
            // Keep the compact button backgrounds inside the entry's content edges.
            for (index in 0 until actions.childCount) (actions.getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                if (height == ui.dp(44)) { topMargin = -ui.dp(6); bottomMargin = -ui.dp(6) }
            }
        }
    }
    private fun addCopyDetail(parent: LinearLayout, label: String, value: String) {
        val valueText = ui.text("", 13f, true).apply {
            val copyIcon = android.graphics.drawable.InsetDrawable(
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_minimal_copy), ui.dp(2)
            ).apply { setBounds(0, 0, ui.dp(18), ui.dp(18)) }
            val detail = android.text.SpannableStringBuilder("$label: $value").append("\u00A0\uFFFC")
            val iconStart = detail.length - 1
            detail.setSpan(object : android.text.style.ImageSpan(copyIcon) {
                override fun draw(canvas: android.graphics.Canvas, text: CharSequence,
                                  start: Int, end: Int, x: Float, top: Int, y: Int,
                                  bottom: Int, paint: android.graphics.Paint) {
                    val metrics = paint.fontMetrics
                    val centerY = y + (metrics.ascent + metrics.descent) / 2f
                    canvas.save()
                    canvas.translate(x, centerY - drawable.bounds.height() / 2f)
                    drawable.draw(canvas)
                    canvas.restore()
                }
            }, iconStart, detail.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            detail.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: android.view.View) {
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                        android.content.ClipData.newPlainText(label, value))
                    Toast.makeText(this@NotificationsActivity, R.string.minimal_copied, Toast.LENGTH_SHORT).show()
                }
                override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
            }, iconStart, detail.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = detail
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        }
        parent.addView(valueText, LinearLayout.LayoutParams(-1, -2))
    }

}
