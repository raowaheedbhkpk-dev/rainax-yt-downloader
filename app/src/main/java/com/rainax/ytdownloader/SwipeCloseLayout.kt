package com.rainax.ytdownloader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * The side menu's panel: drag it to the left to close it (it follows your finger and springs back if you let go
 * early), like a phone's own side drawers. Taps and up/down scrolling inside work as usual.
 */
class SwipeCloseLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    /** Called when the panel was swiped away (the caller hides the menu). */
    var onSwipedClosed: () -> Unit = {}

    /** 0 (closed) … 1 (open): how far the panel is shown while dragging (for the dark layer behind). */
    var onDrag: (Float) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingSpeed = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                tracker?.recycle(); tracker = VelocityTracker.obtain()
                tracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                // a clear drag to the left: the panel takes it (up/down stays with the list)
                if (dx < -slop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging && ev.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(ev)
        tracker?.addMovement(ev)
        val w = width.coerceAtLeast(1).toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downX).coerceAtMost(0f)
                translationX = dx
                onDrag(1f + dx / w)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val shown = 1f + translationX / w
                dragging = false
                if (ev.actionMasked == MotionEvent.ACTION_UP && (shown < 0.6f || vx < -flingSpeed)) {
                    onSwipedClosed()
                } else {
                    animate().translationX(0f).setDuration(180).withEndAction { onDrag(1f) }.start()
                    onDrag(1f)
                }
            }
        }
        return true
    }
}
