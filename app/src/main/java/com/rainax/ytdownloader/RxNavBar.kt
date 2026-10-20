package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The bottom bar, Cinema Pro "Ticket" style: a flat strip the width of the screen with a thin line on top,
 * small capital labels, a short film-red bar above the chosen tab (it slides over when another tab is chosen),
 * and a red square "+" in the middle of the strip.
 *
 * The tabs themselves are still the [BottomNavigationView] child (same menu, badges and selection); this view
 * draws the strip and the red bar and lays out the "+" button ([fab]).
 */
class RxNavBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val barWidth = dp(28f)
    private val barHeight = dp(3f)

    private val stripPaint = Paint()
    private val linePaint = Paint()
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nav: BottomNavigationView? = null
    /** The red square "+" (laid out in the middle of the strip). */
    var fab: View? = null
        private set

    private var selectedId = 0
    private var markX = -1f           // centre of the red bar now
    private var anim: ValueAnimator? = null
    private val settle = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        val amoled = AppPrefs.themeMode(context) == 3
        stripPaint.color = if (amoled) Color.BLACK else themeColor(R.attr.rxBg)
        linePaint.color = ContextCompat.getColor(context, R.color.rx_divider)
        markPaint.color = ContextCompat.getColor(context, R.color.rx_primary)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return Color.WHITE
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is BottomNavigationView) nav = c else if (c.id == R.id.navFab) fab = c
        }
        nav?.isItemActiveIndicatorEnabled = false
    }

    /** Centre x of a tab (in this view), or null before it is laid out. */
    private fun centreOf(itemId: Int): Float? {
        val n = nav ?: return null
        val item = n.findViewById<View>(itemId) ?: return null
        if (item.width == 0) return null
        val a = IntArray(2)
        val b = IntArray(2)
        item.getLocationInWindow(a)
        getLocationInWindow(b)
        return (a[0] - b[0]) + item.width / 2f
    }

    /** The tab [itemId] was chosen: the red bar slides over to it (or is simply placed there when [animate] is false). */
    fun select(itemId: Int, animate: Boolean = true) {
        selectedId = itemId
        val to = centreOf(itemId) ?: run { post { select(itemId, false) }; return }
        anim?.cancel()
        val from = markX
        if (!animate || from < 0f || !ValueAnimator.areAnimatorsEnabled() || kotlin.math.abs(to - from) < 1f) {
            markX = to
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener {
                markX = from + (to - from) * settle.getInterpolation(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val n = nav ?: return
        // the "+": centred in the strip
        fab?.let { f ->
            val cx = (n.left + n.right) / 2
            val cy = (n.top + n.bottom) / 2
            val hw = f.measuredWidth / 2
            val hh = f.measuredHeight / 2
            f.layout(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        // first layout (or the phone turned): put the red bar on the selected tab
        val id = if (selectedId != 0) selectedId else n.selectedItemId
        if (anim?.isRunning != true) post { select(id, false) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        canvas.drawRect(0f, 0f, w, height.toFloat(), stripPaint)
        canvas.drawRect(0f, 0f, w, maxOf(1f, dp(0.7f)), linePaint)
        // the red bar above the chosen tab (not above the "+", which is red itself)
        if (markX >= 0f && selectedId != R.id.nav_social) {
            canvas.drawRect(markX - barWidth / 2f, 0f, markX + barWidth / 2f, barHeight, markPaint)
        }
    }
}
