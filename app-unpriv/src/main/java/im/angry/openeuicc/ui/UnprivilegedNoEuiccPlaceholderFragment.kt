package im.angry.openeuicc.ui

import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import im.angry.openeuicc.common.R
import im.angry.openeuicc.ui.minimal.MinimalUi
import im.angry.openeuicc.util.setupRootViewSystemBarInsets

class UnprivilegedNoEuiccPlaceholderFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ui = MinimalUi(requireContext())
        // Extra space below the centered card lifts it 32dp toward the screen's visual center.
        val bottomSpacing = ui.dp(64)
        val page = ui.page().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(18), 0, ui.dp(18), bottomSpacing)
        }
        val card = ui.card(page).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
        card.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_minimal_sim_search)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        ui.gap(card, 8)
        card.addView(ui.text(getString(im.angry.easyeuicc.R.string.empty_esim_title), 16f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(typeface, 600, false)
        })
        ui.gap(card, 8)
        card.addView(ui.text(getString(im.angry.easyeuicc.R.string.empty_esim_description), 13f, true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })
        return SwipeRefreshLayout(requireContext()).apply {
            id = R.id.swipe_refresh
            ui.styleHomeRefresh(this)
            addView(ui.scroll(page))
            setOnRefreshListener { (requireActivity() as MainActivity).refreshSlots() }
            setupRootViewSystemBarInsets(this, arrayOf({ insets ->
                page.setPadding(ui.dp(18) + insets.left, 0,
                    ui.dp(18) + insets.right, bottomSpacing + insets.bottom)
            }))
        }
    }
}
