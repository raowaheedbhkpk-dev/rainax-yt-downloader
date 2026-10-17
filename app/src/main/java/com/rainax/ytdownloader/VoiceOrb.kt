package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * RAINAX's listening orb: three soft blobs of red, pink and coral that drift around each other and swell with
 * your voice, and rings that ripple outward when you speak loudly. Calm "breathing" while it waits.
 */
class VoiceOrb @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 0 = quiet … 1 = loud (from the recognizer's sound level). */
    private var target = 0f
    private var level = 0f
    private var t = 0f
    /** listening: blobs move; thinking: they spin faster and shrink; idle: slow breath. */
    var mode = MODE_IDLE
        set(v) { field = v; invalidate() }

    private val colors = intArrayOf(0xFFFF1F4B.toInt(), 0xFFFF5C8A.toInt(), 0xFFFF8A3D.toInt())
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (android.os.Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.SCREEN
    }
    /** one soft gradient per colour, made once (radius 1 at 0,0) and moved/scaled each frame: no garbage */
    private val shaders = colors.map { c ->
        RadialGradient(0f, 0f, 1f, intArrayOf(c, (c and 0x00FFFFFF) or 0x66000000, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    private val shaderMatrix = android.graphics.Matrix()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ripples = ArrayList<FloatArray>()   // [progress 0..1]
    private var lastRipple = 0L
    private var frame: ValueAnimator? = null

    fun setLevel(rmsDb: Float) {
        // the recognizer reports about -2 dB (silence) to 10 dB (loud)
        target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        frame = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            var last = System.nanoTime()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                step(dt)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        frame?.cancel()
        frame = null
        super.onDetachedFromWindow()
    }

    private fun step(dt: Float) {
        val speed = when (mode) { MODE_THINKING -> 3.2f; MODE_LISTENING -> 1f + level * 1.5f; else -> 0.45f }
        t += dt * speed
        val goal = if (mode == MODE_LISTENING) target else 0f
        level += (goal - level) * (if (goal > level) 0.35f else 0.12f)
        // a ripple when the voice gets loud
        val nowMs = System.currentTimeMillis()
        if (mode == MODE_LISTENING && level > 0.5f && nowMs - lastRipple > 380) {
            ripples += floatArrayOf(0f)
            lastRipple = nowMs
        }
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r[0] += dt / 1.3f
            if (r[0] >= 1f) it.remove()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val max = minOf(width, height) / 2f
        val base = max * 0.42f
        val breath = if (mode == MODE_IDLE) 0.04f * sin(t * 2.4f) else 0f
        val shrink = if (mode == MODE_THINKING) 0.85f else 1f

        // rings rippling outward
        for (r in ripples) {
            val p = r[0]
            ringPaint.strokeWidth = max * 0.012f * (1f - p) + 1f
            ringPaint.color = 0xFFFF2E55.toInt()
            ringPaint.alpha = ((1f - p) * 150).toInt()
            canvas.drawCircle(cx, cy, base * (1.05f + p * 1.25f), ringPaint)
        }

        // three blobs drifting around the centre
        for (i in 0 until 3) {
            val a = t * (0.9f + i * 0.35f) + i * 2.094f
            val drift = base * (0.10f + level * 0.22f)
            val bx = cx + cos(a) * drift
            val by = cy + sin(a * 1.3f) * drift
            val rad = base * shrink * (1f + breath + level * 0.38f + 0.05f * sin(t * 3f + i))
            shaderMatrix.setScale(rad * 1.35f, rad * 1.35f)
            shaderMatrix.postTranslate(bx, by)
            shaders[i].setLocalMatrix(shaderMatrix)
            blobPaint.shader = shaders[i]
            canvas.drawCircle(bx, by, rad * 1.35f, blobPaint)
        }
    }

    companion object {
        const val MODE_IDLE = 0
        const val MODE_LISTENING = 1
        const val MODE_THINKING = 2
    }
}
