package com.rainax.ytdownloader

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Small, soft vibrations that make the app feel solid: a tick when a tab changes, a firmer click when a
 * download starts or a ticket tears. They follow the phone's own "touch vibration" setting, so they stay
 * off for anyone who turned that off.
 */
object Haptics {

    /** A light tick: tab or chip changed. */
    fun tick(v: View?) {
        v ?: return
        val k = if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
        v.performHapticFeedback(k)
    }

    /** A firmer click: something started (a download, a ticket torn off). */
    fun confirm(v: View?) {
        v ?: return
        val k = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        v.performHapticFeedback(k)
    }

    /** Hold: a long press opened a menu. */
    fun press(v: View?) {
        v ?: return
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
