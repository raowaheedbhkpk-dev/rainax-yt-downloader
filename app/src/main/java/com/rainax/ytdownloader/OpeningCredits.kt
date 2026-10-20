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
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Opening credits (Settings › Opening title): for about a second when the app starts, a black title card —
 * RAINAX in big Oswald letters that close up, a red bar that draws out under it and TUBE in gold —
 * then it fades away. A tap skips it. Not shown when the phone has animations turned off.
 */
object OpeningCredits {

    fun show(root: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val c = root.context
        val d = c.resources.displayMetrics.density
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

        val title = TextView(c).apply {
            text = "RAINAX"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = runCatching { ResourcesCompat.getFont(c, R.font.rx_display) }.getOrNull() ?: Typeface.DEFAULT_BOLD
            includeFontPadding = false
            letterSpacing = 0.55f
            alpha = 0f
        }
        val bar = View(c).apply {
            setBackgroundColor(Ui.accent(c))
            scaleX = 0f
        }
        val sub = TextView(c).apply {
            text = "TUBE"
            setTextColor(0xFFE8B44A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.6f
            alpha = 0f
        }
        val column = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(bar, LinearLayout.LayoutParams((64 * d).toInt(), (3 * d).toInt()).apply { topMargin = (14 * d).toInt() })
            addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (12 * d).toInt() })
        }
        val card = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true                   // nothing behind it can be tapped while it shows
            contentDescription = "RAINAX Tube"
            addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            elevation = 100 * d
        }
        root.addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var gone = false
        fun leave() {
            if (gone) return
            gone = true
            card.animate().alpha(0f).setDuration(320).withEndAction { root.removeView(card) }.start()
        }
        card.setOnClickListener { leave() }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750
            interpolator = ease
            addUpdateListener {
                val t = it.animatedValue as Float
                title.alpha = (t * 1.6f).coerceAtMost(1f)
                title.letterSpacing = 0.55f - 0.43f * t            // the letters close up
                bar.scaleX = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                sub.alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
            }
            start()
        }
        card.postDelayed({ leave() }, 1250)
    }
}
