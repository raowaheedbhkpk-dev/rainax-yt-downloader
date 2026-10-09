package com.rainax.ytdownloader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Applies the saved theme before any screen is created, so the first frame already has the right colours. */
class RainaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // more open connections kept for reuse (6 per download, plus the extractor and pictures)
        System.setProperty("http.maxConnections", "16")
        applyTheme(AppPrefs.themeMode(this))
    }

    companion object {
        /** 0 = follow the phone, 1 = light, 2 = dark, 3 = AMOLED black (dark + pure black) */
        fun applyTheme(mode: Int) {
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
