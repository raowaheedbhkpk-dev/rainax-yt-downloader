package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A 3×3 lock pattern: draw through the dots with one finger. [onPattern] gets the dots in order
 * (0 = top left … 8 = bottom right) when the finger lifts.
 */
class PatternView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onPattern: (List<Int>) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = mutableListOf<Int>()
    private var fingerX = -1f
    private var fingerY = -1f
    private var wrong = false

    private val idle = androidx.core.content.ContextCompat.getColor(context, R.color.rx_text2)
    private val accent = androidx.core.content.ContextCompat.getColor(context, R.color.rx_primary)
    private val error = androidx.core.content.ContextCompat.getColor(context, R.color.rx_error)

    private fun cell() = min(width, height) / 3f
    private fun left() = (width - cell() * 3) / 2f
    private fun top() = (height - cell() * 3) / 2f
    private fun cx(i: Int) = left() + cell() * (i % 3 + 0.5f)
    private fun cy(i: Int) = top() + cell() * (i / 3 + 0.5f)

    /** Shows the pattern in red for a moment (wrong pattern), then clears it. */
    fun showWrong() {
        wrong = true
        invalidate()
        postDelayed({ clear() }, 700)
    }

    fun clear() {
        path.clear()
        wrong = false
        fingerX = -1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val size = min(w, (300 * d).toInt())
        setMeasuredDimension(w, size)
    }

    override fun onDraw(canvas: Canvas) {
        val color = if (wrong) error else accent
        linePaint.color = color
        for (k in 1 until path.size) canvas.drawLine(cx(path[k - 1]), cy(path[k - 1]), cx(path[k]), cy(path[k]), linePaint)
        if (path.isNotEmpty() && fingerX >= 0 && !wrong) canvas.drawLine(cx(path.last()), cy(path.last()), fingerX, fingerY, linePaint)
        for (i in 0 until 9) {
            val on = i in path
            dotPaint.color = if (on) color else idle
            canvas.drawCircle(cx(i), cy(i), (if (on) 9f else 6f) * d, dotPaint)
            if (on) {
                ringPaint.color = color
                canvas.drawCircle(cx(i), cy(i), 22f * d, ringPaint)
            }
        }
    }

    private fun hit(x: Float, y: Float): Int {
        val r = cell() * 0.32f
        for (i in 0 until 9) if (hypot(x - cx(i), y - cy(i)) <= r) return i
        return -1
    }

    private fun add(i: Int) {
        if (i < 0 || i in path) return
        // passing over the middle dot between two dots selects it too (like Android's pattern)
        path.lastOrNull()?.let { last ->
            val mid = (last + i) / 2
            val inLine = (last % 3 + i % 3) % 2 == 0 && (last / 3 + i / 3) % 2 == 0
            if (inLine && mid != last && mid != i && mid !in path) path += mid
        }
        path += i
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || wrong) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.clear()
                add(hit(e.x, e.y))
                fingerX = e.x; fingerY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                add(hit(e.x, e.y))
                fingerX = e.x; fingerY = e.y
            }
            MotionEvent.ACTION_UP -> {
                fingerX = -1f
                val done = path.toList()
                invalidate()
                if (done.isNotEmpty()) onPattern(done)
                return true
            }
            MotionEvent.ACTION_CANCEL -> clear()
        }
        invalidate()
        return true
    }
}
