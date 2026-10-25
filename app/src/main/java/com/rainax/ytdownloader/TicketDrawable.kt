package com.rainax.ytdownloader

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A ticket (download sheet quality cards): a card with two round notches on its sides [stubHeight] above the
 * bottom, and a dashed tear line between them, so the size sits on a tear-off stub.
 */
class TicketDrawable(
    private val fill: Int,
    private val stroke: Int,
    private val strokeWidth: Float,
    private val density: Float,
    private val stubHeight: Float = 34 * density
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke
        style = Paint.Style.STROKE
        strokeWidth = this@TicketDrawable.strokeWidth
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    }
    private val path = Path()
    private val hole = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val half = strokeWidth / 2f
        val notch = 7 * density
        val y = b.bottom - stubHeight
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        path.reset()
        path.addRoundRect(rect, 2 * density, 2 * density, Path.Direction.CW)
        hole.reset()
        hole.addCircle(b.left.toFloat(), y, notch, Path.Direction.CW)
        hole.addCircle(b.right.toFloat(), y, notch, Path.Direction.CW)
        path.op(hole, Path.Op.DIFFERENCE)
        canvas.drawPath(path, fillPaint)
        if (strokeWidth > 0f) canvas.drawPath(path, strokePaint)
        canvas.drawLine(b.left + notch + 4 * density, y, b.right - notch - 4 * density, y, dashPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha; strokePaint.alpha = alpha; dashPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter; dashPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
