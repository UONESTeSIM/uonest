package im.angry.openeuicc.ui.minimal

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import im.angry.openeuicc.common.R
import kotlin.math.abs

/** Swipes reveal actions; they never execute an eUICC operation. */
class ProfileCardView(context: Context) : FrameLayout(context) {
    private val ui = MinimalUi(context)
    val name = ui.text("", 14f).apply {
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 800, false)
        setTextColor(ui.color(R.color.minimal_profile_name))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val provider = ui.text("", 13f, true).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        setTextColor(ui.color(R.color.minimal_profile_provider))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val iccid = ui.text("", 12f)
    val profileClass = ui.text("", 13f, true)
    val toggle = MinimalSwitch(context)
    val icon = FrameLayout(context)
    private val divider = android.view.View(context)
    private val countryBadge = ImageView(context).apply { visibility = GONE }
    private val front = LinearLayout(context)
    private val edit = ui.text(context.getString(R.string.minimal_edit))
    private val delete = ui.text(context.getString(R.string.profile_delete))
    var onEdit: () -> Unit = {}
    var onDelete: () -> Unit = {}
    var canDelete = false
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val reveal = ui.dp(80).toFloat()

    init {
        layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2).apply {
            setMargins(ui.dp(18), ui.dp(5), ui.dp(18), ui.dp(5))
        }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ui.dp(14).toFloat(); setColor(ui.color(R.color.minimal_profile_surface))
        }
        clipToOutline = true
        elevation = 5f * resources.displayMetrics.density
        outlineAmbientShadowColor = ui.color(R.color.minimal_profile_shadow)
        outlineSpotShadowColor = ui.color(R.color.minimal_profile_shadow)
        edit.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        delete.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        edit.gravity = Gravity.CENTER
        delete.gravity = Gravity.CENTER
        edit.setTextColor(android.graphics.Color.WHITE)
        delete.setTextColor(android.graphics.Color.WHITE)
        edit.setBackgroundColor(ui.color(R.color.minimal_blue))
        delete.setBackgroundColor(ui.color(R.color.minimal_red))
        edit.visibility = INVISIBLE
        delete.visibility = INVISIBLE
        addView(edit, LayoutParams(ui.dp(80), -1, Gravity.LEFT))
        addView(delete, LayoutParams(ui.dp(80), -1, Gravity.RIGHT))
        edit.setOnClickListener { close(); onEdit() }
        delete.setOnClickListener { close(); if (canDelete) onDelete() }
        front.setOnClickListener { close() }
        front.orientation = LinearLayout.VERTICAL
        front.background = HomeGlass.surface(front)
        front.setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10))
        addView(front, LayoutParams(-1, -2))
        icon.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ui.dp(10).toFloat(); setColor(ui.color(R.color.minimal_blue_soft))
        }
        icon.clipToOutline = true
        val topRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        front.addView(topRow, LinearLayout.LayoutParams(-1, -2))
        topRow.addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        topRow.addView(divider.apply {
            setBackgroundColor(ui.color(R.color.minimal_line))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(1), -1).apply {
            marginStart = ui.dp(14); marginEnd = ui.dp(14)
            topMargin = ui.dp(7); bottomMargin = ui.dp(7)
        })
        val labels = ui.column().apply { clipChildren = false }
        val nameRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        nameRow.addView(countryBadge, LinearLayout.LayoutParams(ui.dp(18), ui.dp(12)).apply {
            marginEnd = ui.dp(6)
        })
        nameRow.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        labels.addView(nameRow, LinearLayout.LayoutParams(-1, -2))
        labels.addView(provider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        topRow.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        topRow.addView(toggle, LinearLayout.LayoutParams(ui.dp(57), ui.dp(44)))
        val iccidRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        iccid.setTextColor(ui.color(R.color.minimal_profile_iccid))
        iccidRow.addView(iccid, LinearLayout.LayoutParams(-2, -2))
        iccidRow.addView(androidx.appcompat.widget.AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_minimal_copy)
            background = null
            setPaddingRelative(ui.dp(5), 0, ui.dp(5), 0)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${context.getString(R.string.minimal_copy)} ICCID"
            tooltipText = contentDescription
            setOnClickListener {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("ICCID", iccid.text.toString()))
                Toast.makeText(context, R.string.minimal_copied, Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ui.dp(24), -1))
        iccidRow.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val availableWidth = (right - left - ui.dp(24)).coerceAtLeast(0)
            if (iccid.maxWidth != availableWidth) iccid.maxWidth = availableWidth
        }
        labels.addView(iccidRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        labels.addView(profileClass)
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.minimal_edit)) { _, _ -> onEdit(); true }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.profile_delete)) { _, _ ->
            if (canDelete) onDelete()
            canDelete
        }
    }

    fun setIccid(value: String) {
        iccid.text = value
        iccid.contentDescription = "ICCID $value"
    }

    fun setCountry(country: String?) {
        countryBadge.visibility = GONE
        icon.removeAllViews()
        icon.layoutParams = icon.layoutParams.apply { width = ui.dp(40) }
        icon.clipToOutline = true
        divider.layoutParams = (divider.layoutParams as LinearLayout.LayoutParams).apply { marginStart = ui.dp(14) }
        (icon.background.mutate() as android.graphics.drawable.GradientDrawable).cornerRadius =
            if (country.isNullOrEmpty()) ui.dp(10).toFloat() else ui.dp(17).toFloat()
        (icon.background as android.graphics.drawable.GradientDrawable).setColor(ui.color(R.color.minimal_blue_soft))
        icon.elevation = 0f
        icon.invalidateOutline()
        if (country.isNullOrEmpty()) {
            icon.layoutParams = icon.layoutParams.apply { width = LayoutParams.WRAP_CONTENT }
            icon.clipToOutline = false
            (icon.background as android.graphics.drawable.GradientDrawable).setColor(android.graphics.Color.TRANSPARENT)
            icon.addView(ImageView(context).apply { setImageResource(R.drawable.ic_minimal_sim) },
                LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        } else {
            val flag = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(ui.color(R.color.minimal_blue_soft))
                }
                clipToOutline = true
                val path = "flags/${country.lowercase(java.util.Locale.ROOT)}.png"
                val bitmap = runCatching { context.assets.open(path).use(android.graphics.BitmapFactory::decodeStream) }.getOrNull()
                if (bitmap != null) {
                    // Extend the edge colors into the circle instead of clipping
                    // a full-size flag or exposing straight inset edges.
                    val size = ui.dp(34)
                    val scale = (size - ui.dp(4)).toFloat() / maxOf(bitmap.width, bitmap.height)
                    val shader = android.graphics.BitmapShader(bitmap,
                        android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                    shader.setLocalMatrix(android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((size - bitmap.width * scale) / 2, (size - bitmap.height * scale) / 2)
                    })
                    setImageDrawable(android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                        intrinsicWidth = size
                        intrinsicHeight = size
                        paint.isAntiAlias = true
                        paint.isFilterBitmap = true
                        paint.shader = shader
                    })
                } else setImageResource(R.drawable.ic_minimal_sim)
            }
            icon.addView(flag, LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER))
        }
    }

    fun setBranding(logoBase64: String, country: String?, provider: String = "") {
        setBranding(ProfileBranding.logo(context, logoBase64, provider), country)
    }

    fun setBranding(logo: android.graphics.Bitmap?, country: String?, brandName: String? = null,
        englishName: String? = null) {
        setCountry(null)
        if (logo != null) {
            icon.removeAllViews()
            icon.layoutParams = icon.layoutParams.apply { width = LayoutParams.WRAP_CONTENT }
            icon.clipToOutline = false
            divider.layoutParams = (divider.layoutParams as LinearLayout.LayoutParams).apply { marginStart = ui.dp(14) }
            (icon.background as android.graphics.drawable.GradientDrawable).apply {
                cornerRadius = ui.dp(10).toFloat()
                setColor(android.graphics.Color.TRANSPARENT)
            }
            icon.invalidateOutline()
            val contents = ui.column().apply { gravity = Gravity.CENTER }
            contents.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(logo)
                contentDescription = brandName
            }, LinearLayout.LayoutParams(ui.dp(if (brandName == null) 32 else 20),
                ui.dp(if (brandName == null) 32 else 20)))
            if (brandName != null) {
                contents.addView(ui.text(brandName, 6f).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    setTextColor(ui.color(R.color.minimal_blue))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(1) })
                contents.addView(ui.text(englishName.orEmpty(), 4.5f).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false
                    setTextColor(ui.color(R.color.minimal_blue))
                }, LinearLayout.LayoutParams(-2, -2))
            }
            icon.addView(contents, LayoutParams(-2, -1))
        }
        if (country != null) {
            val flag = runCatching {
                context.assets.open("flags/${country.lowercase(java.util.Locale.ROOT)}.png")
                    .use(android.graphics.BitmapFactory::decodeStream)
            }.getOrNull()
            if (flag != null) {
                countryBadge.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ui.dp(1).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
                countryBadge.elevation = 1.5f * resources.displayMetrics.density
                countryBadge.outlineAmbientShadowColor = ui.color(R.color.minimal_card_shadow)
                countryBadge.outlineSpotShadowColor = ui.color(R.color.minimal_card_shadow)
                countryBadge.setPadding(0, 0, 0, 0)
                countryBadge.scaleType = ImageView.ScaleType.FIT_CENTER
                countryBadge.clipToOutline = false
                val width = ui.dp(18)
                val height = ui.dp(12)
                val scale = minOf(width.toFloat() / flag.width, height.toFloat() / flag.height)
                val shader = android.graphics.BitmapShader(flag, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - flag.width * scale) / 2, (height - flag.height * scale) / 2)
                    })
                }
                countryBadge.setImageDrawable(android.graphics.drawable.ShapeDrawable(
                    android.graphics.drawable.shapes.RoundRectShape(FloatArray(8) { ui.dp(1).toFloat() }, null, null)
                ).apply {
                    intrinsicWidth = width; intrinsicHeight = height
                    paint.isAntiAlias = true; paint.isFilterBitmap = true; paint.shader = shader
                })
                countryBadge.contentDescription = java.util.Locale("", country).getDisplayCountry()
                countryBadge.visibility = VISIBLE
            }
        }
    }

    private fun updateActionVisibility() {
        edit.visibility = if (front.translationX > 0f) VISIBLE else INVISIBLE
        delete.visibility = if (front.translationX < 0f && canDelete) VISIBLE else INVISIBLE
    }

    fun close() {
        front.animate().cancel()
        front.translationX = 0f
        updateActionVisibility()
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                front.animate().cancel()
                downX = event.x; downY = event.y; startOffset = front.translationX; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (abs(dx) > slop && abs(dx) > abs(event.y - downY) * 1.5f) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                front.translationX = (startOffset + event.x - downX)
                    .coerceIn(if (canDelete) -reveal else 0f, reveal)
                updateActionVisibility()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val target = if (event.actionMasked == MotionEvent.ACTION_CANCEL || abs(front.translationX) < reveal / 2) 0f
                    else if (front.translationX > 0) reveal else -reveal
                front.animate().translationX(target).setDuration(160)
                    .setUpdateListener { updateActionVisibility() }.start()
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

