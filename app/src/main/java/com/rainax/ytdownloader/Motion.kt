package com.rainax.ytdownloader

import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * The app's motion, the same everywhere: quick (about a quarter of a second), easing to a gentle stop.
 * Android's "Remove animations" setting turns all of it off by itself.
 */
object Motion {

    /** Material "emphasized decelerate": starts fast, settles softly. */
    private val settle = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private const val QUICK = 240L

    private fun dp(v: View, value: Float) = value * v.resources.displayMetrics.density

    /** A page appears: slides in a little from the right (or left) and fades in. */
    fun slideIn(v: View, fromRight: Boolean) {
        v.animate().cancel()
        v.alpha = 0f
        v.translationX = dp(v, 28f) * if (fromRight) 1 else -1
        v.animate().alpha(1f).translationX(0f).setDuration(QUICK).setInterpolator(settle).start()
    }

    /** The video page opens: rises up into place (no fading: video surfaces don't fade well). */
    fun riseUp(v: View) {
        v.animate().cancel()
        v.translationY = dp(v, 56f)
        v.animate().translationY(0f).setDuration(280).setInterpolator(settle).start()
    }

    /** Something small appears (the mini player): grows from a bit smaller and fades in. */
    fun popIn(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.scaleX = 0.85f
        v.scaleY = 0.85f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(QUICK).setInterpolator(settle).start()
    }

    /** The search box opens: grows a touch and fades in. */
    fun focusIn(v: View) {
        v.animate().cancel()
        v.alpha = 0f
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(settle).start()
    }

    /** A tapped tab icon: a short springy bounce. */
    fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.86f
        v.scaleY = 0.86f
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }
}
