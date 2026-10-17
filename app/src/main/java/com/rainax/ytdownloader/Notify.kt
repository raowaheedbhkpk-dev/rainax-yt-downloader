package com.rainax.ytdownloader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

/**
 * Short messages ("Added to downloads", "Search history cleared"...): a RAINAX message bar (rounded, above the
 * bottom bar, optional action like "View") on screen; Android's small toast when there is no screen (background).
 */
object Notify {

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

    fun show(context: Context, text: CharSequence, long: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
        val act = context.activity()
        val root = act?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById<View>(android.R.id.content)
        if (root == null || !root.isAttachedToWindow) {
            Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        val bar = Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        // above the bottom bar (and the mini player) when they are on screen
        act.findViewById<View>(R.id.navCard)?.takeIf { it.isShown }?.let { bar.anchorView = it }
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.show()
    }
}
