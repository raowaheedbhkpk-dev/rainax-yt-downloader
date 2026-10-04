package com.rainax.ytdownloader

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/** Small shared look helpers. */
object Ui {

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
