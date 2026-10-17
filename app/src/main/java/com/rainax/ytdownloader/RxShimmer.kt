package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

/**
 * A loading placeholder that shimmers: a soft band of light sweeps across the grey shapes inside it
 * (only over the shapes, never the empty space). Used as the root of the skeleton rows.
 */
class RxShimmer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val shift = Matrix()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)     // own layer, so the light only lands on the shapes
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val light = if (night) 0x26FFFFFF else 0x8CFFFFFF.toInt()
        val band = w * 0.45f
        paint.shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0, light, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val shader = paint.shader ?: return
        val band = width * 0.45f
        val x = -band + (width + band * 2) * progress
        shift.setTranslate(x, 0f)
        shift.postSkew(-0.25f, 0f)
        shader.setLocalMatrix(shift)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
