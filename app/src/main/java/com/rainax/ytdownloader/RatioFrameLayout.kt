package com.rainax.ytdownloader

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A box that is always 16:9 (video pictures and the player), unless [fill] is on (fullscreen). */
class RatioFrameLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var fill = false
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fill) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = width * 9 / 16
        // never taller than the room the screen gives (a phone held sideways)
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            height = minOf(height, MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
