package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Every confirmation box in the app, in the "Cinema Pro" look: a short red bar, a small gold label
 * (CONFIRM for questions) and the title in big Oswald capitals, like a scene title in a film.
 * Buttons, corners and the dark dim come from ThemeOverlay.Rainax.Dialog.
 */
class RxDialogBuilder(
    context: Context,
    theme: Int = R.style.ThemeOverlay_Rainax_Dialog,
) : MaterialAlertDialogBuilder(context, theme) {

    override fun setTitle(title: CharSequence?): MaterialAlertDialogBuilder {
        if (title.isNullOrBlank()) return super.setTitle(title)
        return setCustomTitle(titleView(context, title))
    }

    override fun setTitle(titleId: Int): MaterialAlertDialogBuilder = setTitle(context.getText(titleId))

    companion object {
        fun titleView(ctx: Context, title: CharSequence): View {
            fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24f), dp(24f), dp(24f), dp(6f))
            }
            box.addView(View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.rx_primary))
            }, LinearLayout.LayoutParams(dp(40f), dp(3f)))
            box.addView(TextView(ctx).apply {
                text = if (title.trim().endsWith("?")) "CONFIRM" else "RAINAX TUBE"
                setTextColor(ContextCompat.getColor(ctx, R.color.rx_gold))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.25f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
            box.addView(TextView(ctx).apply {
                text = title
                setTextColor(ContextCompat.getColor(ctx, R.color.rx_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
                typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.rx_display) }.getOrNull() ?: Typeface.DEFAULT_BOLD
                isAllCaps = true
                letterSpacing = 0.01f
                setLineSpacing(0f, 1.05f)
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
            return box
        }
    }
}
