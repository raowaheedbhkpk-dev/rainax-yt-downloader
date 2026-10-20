package com.rainax.ytdownloader

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Small shared look helpers. */
object Ui {

    /** Largest text size the app follows (bigger phone settings would push buttons and text off screen). */
    private const val MAX_FONT_SCALE = 1.15f

    /**
     * The same clean sizes on every phone: very large "Font size" settings are capped, and a "Display size"
     * set above the phone's normal one is brought back to normal (only inside this app).
     * Used by every screen in attachBaseContext.
     */
    fun saneScale(base: android.content.Context): android.content.res.Configuration? {
        val cur = base.resources.configuration
        val over = android.content.res.Configuration()
        var change = false
        if (cur.fontScale > MAX_FONT_SCALE) { over.fontScale = MAX_FONT_SCALE; change = true }
        val stable = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE
        if (stable > 0 && cur.densityDpi > stable) {
            over.densityDpi = stable
            // the screen measured in the normal size (layouts read these)
            over.screenWidthDp = cur.screenWidthDp * cur.densityDpi / stable
            over.screenHeightDp = cur.screenHeightDp * cur.densityDpi / stable
            over.smallestScreenWidthDp = cur.smallestScreenWidthDp * cur.densityDpi / stable
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
