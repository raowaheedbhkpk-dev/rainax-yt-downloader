package com.rainax.ytdownloader

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Keeps every screen and the home-screen icon in step with Settings:
 *
 *  - Each open screen remembers the accent and theme it was drawn with. If you change them in Settings,
 *    any other screen still open (player, Shorts, Private folder...) redraws itself in the new colours as
 *    soon as you go back to it.
 *  - The app icon on the home screen takes the accent colour too (one launcher entry per accent; only the
 *    chosen one is on). The switch happens when you leave the app, because some phones close an app whose
 *    launcher entry changes while it is open.
 */
object StyleSync : Application.ActivityLifecycleCallbacks {

    private val drawnWith = WeakHashMap<Activity, String>()
    private var started = 0

    private val ALIASES = listOf("LauncherRed", "LauncherGold", "LauncherTeal", "LauncherPurple")   // = accent 0..3

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        // (never switched here: the app may have just been opened from the old icon)
    }

    private fun signature(c: Context) = "${AppPrefs.accent(c)}/${AppPrefs.themeMode(c)}"

    /** Turns on the launcher entry of the current accent and turns the others off (only what differs). */
    fun applyIcon(c: Context) {
        val pm = c.packageManager
        val want = AppPrefs.accent(c).coerceIn(0, ALIASES.size - 1)
        ALIASES.forEachIndexed { i, name ->
            val cn = ComponentName(c.packageName, "${c.packageName}.$name")
            val target = if (i == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val now = runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull() ?: return@forEachIndexed
            // "default" means what the manifest says: Red on, the others off
            val effective = if (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                if (i == 0) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else now
            if (effective != target) runCatching { pm.setComponentEnabledSetting(cn, target, PackageManager.DONT_KILL_APP) }
        }
    }

    override fun onActivityCreated(a: Activity, saved: Bundle?) { drawnWith[a] = signature(a) }

    override fun onActivityResumed(a: Activity) {
        val was = drawnWith[a] ?: return
        if (was != signature(a)) {
            drawnWith[a] = signature(a)
            a.recreate()
        }
    }

    override fun onActivityStarted(a: Activity) { started++ }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            started = 0
            applyIcon(a.applicationContext)          // the app went to the background: safe to switch the icon
        }
    }

    override fun onActivityDestroyed(a: Activity) { drawnWith.remove(a) }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
}
