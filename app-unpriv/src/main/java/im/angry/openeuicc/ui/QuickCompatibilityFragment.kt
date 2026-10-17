package im.angry.openeuicc.ui

import android.content.pm.PackageManager
import android.icu.text.ListFormatter
import android.os.Build
import android.os.Bundle
import android.se.omapi.Reader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import im.angry.easyeuicc.R
import im.angry.openeuicc.core.IsdrProbeSession
import im.angry.openeuicc.core.probeIsdrCompatibility
import im.angry.openeuicc.common.R as CommonR
import im.angry.openeuicc.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

open class QuickCompatibilityFragment : Fragment(), UnprivilegedEuiccContextMarker {
    companion object {
        enum class Compatibility {
            COMPATIBLE,
            NOT_COMPATIBLE,
        }

        data class CompatibilityResult(
            val slotsOmapi: List<String> = emptyList(),
            val slotsIsdr: List<String> = emptyList()
        ) {
            val compatibility: Compatibility
                get() = if (slotsOmapi.isNotEmpty()) Compatibility.COMPATIBLE else Compatibility.NOT_COMPATIBLE
        }
    }

    private lateinit var status: TextView
    private lateinit var retry: android.widget.Button
    private lateinit var resultIcon: android.widget.ImageView
    private lateinit var detail: TextView
    private lateinit var note: TextView
    private lateinit var deviceCard: android.widget.LinearLayout
    private var iconAnimation: android.animation.ObjectAnimator? = null
    private var checking = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(requireContext(), glassCards = true)
        val page = ui.page()
        ui.gap(page, 30)
        val card = ui.card(page).apply {
            (layoutParams as android.widget.LinearLayout.LayoutParams).bottomMargin = ui.dp(18)
        }
        resultIcon = android.widget.ImageView(requireContext()).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(resultIcon, android.widget.LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12)
        })
        status = ui.text("", 16f).apply {
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create(typeface, 600, false)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { card.addView(it, android.widget.LinearLayout.LayoutParams(-1, -2)) }
        detail = ui.text("", 13f, true).apply {
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        }
        card.addView(detail, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        deviceCard = ui.card(page).apply {
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
        }
        note = ui.text("", 13f, true)
        page.addView(note, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = ui.dp(12); marginStart = ui.dp(2); marginEnd = ui.dp(2)
        })
        retry = ui.button(page, getString(CommonR.string.minimal_retry)) { check() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(8) }
            insetTop = 0; insetBottom = 0; textSize = 16f; cornerRadius = ui.dp(10)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(CommonR.color.minimal_blue))
            setTextColor(android.graphics.Color.WHITE)
        }
        return ui.scroll(page).also { root ->
            setupRootViewSystemBarInsets(root, arrayOf(ui.pageInsets(page, true)))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        check()
    }

    private fun deviceInformation(result: CompatibilityResult? = null, usbHost: Boolean = false) {
        val ui = im.angry.openeuicc.ui.minimal.MinimalUi(requireContext())
        deviceCard.removeAllViews()
        val rows = mutableListOf(
            getString(CommonR.string.minimal_device_model) to "${Build.BRAND} / ${Build.MODEL}",
            getString(CommonR.string.minimal_android_version) to Build.VERSION.RELEASE
        )
        if (result != null) {
            val formatter = ListFormatter.getInstance(ConfigurationCompat.getLocales(resources.configuration).get(0))
            rows += getString(R.string.startup_slots) to if (result.slotsOmapi.isEmpty())
                getString(R.string.startup_not_found) else formatter.format(result.slotsOmapi)
            rows += getString(R.string.startup_isdr) to if (result.slotsIsdr.isEmpty())
                getString(R.string.quick_compatibility_unknown) else formatter.format(result.slotsIsdr)
            rows += "USB Host" to getString(if (usbHost) R.string.startup_supported else R.string.startup_unsupported)
        }
        rows.forEachIndexed { index, (key, value) ->
            if (index > 0) deviceCard.addView(View(requireContext()).apply {
                setBackgroundColor(ui.color(CommonR.color.minimal_line))
            }, android.widget.LinearLayout.LayoutParams(-1, ui.dp(1)))
            val row = ui.column().apply { setPadding(0, ui.dp(12), 0, ui.dp(12)) }
            ui.keyValue(row, key, value, labelSize = 13f, valueSize = 13f)
            deviceCard.addView(row)
        }
    }

    private fun setState(title: Int, description: Int, icon: Int, button: Int, busy: Boolean) {
        status.setText(title)
        detail.setText(description)
        resultIcon.setImageResource(icon)
        retry.setText(button)
        retry.isEnabled = !busy
        retry.alpha = if (busy) 0.42f else 1f
    }

    private fun check() {
        if (checking) return
        checking = true
        setState(R.string.startup_checking, R.string.startup_checking_detail, R.drawable.ic_startup_loading,
            R.string.startup_busy, true)
        note.setText(if (requireActivity().intent.getBooleanExtra(QuickCompatibilityActivity.EXTRA_MANUAL_CHECK, false))
            R.string.startup_manual else R.string.startup_automatic)
        note.isVisible = true
        deviceInformation()
        iconAnimation?.cancel()
        iconAnimation = android.animation.ObjectAnimator.ofFloat(resultIcon, View.ROTATION, 0f, 360f).apply {
            duration = 1100
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        val started = android.os.SystemClock.elapsedRealtime()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                preferenceRepository.quickCompatibilityPassedFlow.updatePreference(false)
                val result = withContext(Dispatchers.IO) {
                    try {
                        getCompatibilityCheckResult()
                    } catch (_: TimeoutCancellationException) {
                        CompatibilityResult()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        CompatibilityResult()
                    }
                }
                // Keep the checking state readable, matching the approved preview transition.
                kotlinx.coroutines.delay((1400 - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                iconAnimation?.cancel()
                resultIcon.rotation = 0f
                val usbHost = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
                deviceInformation(result, usbHost)
                // Match the original device compatibility decision; retain the USB fallback.
                if (result.compatibility == Compatibility.COMPATIBLE || usbHost) {
                    preferenceRepository.quickCompatibilityPassedFlow.updatePreference(true)
                    val manual = requireActivity().intent.getBooleanExtra(QuickCompatibilityActivity.EXTRA_MANUAL_CHECK, false)
                    setState(if (result.compatibility == Compatibility.COMPATIBLE) R.string.startup_ready else R.string.startup_usb_ready,
                        if (result.compatibility == Compatibility.COMPATIBLE) R.string.startup_ready_detail else R.string.startup_usb_detail,
                        R.drawable.ic_startup_success,
                        if (manual) R.string.startup_return_home else R.string.startup_passed, !manual)
                    requireActivity().setResult(android.app.Activity.RESULT_OK)
                    if (manual) {
                        note.isVisible = false
                        retry.setOnClickListener { requireActivity().finish() }
                    } else {
                        note.setText(R.string.startup_entering)
                        kotlinx.coroutines.delay(900)
                        requireActivity().finish()
                    }
                } else {
                    setState(R.string.startup_unavailable, R.string.startup_unavailable_detail,
                        R.drawable.ic_startup_error, CommonR.string.minimal_retry, false)
                    note.isVisible = false
                }
            } finally {
                checking = false
            }
        }
    }

    override fun onDestroyView() {
        iconAnimation?.cancel()
        iconAnimation = null
        super.onDestroyView()
    }

    private suspend fun getCompatibilityCheckResult(): CompatibilityResult {
        val context = requireContext().applicationContext
        val service = withTimeout(5000) { withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.se.omapi.SEService> { continuation ->
                lateinit var connection: android.se.omapi.SEService
                connection = android.se.omapi.SEService(context, context.mainExecutor) {
                    if (continuation.isActive) continuation.resume(connection, onCancellation = { _, value, _ -> value.shutdown() })
                }
                continuation.invokeOnCancellation { connection.shutdown() }
            }
        } }
        try {
            if (!service.isConnected) return CompatibilityResult()
            val readers = service.readers.filter(Reader::isSIM)
            val slotsIsdr = readers.mapNotNull { reader ->
                val compatible = try {
                    withTimeout(5000) {
                        probeIsdrCompatibility {
                            val session = reader.openSession()
                            object : IsdrProbeSession {
                                override fun openChannel(aid: ByteArray): AutoCloseable? =
                                    session.openLogicalChannel(aid)?.let { channel ->
                                        AutoCloseable { channel.close() }
                                    }
                                override fun close() = session.close()
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) { false }
                currentCoroutineContext().ensureActive()
                if (compatible) appContainer.customizableTextProvider.formatNonUsbChannelName(reader.slotIndex) else null
            }
            return CompatibilityResult(
                slotsOmapi = readers.map { appContainer.customizableTextProvider.formatNonUsbChannelName(it.slotIndex) },
                slotsIsdr = slotsIsdr
            )
        } finally {
            service.shutdown()
        }
    }

}

private inline val Reader.isSIM: Boolean
    get() = name.startsWith("SIM")

private inline val Reader.slotIndex: Int
    get() = (name.replace("SIM", "").toIntOrNull() ?: 1) - 1 // 0-based index
