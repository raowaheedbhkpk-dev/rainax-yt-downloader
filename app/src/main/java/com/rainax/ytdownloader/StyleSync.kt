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


    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        // (never switched here: the app may have just been opened from the old icon)
    }

    /**
     * What a screen was drawn with: accent, theme, and (for "follow the phone") whether the phone was dark.
     * Screens that handle dark-mode changes themselves (Private folder, sign-in, welcome) missed the phone
     * switching to dark while they were open and stayed in the old colours; now they redraw when you return.
     * The player and Shorts are left alone (always dark, and a redraw would stop the video).
     */
    private fun signature(a: Activity): String {
        val night = if (a is PlayerActivity || a is ShortsActivity || AppPrefs.themeMode(a) != 0) ""
            else "/" + (a.applicationContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        return "${AppPrefs.accent(a)}/${AppPrefs.themeMode(a)}$night"
    }

    /**
     * True while RAINAX downloads or plays in the background (a foreground service is on). Some phones close
     * the whole app when its launcher icon changes, which would cut the download or the music: wait then.
     */
    private fun busy(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningServices(20).any { it.service.packageName == c.packageName && it.foreground }
    }.getOrDefault(true)

    /** Turns on the launcher entry of the current accent and turns the others off (only what differs). */
    fun applyIcon(c: Context) {
        val pm = c.packageManager
        val want = Accents.of(AppPrefs.accent(c)).alias
        Accents.ALL.forEach { acc ->
            val name = acc.alias
            val i = acc.id
            val cn = ComponentName(c.packageName, "${c.packageName}.$name")
            val target = if (name == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val now = runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull() ?: return@forEach
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

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var iconJob: Runnable? = null

    override fun onActivityStarted(a: Activity) {
        started++
        iconJob?.let { main.removeCallbacks(it) }       // back in the app before the switch: not now
        iconJob = null
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            started = 0
            // The icon switches a minute after RAINAX went to the background, not at once: some phones close an
            // app whose launcher entry changes, and switching right away made a quick trip out (to copy a link,
            // read a message) bring you back to a freshly started app, as if everything had been reset.
            val app = a.applicationContext
            iconJob?.let { main.removeCallbacks(it) }
            val job = object : Runnable {
                override fun run() {
                    if (started > 0) { iconJob = null; return }
                    if (busy(app)) { main.postDelayed(this, 60_000); return }    // downloading / playing: later
                    iconJob = null
                    applyIcon(app)
                }
            }
            iconJob = job
            main.postDelayed(job, 60_000)
        }
    }

    override fun onActivityDestroyed(a: Activity) { drawnWith.remove(a) }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
}
