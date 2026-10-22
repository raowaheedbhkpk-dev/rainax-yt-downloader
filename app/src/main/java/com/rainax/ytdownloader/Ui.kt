package com.rainax.ytdownloader

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Small shared look helpers. */
object Ui {

    /** Extra bottom room (px) every list has while the app-update pill floats above the bottom bar. */
    @JvmStatic var pillExtra = 0

    /**
     * Makes a small button easier to hit: its touch area grows to at least [minDp] square (the picture stays
     * the same size). Uses the parent's TouchDelegate, so it suits one small button per parent.
     */
    fun expandTouch(v: android.view.View, minDp: Int = 48) {
        val min = (minDp * v.resources.displayMetrics.density).toInt()
        fun apply() {
            val parent = v.parent as? android.view.View ?: return
            val r = android.graphics.Rect()
            v.getHitRect(r)
            if (r.isEmpty) return
            r.inset(-((min - r.width()) / 2).coerceAtLeast(0), -((min - r.height()) / 2).coerceAtLeast(0))
            parent.touchDelegate = android.view.TouchDelegate(r, v)
        }
        // worked out again whenever the button is laid out (it may start hidden or move)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        v.post { apply() }
    }


    /**
     * How big everything is drawn, worked out from the screen itself (no setting): roomy screens (Pixel 8:
     * about 411 × 914 dp, tablets) keep the phone's own size; narrow or short ones (HD phones like the itel A70:
     * about 360 × 800 dp) are drawn a little smaller, so text isn't cut and buttons at the bottom stay on screen.
     * [swDp] and [longDp] are the screen's short and long sides at the phone's normal size.
     */
    private fun sizeFactor(swDp: Int, longDp: Int): Float {
        if (swDp >= 600) return 1f                                   // tablets and open foldables
        val byWidth = if (swDp > 0) swDp / 400f else 1f
        val byHeight = if (longDp > 0) longDp / 860f else 1f
        return minOf(1f, byWidth, byHeight).coerceAtLeast(0.8f)
    }

    /**
     * The same clean sizes on every phone, automatically: a "Display size" above the phone's normal one is
     * brought back to normal, small screens are drawn a little smaller (see [sizeFactor]), and very large
     * "Font size" settings are capped (more on small screens, where big text would be cut).
     * Only inside this app. Used by every screen in attachBaseContext.
     */
    fun saneScale(base: android.content.Context): android.content.res.Configuration? {
        val cur = base.resources.configuration
        val over = android.content.res.Configuration()
        var change = false
        val stable = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE
        val normal = if (stable > 0) minOf(cur.densityDpi, stable) else cur.densityDpi
        val swDp = if (normal > 0) cur.smallestScreenWidthDp * cur.densityDpi / normal else cur.smallestScreenWidthDp
        val longDp = if (normal > 0) maxOf(cur.screenWidthDp, cur.screenHeightDp) * cur.densityDpi / normal else 0
        val maxFont = if (swDp in 1 until 380) 1.0f else 1.1f
        if (cur.fontScale > maxFont) { over.fontScale = maxFont; change = true }
        val target = (normal * sizeFactor(swDp, longDp)).toInt().coerceAtLeast(120)
        if (cur.densityDpi > 0 && target != cur.densityDpi) {
            over.densityDpi = target
            // the screen measured in the new size (layouts read these)
            over.screenWidthDp = cur.screenWidthDp * cur.densityDpi / target
            over.screenHeightDp = cur.screenHeightDp * cur.densityDpi / target
            over.smallestScreenWidthDp = cur.smallestScreenWidthDp * cur.densityDpi / target
            change = true
        }
        return if (change) over else null
    }

    /** YouTube-style Subscribe button: solid when not subscribed, grey "Subscribed" when subscribed. */
    fun subscribeButton(b: MaterialButton, subscribed: Boolean) {
        val ctx = b.context
        if (subscribed) {
            b.text = "Subscribed"
            b.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(b, R.attr.rxSurfaceAlt))
            b.setTextColor(ContextCompat.getColor(ctx, R.color.rx_text))
        } else {
            b.text = "Subscribe"
            b.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rx_text))
            b.setTextColor(MaterialColors.getColor(b, R.attr.rxBg))
        }
    }

    /** The accent colour chosen in Settings (0 film red, 1 gold, 2 teal, 3 purple), as a colour resource. */
    fun accentRes(c: android.content.Context): Int = when (AppPrefs.accent(c)) {
        1 -> R.color.rx_accent_gold
        2 -> R.color.rx_accent_teal
        3 -> R.color.rx_accent_purple
        else -> R.color.rx_primary
    }

    /** The accent colour itself (right for light and dark; works with any context, services too). */
    fun accent(c: android.content.Context): Int = ContextCompat.getColor(c, accentRes(c))

    /** The accent for small text (film red is a touch lighter in the dark theme so it stays readable). */
    fun accentText(c: android.content.Context): Int =
        ContextCompat.getColor(c, if (AppPrefs.accent(c) == 0) R.color.rx_primary_text else accentRes(c))

    /** Every screen: buttons, bars, chips and switches take the chosen accent (?attr/rxAccent). */
    fun applyAccent(a: android.app.Activity) {
        val style = when (AppPrefs.accent(a)) {
            1 -> R.style.ThemeOverlay_Rainax_Accent_Gold
            2 -> R.style.ThemeOverlay_Rainax_Accent_Teal
            3 -> R.style.ThemeOverlay_Rainax_Accent_Purple
            else -> return
        }
        a.theme.applyStyle(style, true)
    }

    /** Like / dislike / save pill: red icon when active. */
    fun toggleButton(b: MaterialButton, active: Boolean) {
        val color = if (active) Ui.accent(b.context) else androidx.core.content.ContextCompat.getColor(b.context, R.color.rx_text)
        b.iconTint = ColorStateList.valueOf(color)
    }
}
