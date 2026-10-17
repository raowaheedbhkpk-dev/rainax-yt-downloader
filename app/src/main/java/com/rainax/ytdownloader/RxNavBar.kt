package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlin.math.PI
import kotlin.math.sin

/**
 * The bottom bar: a floating rounded bar with a soft shadow, a raised round "+" button sitting in a curved cut-out
 * in the middle, and a small dot above the selected tab (in a little dip of the bar's edge). Choosing another tab
 * makes the dot jump over in an arc and land on it, the dip sliding along.
 *
 * The tabs themselves are still the [BottomNavigationView] child (same menu, badges and selection); this view only
 * draws the bar, the dot and lays out the "+" button ([fab]).
 */
class RxNavBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fabRadius = dp(25f)
    private val notchRadius = fabRadius + dp(7f)      // the cut-out around the "+" button
    private val dipRadius = dp(12f)
    private val dipDepth = dp(6.5f)
    private val dotRadius = dp(3.5f)
    private val jump = dp(22f)                          // how high the dot jumps between tabs
    private val highJump = dp(34f)                      // over the "+" button
    private val smallJump = dp(10f)                     // onto / off the "+" button (it already rises there)
    private var jumpNow = jump

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPath = Path()
    private val cut = Path()
    private val rect = RectF()

    private var nav: BottomNavigationView? = null
    /** The raised "+" button (laid out over the middle of the bar). */
    var fab: View? = null
        private set

    private var selectedId = 0
    private var dotX = -1f            // where the dot is now (x)
    private var lift = 0f             // 0 = resting in its dip, 1 = top of the jump
    private var restFrom = Float.NaN  // resting heights at the start and end of a jump (they differ at the "+" button)
    private var restTo = Float.NaN
    private var eased = 1f
    private var anim: ValueAnimator? = null
    private val settle = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)      // (for the bar's soft shadow)
        val amoled = AppPrefs.themeMode(context) == 3
        barPaint.color = if (amoled) ContextCompat.getColor(context, R.color.rx_amoled_alt) else themeColor(R.attr.rxSurface)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        barPaint.setShadowLayer(dp(14f), 0f, dp(4f), if (dark) 0x66000000 else 0x1F2A1A40)
        dotPaint.color = ContextCompat.getColor(context, R.color.rx_primary)
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else Color.WHITE
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is BottomNavigationView) nav = c else if (c.id == R.id.navFab) fab = c
        }
        nav?.isItemActiveIndicatorEnabled = false
    }

    /** The bar's own rectangle (the tabs' area). */
    private fun bar(): RectF? {
        val n = nav ?: return null
        rect.set(n.left.toFloat(), n.top.toFloat(), n.right.toFloat(), n.bottom.toFloat())
        return rect
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

    /** The tab [itemId] was chosen: the dot jumps over to it (or is simply placed there when [animate] is false). */
    fun select(itemId: Int, animate: Boolean = true) {
        selectedId = itemId
        val to = centreOf(itemId) ?: run { post { select(itemId, false) }; return }
        anim?.cancel()
        val from = dotX
        if (!animate || from < 0f || !ValueAnimator.areAnimatorsEnabled() || kotlin.math.abs(to - from) < 1f) {
            dotX = to
            lift = 0f
            restFrom = Float.NaN
            invalidate()
            return
        }
        // crossing the "+" button: a higher arc, over it
        val mid = bar()?.centerX() ?: 0f
        val m = notchRadius
        jumpNow = when {
            kotlin.math.abs(to - mid) < m || kotlin.math.abs(from - mid) < m -> smallJump
            (from - mid) * (to - mid) < 0 -> highJump
            else -> jump
        }
        restFrom = restY(from)
        restTo = restY(to)
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (jumpNow == highJump) 560 else 460
            addUpdateListener {
                val t = it.animatedValue as Float
                eased = settle.getInterpolation(t)
                dotX = from + (to - from) * eased
                lift = sin(PI * t).toFloat()           // up, over, and down onto the new tab
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val r = bar() ?: return
        // the "+" button: centred on the bar's top edge
        fab?.let { f ->
            val cx = r.centerX().toInt()
            val cy = r.top.toInt()
            val half = f.measuredWidth / 2
            f.layout(cx - half, cy - half, cx + half, cy + half)
        }
        // first layout (or the bar moved, e.g. turning the phone): put the dot on the selected tab
        val id = if (selectedId != 0) selectedId else nav?.selectedItemId ?: return
        if (anim?.isRunning != true) post { select(id, false) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = bar() ?: return
        val radius = r.height() / 2f
        barPath.reset()
        barPath.addRoundRect(r, radius, radius, Path.Direction.CW)
        // the curved cut-out for the "+" button
        cut.reset()
        cut.addCircle(r.centerX(), r.top, notchRadius, Path.Direction.CW)
        barPath.op(cut, Path.Op.DIFFERENCE)
        // the little dip under the dot (it closes while the dot is in the air), not under the "+" button
        val nearFab = dotX >= 0f && kotlin.math.abs(dotX - r.centerX()) < notchRadius
        if (dotX >= 0f && !nearFab) {
            val depth = dipDepth * (1f - lift)
            if (depth > 0.5f) {
                cut.reset()
                cut.addCircle(dotX, r.top - dipRadius + depth, dipRadius, Path.Direction.CW)
                barPath.op(cut, Path.Op.DIFFERENCE)
            }
        }
        canvas.drawPath(barPath, barPaint)
    }

    /**
     * The dot is drawn on the screen above the bar (its parent's overlay), so its jump is never cut off at the
     * bar's top edge, and it passes in front of the "+" button.
     */
    private val dotLayer = object : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) {
            if (!isShown) return
            canvas.save()
            canvas.translate(left + translationX, top + translationY)
            drawDot(canvas)
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    /** Set while attached (null early on, when Android may already call invalidate()). */
    private var overlayDot: android.graphics.drawable.Drawable? = null
    private val parentSize = View.OnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> dotLayer.setBounds(0, 0, r - l, b - t) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? android.view.ViewGroup)?.let { p ->
            p.overlay.add(dotLayer)
            dotLayer.setBounds(0, 0, p.width, p.height)
            overlayDot = dotLayer
            p.addOnLayoutChangeListener(parentSize)
        }
    }

    override fun onDetachedFromWindow() {
        (parent as? android.view.ViewGroup)?.let { p ->
            p.overlay.remove(dotLayer)
            p.removeOnLayoutChangeListener(parentSize)
        }
        overlayDot = null
        super.onDetachedFromWindow()
    }

    override fun invalidate() {
        super.invalidate()
        overlayDot?.invalidateSelf()
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        overlayDot?.invalidateSelf()
    }

    private fun drawDot(canvas: Canvas) {
        val r = bar() ?: return
        if (dotX < 0f) return
        val rest = if (anim?.isRunning == true && !restFrom.isNaN()) restFrom + (restTo - restFrom) * eased else restY(dotX)
        canvas.drawCircle(dotX, rest - jumpNow * lift, dotRadius, dotPaint)
    }

    /** Where the dot rests at [x]: on the dipped edge, or above the "+" button (Add open). */
    private fun restY(x: Float): Float {
        val r = bar() ?: return 0f
        return if (kotlin.math.abs(x - r.centerX()) < notchRadius) r.top - fabRadius - dp(9f)
        else r.top + dipDepth - dotRadius - dp(1.5f)
    }
}
