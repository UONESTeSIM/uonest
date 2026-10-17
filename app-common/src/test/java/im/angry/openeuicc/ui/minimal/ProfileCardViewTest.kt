package im.angry.openeuicc.ui.minimal

import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import im.angry.openeuicc.common.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProfileCardViewTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenEUICC)

    @Test fun `switch keeps the confirmed state while a chip operation is pending`() {
        val toggle = ProfileCardView(context).toggle
        var enableRequests = 0
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) enableRequests++
            toggle.isChecked = false
        }
        toggle.performClick()
        assertEquals(1, enableRequests)
        assertFalse(toggle.isChecked)
        assertEquals("android.widget.Switch", toggle.accessibilityClassName)
    }

    @Test fun `a swipe only reveals an action and deleting still requires tapping it`() {
        val card = ProfileCardView(context)
        card.canDelete = true
        var deletes = 0
        card.onDelete = { deletes++ }
        swipe(card, 250f, 50f)
        assertEquals(0, deletes)
        val button = (0 until card.childCount).map { card.getChildAt(it) }
            .filterIsInstance<TextView>().single { it.text == context.getString(R.string.profile_delete) }
        button.performClick()
        assertEquals(1, deletes)
    }

    @Test fun `active profile cannot be deleted through the revealed action`() {
        val card = ProfileCardView(context)
        card.canDelete = false
        var deletes = 0
        card.onDelete = { deletes++ }
        swipe(card, 250f, 50f)
        (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<TextView>()
            .single { it.text == context.getString(R.string.profile_delete) }.performClick()
        assertEquals(0, deletes)
    }

    @Test fun `vertical scrolling never invokes a card action`() {
        val card = ProfileCardView(context)
        card.onEdit = { fail("scrolling must not edit a profile") }
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 30f, 0)
        val move = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 101f, 140f, 0)
        card.onInterceptTouchEvent(down)
        assertFalse(card.onInterceptTouchEvent(move))
        down.recycle(); move.recycle()
    }

    private fun swipe(card: ProfileCardView, from: Float, to: Float) {
        val parent = android.widget.FrameLayout(context)
        parent.addView(card)
        parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 400, 160)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, from, 40f, 0)
        val move = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, to, 40f, 0)
        val up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, to, 40f, 0)
        card.dispatchTouchEvent(down)
        card.dispatchTouchEvent(move)
        // The first move cancels the child's touch target; the next move drives the drag.
        val drag = MotionEvent.obtain(0, 30, MotionEvent.ACTION_MOVE, to, 40f, 0)
        card.dispatchTouchEvent(drag)
        if (card.canDelete) assertTrue(card.getChildAt(2).translationX < 0f)
        else assertEquals(0f, card.getChildAt(2).translationX)
        card.dispatchTouchEvent(up)
        drag.recycle()
        down.recycle(); move.recycle(); up.recycle()
    }
}
