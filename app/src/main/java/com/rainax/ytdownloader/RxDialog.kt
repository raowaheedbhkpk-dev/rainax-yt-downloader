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
 * Every confirmation box in the app: the title in bold Plus Jakarta Sans (v19.9: the Cinema bar and label
 * above it are gone). Buttons, corners, the dim and the blur come from ThemeOverlay.Rainax.Dialog.
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
            box.addView(TextView(ctx).apply {
                text = title
                setTextColor(ContextCompat.getColor(ctx, R.color.rx_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.rx_modern_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
                isAllCaps = false
                setLineSpacing(0f, 1.1f)
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(-1, -2))
            return box
        }
    }
}
