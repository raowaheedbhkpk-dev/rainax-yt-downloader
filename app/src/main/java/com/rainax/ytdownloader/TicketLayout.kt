package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A cinema ticket (the magazine's Movies section): a dark card with two round notches where the tear-off stub
 * starts ([stubWidth] from the right end) and a dashed tear line between them.
 */
class TicketLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val d = resources.displayMetrics.density
    var stubWidth = 64 * d
    private val notch = 9 * d
    private val corner = 3 * d

    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() }
    private val tear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val shape = Path()
    private val hole = Path()
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = w - stubWidth
        shape.reset()
        rect.set(0f, 0f, w, h)
        shape.addRoundRect(rect, corner, corner, Path.Direction.CW)
        hole.reset()
        hole.addCircle(x, 0f, notch, Path.Direction.CW)
        hole.addCircle(x, h, notch, Path.Direction.CW)
        shape.op(hole, Path.Op.DIFFERENCE)
        canvas.drawPath(shape, card)
        canvas.drawLine(x, notch + 3 * d, x, h - notch - 3 * d, tear)
        super.onDraw(canvas)
    }
}
