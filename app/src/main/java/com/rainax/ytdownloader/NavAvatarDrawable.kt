package com.rainax.ytdownloader

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The bottom bar's "You" icon when signed in: your round profile picture, like YouTube, with a thin ring
 * while the You tab is open. The bar colours its icons; this one ignores that so the photo keeps its colours.
 */
class NavAvatarDrawable(bitmap: Bitmap, private val sizePx: Int, private val ringColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ringColor }
    private val src = bitmap
    private var checked = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val ringW = size * 0.09f
        val r = if (checked) size / 2f - ringW * 1.6f else size / 2f
        // cover the circle with the picture (centre crop)
        val scale = (r * 2f) / minOf(src.width, src.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(cx - src.width * scale / 2f, cy - src.height * scale / 2f)
        paint.shader.setLocalMatrix(m)
        canvas.drawCircle(cx, cy, r, paint)
        if (checked) {
            ring.strokeWidth = ringW
            canvas.drawCircle(cx, cy, size / 2f - ringW / 2f, ring)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_checked) || state.contains(android.R.attr.state_selected)
        if (now == checked) return false
        checked = now
        invalidateSelf()
        return true
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // the bar's icon colouring: ignored (the photo keeps its own colours)
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setTintList(tint: ColorStateList?) {}
    override fun setTintMode(tintMode: PorterDuff.Mode?) {}
    override fun mutate(): Drawable = this
}
