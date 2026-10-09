package com.rainax.ytdownloader

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A square box (playlist covers on the Music tab). */
class SquareFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val exact = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        super.onMeasure(exact, exact)
    }
}

/**
 * The biggest box of the given shape that fits ([ratio] = height / width): the Music player's cover (square)
 * or its video (16:9). Placed in the middle by its parent.
 */
class FitRatioLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var ratio = 1f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val width = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || h <= 0) w
        else minOf(w, (h / ratio).toInt())
        val height = (width * ratio).toInt()
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
