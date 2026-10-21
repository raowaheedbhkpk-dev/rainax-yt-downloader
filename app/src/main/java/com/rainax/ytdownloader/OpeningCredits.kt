package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * Opening credits (Settings › Opening title), about 6 seconds, like a film's title card:
 *  0.0 s  a soft glow in the accent colour rises out of the black
 *  0.6 s  R A I N A X is written letter by letter (each letter drifts up, sharpens and fades in)
 *  2.4 s  T U B E follows the same way in the accent colour
 *  3.6 s  a bar draws out under the whole name
 *  3.5 s  "WATCH · SAVE · ENJOY" settles in under it
 *  5.6 s  the card opens into the app (the name grows a touch while everything fades)
 * The name is sized to fit the screen, so all of it (TUBE too) is always on screen.
 * A tap skips it. Not shown when the phone has animations turned off.
 */
object OpeningCredits {

    private const val GLOW_IN = 900L
    private const val FIRST_LETTER = 600L       // when R starts
    private const val STAGGER = 190L            // between letters
    private const val LETTER = 760L             // one letter's drift and fade
    private const val WORD_GAP = 240L           // the pause between RAINAX and TUBE
    private const val HOLD = 1200L              // the finished card stays this long
    private const val OUT = 800L                // opening into the app

    fun show(root: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val c = root.context
        val d = c.resources.displayMetrics.density
        val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)          // smooth in and out
        val soft = PathInterpolator(0.16f, 1f, 0.3f, 1f)             // long, gentle landing
        val display = runCatching { ResourcesCompat.getFont(c, R.font.rx_display) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val accent = Ui.accent(c)

        // size the name so "RAINAX TUBE" takes at most 78% of the screen's width (small phones included)
        val gap = 14 * d
        val wanted = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f, c.resources.displayMetrics)
        val paint = Paint().apply { typeface = display; textSize = wanted }
        val natural = paint.measureText("RAINAX") + paint.measureText("TUBE") + gap + 10 * 3 * d
        val room = c.resources.displayMetrics.widthPixels * 0.78f
        val sizePx = if (natural > room) wanted * room / natural else wanted

        val word = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val letters = mutableListOf<TextView>()
        fun letter(ch: Char, color: Int, gapBefore: Float) {
            val tv = TextView(c).apply {
                text = ch.toString()
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
                typeface = display
                includeFontPadding = true                 // keeps the tops of the tall letters inside the view
                setPadding((1.5f * d).toInt(), 0, (1.5f * d).toInt(), 0)
                alpha = 0f
                translationY = 26 * d
                scaleX = 1.18f
                scaleY = 1.18f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            word.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = gapBefore.toInt() })
            letters += tv
        }
        "RAINAX".forEach { letter(it, Color.WHITE, 0f) }
        "TUBE".forEachIndexed { i, ch -> letter(ch, accent, if (i == 0) gap else 0f) }

        val bar = View(c).apply {
            setBackgroundColor(accent)
            scaleX = 0f
            pivotX = 0f
        }
        val tagline = TextView(c).apply {
            text = "WATCH · SAVE · ENJOY"
            setTextColor(0xFFE8B44A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.45f
            alpha = 0f
            translationY = 10 * d
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            addView(word)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * d).toInt()).apply { topMargin = (16 * d).toInt() })
            addView(tagline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (16 * d).toInt() })
        }
        // the glow behind the name: the accent colour fading out into the black
        val glow = View(c).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 200 * d
                colors = intArrayOf((accent and 0x00FFFFFF) or 0x55000000, Color.TRANSPARENT)
            }
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
        }
        val card = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true                   // nothing behind it can be tapped while it shows
            contentDescription = "RAINAX Tube"
            clipChildren = false
            addView(glow, FrameLayout.LayoutParams((400 * d).toInt(), (400 * d).toInt(), Gravity.CENTER))
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            elevation = 100 * d
        }
        root.addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var gone = false
        fun leave(fast: Boolean) {
            if (gone) return
            gone = true
            val t = if (fast) 350L else OUT
            column.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f).setStartDelay(0).setDuration(t).setInterpolator(ease).withLayer().start()
            glow.animate().scaleX(1.6f).scaleY(1.6f).alpha(0f).setStartDelay(0).setDuration(t).setInterpolator(ease).start()
            card.animate().alpha(0f).setStartDelay(if (fast) 0 else t / 3).setDuration(t).setInterpolator(ease)
                .withEndAction { root.removeView(card) }.start()
        }
        card.setOnClickListener { leave(fast = true) }

        // 1. the glow rises
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(GLOW_IN).setInterpolator(ease).start()
        // 2–3. the letters, one after another (a short breath between RAINAX and TUBE)
        var at = FIRST_LETTER
        letters.forEachIndexed { i, tv ->
            if (i == 6) at += WORD_GAP
            tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(at).setDuration(LETTER).setInterpolator(soft).withLayer().start()
            at += STAGGER
        }
        val written = at - STAGGER + LETTER                    // the last letter has landed
        // 4. the bar draws out under the name
        bar.animate().scaleX(1f).setStartDelay(written - 400).setDuration(900).setInterpolator(ease).start()
        // 5. the tagline settles in under the bar
        tagline.animate().alpha(1f).translationY(0f).setStartDelay(written + 200).setDuration(900).setInterpolator(soft).start()
        // 6. hold, then open into the app
        card.postDelayed({ leave(fast = false) }, written + 200 + 900 + HOLD)
    }
}
