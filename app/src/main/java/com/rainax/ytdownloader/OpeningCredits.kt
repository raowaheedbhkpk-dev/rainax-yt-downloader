package com.rainax.ytdownloader

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
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
 * Opening credits (Settings › Opening title): when the app starts, "RAINAX TUBE" is written letter by letter
 * on a black title card — each letter rises and fades in a moment after the one before, RAINAX in white and
 * TUBE in the accent colour — then a bar draws out under the name, and the card opens into the app
 * (the name grows a touch while everything fades). About 1.7 s; a tap skips it. Not shown when the phone has
 * animations turned off.
 */
object OpeningCredits {

    private const val STAGGER = 55L        // ms between letters
    private const val LETTER = 420L        // one letter's rise and fade

    fun show(root: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val c = root.context
        val d = c.resources.displayMetrics.density
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f)          // fast start, soft landing
        val display = runCatching { ResourcesCompat.getFont(c, R.font.rx_display) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val accent = Ui.accent(c)

        // the name, one TextView per letter so each can be written on its own
        val word = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val letters = mutableListOf<TextView>()
        fun letter(ch: Char, color: Int, gapBefore: Float = 0f) {
            val tv = TextView(c).apply {
                text = ch.toString()
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
                typeface = display
                includeFontPadding = false
                alpha = 0f
                translationY = 22 * d
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            word.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (gapBefore + 1.5f * d).toInt() })
            letters += tv
        }
        "RAINAX".forEach { letter(it, Color.WHITE) }
        "TUBE".forEachIndexed { i, ch -> letter(ch, accent, if (i == 0) 12 * d else 0f) }

        val bar = View(c).apply {
            setBackgroundColor(accent)
            scaleX = 0f
            pivotX = 0f
        }
        val tagline = TextView(c).apply {
            text = "WATCH · SAVE · ENJOY"
            setTextColor(0xFFE8B44A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.45f
            alpha = 0f
        }
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(word)
            addView(bar, LinearLayout.LayoutParams(-1, (3 * d).toInt()).apply { topMargin = (14 * d).toInt() })
            addView(tagline, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * d).toInt() })
        }
        val card = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true                   // nothing behind it can be tapped while it shows
            contentDescription = "RAINAX Tube"
            clipChildren = false
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            elevation = 100 * d
        }
        root.addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var gone = false
        fun leave() {
            if (gone) return
            gone = true
            // the card opens into the app: the name grows a little while everything fades
            column.animate().scaleX(1.08f).scaleY(1.08f).setDuration(380).setInterpolator(ease).start()
            card.animate().alpha(0f).setDuration(380).setInterpolator(ease).withEndAction { root.removeView(card) }.start()
        }
        card.setOnClickListener { leave() }

        // 1. the letters, written one after another
        letters.forEachIndexed { i, tv ->
            tv.animate().alpha(1f).translationY(0f).setStartDelay(120 + i * STAGGER).setDuration(LETTER).setInterpolator(ease).start()
        }
        val written = 120 + (letters.size - 1) * STAGGER + LETTER
        // 2. the bar draws out under the name, 3. the tagline fades in
        bar.animate().scaleX(1f).setStartDelay(written - 180).setDuration(420).setInterpolator(ease).start()
        tagline.animate().alpha(1f).setStartDelay(written).setDuration(300).start()
        // 4. a short hold, then into the app
        card.postDelayed({ leave() }, written + 650)
    }
}
