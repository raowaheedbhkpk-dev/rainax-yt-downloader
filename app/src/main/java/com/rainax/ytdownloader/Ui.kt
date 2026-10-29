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
     * True when the screen is really drawn dark. Read from the theme's own background colour, not the phone's
     * night mode: with Settings › Theme set to Light on a phone in dark mode (or the other way round) the two
     * differ, and screens that asked the phone drew themselves in the wrong theme.
     */
    fun isDark(c: android.content.Context): Boolean {
        val tv = android.util.TypedValue()
        if (!c.theme.resolveAttribute(R.attr.rxBg, tv, true)) {
            return (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val color = if (tv.resourceId != 0) ContextCompat.getColor(c, tv.resourceId) else tv.data
        return androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.5
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

    /**
     * Switches, chips and text boxes under [v] are filled by the app, never by Android's "put the screen back"
     * after a theme or colour change. That replay matched views by number; chips made in code get new numbers
     * every time, so an old choice could land on a different chip (Home jumped to another feed, a switch
     * flipped) and its listener saved or loaded the wrong thing.
     */
    fun noRestore(v: android.view.View) {
        if (v is android.widget.CompoundButton || v is com.google.android.material.chip.ChipGroup || v is android.widget.EditText) v.isSaveEnabled = false
        if (v is android.view.ViewGroup && v !is androidx.recyclerview.widget.RecyclerView) for (i in 0 until v.childCount) noRestore(v.getChildAt(i))
    }

    /** The accent colour chosen in Settings (one of the sixteen in [Accents]), as a colour resource. */
    fun accentRes(c: android.content.Context): Int = R.color.rx_primary      // v19.2: black and white only (no accent choice)

    /** The accent colour itself (right for light and dark; works with any context, services too). */
    fun accent(c: android.content.Context): Int = ContextCompat.getColor(c, accentRes(c))

    /** The accent for small text (black in light, white in dark). */
    fun accentText(c: android.content.Context): Int =
        ContextCompat.getColor(c, R.color.rx_primary_text)

}
