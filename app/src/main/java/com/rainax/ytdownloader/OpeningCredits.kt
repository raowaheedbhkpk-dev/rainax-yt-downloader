package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Opening credits (Settings › Opening title), about 6.5 s, drawn as one picture on the GPU so it is smooth
 * at the screen's full frame rate and always fits the screen:
 *
 *  0.0 s  a stage spotlight flickers on from above (dark theme; soft daylight in the light theme)
 *  0.0 s  the red RAINAX mark spins in, its arrow drops, a neon glow blooms around it
 *  1.4 s  the mark slides left; RAINAX is uncovered on the top line, then TUBE under it (same width)
 *  3.9 s  "WATCH · SAVE · ENJOY" settles in; a band of light runs across the mark and the name
 *  5.6 s  everything fades into the app
 *
 * A floor reflection of the mark and the name sits under them. On phones with an HDR screen (Android 14+)
 * the window switches to HDR for these seconds, so the glow and the light band shine brighter than normal
 * white; other phones show the same picture in normal range. A tap skips it. Not shown when the phone has
 * animations turned off.
 */
object OpeningCredits {

    private const val TOTAL = 6500L
    private const val SHORT = 1300L

    /**
     * The full opening plays once after each install or update; every other start shows a 1.3 s short
     * version (the finished title fades in, holds, and opens into the app) so nobody waits every day.
     */
    fun show(root: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val activity = root.context as? Activity
        val version = runCatching {
            root.context.packageManager.getPackageInfo(root.context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        if (AppPrefs.openingSeen(root.context) == version) { showShort(root); return }
        AppPrefs.setOpeningSeen(root.context, version)
        val view = OpeningView(root.context)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // HDR for these few seconds where the screen supports it (Pixel 8 and similar), back to normal after
        val oldMode = activity?.window?.colorMode ?: ActivityInfo.COLOR_MODE_DEFAULT
        var hdr = false
        if (Build.VERSION.SDK_INT >= 34 && activity != null) {
            val display = activity.display
            // (dark theme only: the light theme has no glow to brighten, so it stays in normal range)
            if (display != null && display.isHdrSdrRatioAvailable && Ui.isDark(activity)) {
                runCatching { activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR; hdr = true }
            }
        }
        view.hdr = hdr

        var done = false
        val anim = ValueAnimator.ofFloat(0f, TOTAL.toFloat()).apply {
            duration = TOTAL
            interpolator = null
            addUpdateListener { view.t = it.animatedValue as Float; view.invalidate() }
        }
        fun finish() {
            if (done) return
            done = true
            root.removeView(view)
            if (hdr) runCatching { activity?.window?.colorMode = oldMode }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = finish()
        })
        // a tap jumps to the fade-out
        view.setOnClickListener {
            if (view.t < OpeningView.OUT_START) anim.currentPlayTime = OpeningView.OUT_START
        }
        anim.start()
    }

    /** The finished title fades in (0.4 s), holds, then the normal fade-out runs (0.9 s). */
    private fun showShort(root: ViewGroup) {
        val view = OpeningView(root.context)
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val outLen = TOTAL - OpeningView.OUT_START
        var done = false
        val anim = ValueAnimator.ofFloat(0f, SHORT.toFloat()).apply {
            duration = SHORT
            interpolator = null
            addUpdateListener {
                val r = it.animatedValue as Float
                view.intro = (r / 400f).coerceIn(0f, 1f)
                view.t = if (r < SHORT - outLen) 5100f else OpeningView.OUT_START + (r - (SHORT - outLen))
                view.invalidate()
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!done) { done = true; root.removeView(view) }
            }
        })
        view.setOnClickListener { anim.end() }
        anim.start()
    }
}

/** The whole opening drawn by hand; [t] is the time in ms (0..6500). */
private class OpeningView(context: Context) : View(context) {

    companion object {
        const val OUT_START = 5600L
    }

    var t = 0f
    var hdr = false
    /** 0..1 fade-in of the title (short opening); the backdrop is always solid so the app never shows through. */
    var intro = 1f

    private val d = resources.displayMetrics.density
    private val dark = Ui.isDark(context)
    private val accent = Ui.accent(context)
    private val ink = if (dark) Color.WHITE else 0xFF111111.toInt()
    private val gold = if (dark) 0xFFE8B44A.toInt() else 0xFF8A6418.toInt()
    private val bgColor = if (dark) Color.BLACK else 0xFFF6F4F0.toInt()

    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private val soft = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val inOut = PathInterpolator(0.65f, 0f, 0.35f, 1f)
    private val bounce = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)

    private val display: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.rx_display) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = display }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        letterSpacing = 0.45f
        color = gold
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    // layout (in px, worked out in onSizeChanged)
    private var scale = 1f
    private var markSize = 0f
    private var gap = 0f
    private var textW = 0f
    private var lineH = 0f
    private var lineGap = 0f
    private var blockW = 0f
    private var blockH = 0f
    private val tubeX = FloatArray(4)
    private val textClip = Path()
    private var spot: Shader? = null
    private var pool: Shader? = null
    private var vignette: Shader? = null
    private var reflMask: Shader? = null

    init {
        isClickable = true
        contentDescription = "RAINAX Tube"
        elevation = 100 * d
        // (no offscreen layer: it would cut the HDR highlights back to normal white)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // the name: RAINAX on top, TUBE under it spread to the same width, the mark as tall as both lines
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, resources.displayMetrics)
        textPaint.letterSpacing = 0.02f
        val r = android.graphics.Rect()
        textPaint.getTextBounds("RAINAX", 0, 6, r)
        lineH = r.height().toFloat()
        lineGap = 10 * d
        textW = textPaint.measureText("RAINAX")
        markSize = lineH * 2 + lineGap
        gap = 16 * d
        blockW = markSize + gap + textW
        blockH = markSize
        // fits any screen: at most 80% of its width
        scale = min(1f, w * 0.8f / blockW)
        // TUBE letters spaced out to RAINAX's width
        val widths = "TUBE".map { textPaint.measureText(it.toString()) }
        val space = (textW - widths.sum()) / 3f
        var x = 0f
        widths.forEachIndexed { i, cw -> tubeX[i] = x; x += cw + space }
        // the letters as a shape (the light band shines only on them)
        textClip.reset()
        val tx = markSize + gap
        val p = Path()
        textPaint.getTextPath("RAINAX", 0, 6, tx, lineH, p); textClip.addPath(p)
        "TUBE".forEachIndexed { i, ch ->
            textPaint.getTextPath(ch.toString(), 0, 1, tx + tubeX[i], lineH * 2 + lineGap, p); textClip.addPath(p)
        }
        textClip.addRect(0f, 0f, markSize, markSize, Path.Direction.CW)
        val cx = w / 2f
        if (dark) {
            spot = LinearGradient(0f, 0f, 0f, h * 0.78f,
                intArrayOf(0x30FFECD2, 0x12FFECD2, 0x00FFECD2), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            pool = RadialGradient(cx, h / 2f + blockH * scale / 2f + 46 * d, 190 * d,
                intArrayOf(0x22FFECD2, 0x00FFECD2), null, Shader.TileMode.CLAMP)
            vignette = RadialGradient(cx, h / 2f, max(w, h) * 0.72f,
                intArrayOf(0x00000000, 0x00000000, 0xA0000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else {
            spot = RadialGradient(cx, h * 0.45f, max(w, h) * 0.45f,
                intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            pool = null
            vignette = RadialGradient(cx, h / 2f, max(w, h) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, 0x1E3C2814), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        reflMask = LinearGradient(0f, 0f, 0f, blockH * 0.6f, intArrayOf(Color.BLACK, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    /** 0..1 progress of [t] between [from] and [to] ms, shaped by [i]. */
    private fun p(from: Long, to: Long, i: android.view.animation.Interpolator = ease): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return i.getInterpolation(x)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    /** A colour brighter than normal white on an HDR screen ([boost] > 1), plain on others. */
    private fun bright(color: Int, alpha: Float, boost: Float): Long {
        val k = if (hdr) boost else 1f
        return Color.pack(Color.red(color) / 255f * k, Color.green(color) / 255f * k, Color.blue(color) / 255f * k, alpha,
            ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f) return
        val out = p(OUT_START, 6500L, ease)
        val bgFade = 1f - out
        val a255 = (255 * bgFade).toInt()
        val fade = bgFade * intro

        // ground
        fill.shader = null
        fill.color = bgColor
        fill.alpha = a255
        canvas.drawRect(0f, 0f, w, h, fill)

        // light: the spotlight flickers on (dark), or soft daylight (light)
        val flicker = when {
            t < 260 -> p(0, 260)
            t < 330 -> 0.35f
            t < 900 -> lerp(0.35f, 1f, p(330, 900))
            else -> 1f
        }
        fill.shader = spot
        fill.alpha = (255 * flicker * fade).toInt()
        if (dark) {
            val cx = w / 2f
            val bottom = h * 0.78f
            for ((topHalf, bottomHalf) in listOf(0.10f to 0.62f, 0.08f to 0.48f, 0.06f to 0.34f)) {
                val cone = Path().apply {
                    moveTo(cx - w * topHalf, -40 * d); lineTo(cx + w * topHalf, -40 * d)
                    lineTo(cx + w * bottomHalf, bottom); lineTo(cx - w * bottomHalf, bottom); close()
                }
                canvas.drawPath(cone, fill)
            }
            fill.shader = pool
            canvas.drawRect(0f, 0f, w, h, fill)
        } else {
            canvas.drawRect(0f, 0f, w, h, fill)
        }
        fill.shader = vignette
        fill.alpha = a255
        canvas.drawRect(0f, 0f, w, h, fill)

        // the block: centred, scaled to fit, growing a touch as it fades out
        val s = scale * (1f + 0.04f * out)
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(s, s)
        canvas.translate(-blockW / 2f, -blockH / 2f)

        val markX = lerp(blockW / 2f - markSize / 2f, 0f, p(1430, 2470, soft))
        drawRow(canvas, markX, fade, reflection = false)

        // the light band across the mark and the name
        val sheen = p(4160, 5070, inOut)
        if (sheen in 0.001f..0.999f) {
            canvas.save()
            canvas.clipPath(textClip)
            val bx = lerp(-80 * d, blockW + 80 * d, sheen)
            val band = 46 * d
            fill.shader = LinearGradient(bx - band, 0f, bx + band, 0f,
                longArrayOf(bright(Color.WHITE, 0f, 1f), bright(Color.WHITE, 0.75f * fade, 2.2f), bright(Color.WHITE, 0f, 1f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            fill.alpha = 255
            canvas.skew(-0.32f, 0f)
            canvas.drawRect(bx - band + blockH * 0.32f, -blockH, bx + band + blockH * 0.32f, blockH * 2, fill)
            canvas.restore()
        }

        // the floor reflection, fading out downwards
        val gapBelow = 8 * d
        val layer = canvas.saveLayer(-20 * d, blockH + gapBelow, blockW + 20 * d, blockH * 2 + gapBelow, null)
        canvas.save()
        canvas.translate(0f, blockH * 2 + gapBelow)
        canvas.scale(1f, -1f)
        drawRow(canvas, markX, fade * (if (dark) 0.24f else 0.13f), reflection = true)
        canvas.restore()
        canvas.translate(0f, blockH + gapBelow)
        maskPaint.shader = reflMask
        canvas.drawRect(-20 * d, 0f, blockW + 20 * d, blockH, maskPaint)
        canvas.restoreToCount(layer)

        canvas.restore()

        // the tagline, under the reflection
        val tag = p(3900, 4680, soft)
        if (tag > 0f) {
            tagPaint.alpha = (255 * tag * fade).toInt()
            val y = h / 2f + (blockH / 2f + gapBelow + blockH * 0.45f) * s + 26 * d + (1f - tag) * 10 * d
            canvas.drawText("WATCH · SAVE · ENJOY", w / 2f, y, tagPaint)
        }
    }

    /** The mark (with its arrow and glow) and the two lines of the name, as they are at time [t]. */
    private fun drawRow(canvas: Canvas, markX: Float, alpha: Float, reflection: Boolean) {
        // 1. the mark spins in
        val spin = p(0, 1040, soft)
        if (spin > 0f) {
            canvas.save()
            val mcx = markX + markSize / 2f
            val mcy = markSize / 2f
            canvas.translate(mcx, mcy)
            canvas.rotate(lerp(-120f, 0f, spin))
            val sc = lerp(0.3f, 1f, spin)
            canvas.scale(sc, sc)

            // the neon glow: blooms when the arrow lands, then settles
            if (!reflection && dark) {
                val bloom = when {
                    t < 1430 -> p(1100, 1430)
                    else -> lerp(1f, 0.55f, p(1430, 2470))
                } * alpha
                if (bloom > 0f) {
                    fill.shader = RadialGradient(0f, 0f, markSize * 1.25f,
                        longArrayOf(bright(accent, 0.6f * bloom, 1.8f), bright(accent, 0.18f * bloom, 1.2f), bright(accent, 0f, 1f)),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    fill.alpha = 255
                    canvas.drawCircle(0f, 0f, markSize * 1.25f, fill)
                }
            } else if (!reflection) {
                // light theme: a real soft shadow under the mark instead of a glow
                fill.shader = RadialGradient(0f, markSize * 0.18f, markSize * 0.9f,
                    intArrayOf(0x40000000, 0x00000000), null, Shader.TileMode.CLAMP)
                fill.alpha = (255 * spin * alpha).toInt()
                canvas.drawCircle(0f, markSize * 0.18f, markSize * 0.9f, fill)
            }

            fill.shader = null
            fill.color = accent
            fill.alpha = (255 * spin * alpha).toInt()
            val half = markSize / 2f
            canvas.drawRect(-half, -half, half, half, fill)

            // 2. its arrow drops in and bounces
            val drop = p(910, 1430, bounce)
            if (drop > 0f) {
                arrowPaint.strokeWidth = markSize * 0.075f
                arrowPaint.alpha = (255 * min(1f, drop * 2f) * alpha).toInt()
                val u = markSize / 48f * 2f                     // the 24-unit icon drawn at half the mark
                canvas.save()
                canvas.translate(-12 * u, -12 * u + (1f - drop) * -22 * d)
                val arrow = Path().apply {
                    moveTo(12 * u, 4 * u); lineTo(12 * u, 15 * u)
                    moveTo(7 * u, 10.5f * u); lineTo(12 * u, 15.5f * u); lineTo(17 * u, 10.5f * u)
                    moveTo(5 * u, 20 * u); lineTo(19 * u, 20 * u)
                }
                canvas.drawPath(arrow, arrowPaint)
                canvas.restore()
            }
            canvas.restore()
        }

        // 3. RAINAX is uncovered from behind the mark, then TUBE under it
        val tx = markSize + gap
        val top = p(2210, 3120, inOut)
        if (top > 0f) {
            canvas.save()
            canvas.clipRect(tx, -lineH, tx + textW * top + 2 * d, lineH + lineGap / 2f)
            textPaint.color = ink
            textPaint.alpha = (255 * alpha).toInt()
            canvas.drawText("RAINAX", tx - (1f - top) * 14 * d, lineH, textPaint)
            canvas.restore()
        }
        val bottom = p(2860, 3770, inOut)
        if (bottom > 0f) {
            canvas.save()
            canvas.clipRect(tx, lineH + lineGap / 2f, tx + textW * bottom + 2 * d, markSize + lineH)
            textPaint.color = accent
            textPaint.alpha = (255 * alpha).toInt()
            val shift = (1f - bottom) * 14 * d
            "TUBE".forEachIndexed { i, ch -> canvas.drawText(ch.toString(), tx + tubeX[i] - shift, lineH * 2 + lineGap, textPaint) }
            canvas.restore()
        }
    }
}
