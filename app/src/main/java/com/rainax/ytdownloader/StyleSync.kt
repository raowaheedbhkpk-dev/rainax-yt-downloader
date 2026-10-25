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

    /** Times a screen was redrawn because its dark/light mode didn't match Settings (kept small: never loops). */
    private var modeFixes = 0

    /** Settings › Theme was just changed: the safety net may act again. */
    fun themeChanged() { modeFixes = 0 }

    /**
     * True when the screen's resources are in the wrong dark/light mode for Settings › Theme (Android can
     * reset them to the phone's mode, e.g. when a WebView is first loaded or updated).
     */
    private fun wrongMode(a: Activity): Boolean {
        if (a !is androidx.appcompat.app.AppCompatActivity || a is PlayerActivity || a is ShortsActivity) return false
        val want = when (AppPrefs.themeMode(a)) {
            1 -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            2, 3 -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return false
        }
        return (a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != want
    }

    override fun onActivityResumed(a: Activity) {
        resumed = java.lang.ref.WeakReference(a)
        if (wrongMode(a) && modeFixes < 5) {
            modeFixes++
            a.window.decorView.post { if (!a.isDestroyed && !a.isFinishing) a.recreate() }
            return
        }
        val was = drawnWith[a] ?: return
        if (was != signature(a)) {
            drawnWith[a] = signature(a)
            a.recreate()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private const val ICON_JOB = 7102

    /** True when the launcher entry that is on isn't the one for the chosen accent. */
    fun iconOutOfDate(c: Context): Boolean {
        val pm = c.packageManager
        val want = Accents.of(AppPrefs.accent(c)).alias
        return Accents.ALL.any { acc ->
            val cn = ComponentName(c.packageName, "${c.packageName}.${acc.alias}")
            val now = runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull() ?: return@any false
            val on = if (now == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) acc.id == 0
                else now == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            on != (acc.alias == want)
        }
    }

    /** Ask Android to switch the icon in about [delayMs] (runs even if the app is asleep or closed by then). */
    fun scheduleIcon(c: Context, delayMs: Long = 60_000) {
        runCatching {
            val js = c.getSystemService(android.app.job.JobScheduler::class.java) ?: return
            js.schedule(
                android.app.job.JobInfo.Builder(ICON_JOB, ComponentName(c, IconJob::class.java))
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs + 5 * 60_000)
                    .build()
            )
        }
    }

    private fun cancelIcon(c: Context) {
        runCatching { c.getSystemService(android.app.job.JobScheduler::class.java)?.cancel(ICON_JOB) }
    }

    /** Called by [IconJob]: switch now, unless the app is open again or busy (then try again later). */
    internal fun iconJobRun(c: Context) {
        if (started > 0) return                                   // back in the app: waits for the next exit
        if (!iconOutOfDate(c)) return
        if (busy(c)) { scheduleIcon(c); return }                 // downloading / playing: a minute later
        applyIcon(c)
    }

    override fun onActivityStarted(a: Activity) {
        started++
        cancelIcon(a.applicationContext)       // back in the app before the switch: not now
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0 && !a.isChangingConfigurations) {
            started = 0
            // The icon switches a minute after RAINAX went to the background, not at once: some phones close an
            // app whose launcher entry changes, and switching right away made a quick trip out (to copy a link,
            // read a message) bring you back to a freshly started app, as if everything had been reset.
            // (A JobScheduler job, not a timer in the app: Android freezes apps in the background within
            // seconds, so the old one-minute timer never ran and the icon never changed.)
            val app = a.applicationContext
            if (iconOutOfDate(app)) scheduleIcon(app)
        }
    }

    override fun onActivityDestroyed(a: Activity) { drawnWith.remove(a) }
    override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }

    private var resumed: java.lang.ref.WeakReference<Activity>? = null

    /** Call on the main thread right after a WebView was made: puts the open screen back in the chosen mode. */
    fun recheckMode() {
        val a = resumed?.get() ?: return
        if (wrongMode(a) && modeFixes < 5) {
            modeFixes++
            main.post { if (!a.isDestroyed && !a.isFinishing) a.recreate() }
        }
    }
    override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
}

/** Switches the home-screen icon to the chosen accent, a minute after RAINAX went to the background. */
class IconJob : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        runCatching { StyleSync.iconJobRun(applicationContext) }
        return false
    }

    override fun onStopJob(params: android.app.job.JobParameters) = false
}
