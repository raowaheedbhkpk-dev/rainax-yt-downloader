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
