package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import im.angry.openeuicc.common.R

/** The reader's permission and connection states share one card, including in the debug preview. */
class UsbReaderStatusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    enum class State { LOADING, PERMISSION, NO_CARD, CARD_UNRESPONSIVE, UNSUPPORTED_CARD, FAILURE }

    private val ui = MinimalUi(context, glassCards = true)
    private val column = ui.column().apply {
        gravity = Gravity.CENTER
        setPadding(ui.dp(18), ui.dp(24), ui.dp(18), ui.dp(24))
        clipChildren = false
        clipToPadding = false
    }
    private val card = ui.card(column).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(paddingLeft, ui.dp(20), paddingRight, ui.dp(20))
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
    }
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_minimal_usb)
        imageTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = ui.text(context.getString(R.string.channel_name_format_usb), 16f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
    private val detail = ui.text("", 13f, true).apply {
        id = R.id.usb_reader_text
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var state = State.LOADING
    var onRetry: (() -> Unit)? = null
    var onGrantPermission: (() -> Unit)? = null
    private val grant = ui.button(card, context.getString(R.string.usb_permission)) {
        when (state) {
            State.PERMISSION -> onGrantPermission?.invoke()
            State.NO_CARD, State.CARD_UNRESPONSIVE, State.UNSUPPORTED_CARD, State.FAILURE -> onRetry?.invoke()
            State.LOADING -> Unit
        }
    }.apply {
        id = R.id.usb_grant_permission
        textSize = 14f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
        insetTop = 0
        insetBottom = 0
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(16) }
    }
    private val loading = ProgressBar(context).apply {
        id = R.id.loading
        indeterminateTintList = ColorStateList.valueOf(ui.color(R.color.minimal_blue))
    }

    init {
        isFillViewport = true
        clipToPadding = false
        clipChildren = false
        addView(column, LayoutParams(-1, -1))
        card.addView(icon, 0, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply {
            bottomMargin = ui.dp(12)
        })
        card.addView(title, 1, LinearLayout.LayoutParams(-1, -2))
        card.addView(detail, 2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        column.addView(loading, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        showState(State.LOADING)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The slot bar makes this viewport asymmetric. Align to the visible window,
        // while keeping the card inside its scrollable area on short screens.
        val window = Rect()
        getWindowVisibleDisplayFrame(window)
        if (window.isEmpty) return
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        for (item in arrayOf(card, loading)) {
            item.translationY = 0f
            if (item.visibility != View.VISIBLE) continue
            val available = height - column.paddingTop - column.paddingBottom
            if (item.height > available) continue
            val location = IntArray(2)
            item.getLocationOnScreen(location)
            val targetTop = (window.exactCenterY() - item.height / 2f).coerceIn(
                (origin[1] + column.paddingTop).toFloat(),
                (origin[1] + height - column.paddingBottom - item.height).toFloat())
            item.translationY = targetTop - location[1]
        }
    }

    fun showState(state: State) {
        this.state = state
        grant.setText(when (state) {
            State.NO_CARD, State.CARD_UNRESPONSIVE, State.UNSUPPORTED_CARD, State.FAILURE -> R.string.usb_detect_again
            else -> R.string.usb_permission
        })
        card.visibility = if (state == State.LOADING) View.GONE else View.VISIBLE
        loading.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        grant.visibility = if (state != State.LOADING) View.VISIBLE else View.GONE
        grant.isEnabled = state != State.LOADING
        detail.setText(when (state) {
            State.NO_CARD -> R.string.usb_no_card
            State.CARD_UNRESPONSIVE -> R.string.usb_card_unresponsive
            State.UNSUPPORTED_CARD -> R.string.usb_unsupported_card
            State.FAILURE -> R.string.usb_read_failed
            else -> R.string.usb_permission_needed
        })
        icon.imageTintList = ColorStateList.valueOf(ui.color(
            if (state == State.FAILURE || state == State.UNSUPPORTED_CARD)
                R.color.minimal_secondary else R.color.minimal_blue))
    }
}
