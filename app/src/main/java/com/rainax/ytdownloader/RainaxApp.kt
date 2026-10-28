package com.rainax.ytdownloader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the saved theme before any screen is created, so the first frame already has the right colours. */
class RainaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        YtAccount.load(this)
        if (AppPrefs.accent(this) != 0) AppPrefs.setAccent(this, 0)   // v19.2: black and white only; the app icon goes back to the normal one                                  // the YouTube sign-in, for age-restricted videos too
        // more open connections kept for reuse (6 per download, plus the extractor and pictures)
        System.setProperty("http.maxConnections", "16")
        // Load Android's WebView once now, before any screen exists. The first WebView in the app (the YouTube
        // code solver) adds its files to the app's resources, and Android then resets every open screen to the
        // PHONE's dark/light mode: with Theme = Light on a dark phone, new list rows came out with white text
        // on the light page. Done here, there is no open screen yet to spoil.
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }
        applyTheme(AppPrefs.themeMode(this))
        StyleSync.install(this)
        StatusAutoSave.ensureScheduled(this)    // WhatsApp status auto-save keeps its background check                 // open screens and the app icon follow Settings › Theme and Accent
        Net.init(this)
        // read the small saved lists early, so the first screens don't wait for the disk
        val app = this
        Thread { runCatching { WatchHistory.preload(app); LocalPlaylists.all(app); HiddenFeed.load(app) } }.start()
    }

    companion object {
        /** The app (for helpers that run without a screen, e.g. the YouTube player-code solver). */
        @Volatile var app: android.content.Context? = null
            private set

        /**
         * 0 = follow the phone, 1 = light, 2 = dark, 3 = AMOLED black (dark + pure black).
         *
         * Android 12 and newer: the choice is given to Android itself (per-app dark mode, like per-app language).
         * Android then hands RAINAX a configuration that is already light or dark, so nothing can flip a screen
         * back to the phone's mode any more. Before, the mode was only laid on top of each screen by AppCompat,
         * and Android sometimes reset it underneath (loading a WebView, turning the phone, a system update of
         * WebView): rows drawn after that came out in the other theme (white text on a light page, black cards).
         *
         * Android 10 / 11 have no per-app mode, so AppCompat still does it there (with the guards in StyleSync).
         */
        fun applyTheme(mode: Int) {
            val c = app
            if (android.os.Build.VERSION.SDK_INT >= 31 && c != null) {
                val ok = runCatching {
                    val um = c.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
                    um.setApplicationNightMode(
                        when (mode) {
                            1 -> android.app.UiModeManager.MODE_NIGHT_NO
                            2, 3 -> android.app.UiModeManager.MODE_NIGHT_YES
                            else -> android.app.UiModeManager.MODE_NIGHT_AUTO      // follow the phone
                        }
                    )
                }.isSuccess
                if (ok) {
                    // the configuration from Android is already right: AppCompat must not lay a second one on top
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    return
                }
            }
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2, 3 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
