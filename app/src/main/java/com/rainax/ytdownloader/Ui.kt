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

    /** Like / dislike / save pill: red icon when active. */
    fun toggleButton(b: MaterialButton, active: Boolean) {
        val color = ContextCompat.getColor(b.context, if (active) R.color.rx_primary else R.color.rx_text)
        b.iconTint = ColorStateList.valueOf(color)
    }
}
