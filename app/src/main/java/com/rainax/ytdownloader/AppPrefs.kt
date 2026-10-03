package com.rainax.ytdownloader

import android.content.Context

object AppPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi_only", v).apply()

    fun maxParallel(c: Context) = p(c).getInt("max_parallel", 3)
    fun setMaxParallel(c: Context, v: Int) = p(c).edit().putInt("max_parallel", v).apply()


    fun autoClear(c: Context) = p(c).getBoolean("auto_clear", false)
    fun setAutoClear(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_clear", v).apply()

    fun adBlock(c: Context) = p(c).getBoolean("ad_block", true)
    fun setAdBlock(c: Context, v: Boolean) = p(c).edit().putBoolean("ad_block", v).apply()

    /** The signed-in YouTube user's photo (shown in the bottom bar). */
    fun avatarUrl(c: Context): String? = p(c).getString("avatar_url", null)
    fun setAvatarUrl(c: Context, v: String?) = p(c).edit().putString("avatar_url", v).apply()

    /** Leaving the app while a video plays keeps its sound playing (on by default). */
    fun backgroundPlay(c: Context) = p(c).getBoolean("bg_play", true)
    fun setBackgroundPlay(c: Context, v: Boolean) = p(c).edit().putBoolean("bg_play", v).apply()

    /** 0 = follow the phone, 1 = light, 2 = dark, 3 = AMOLED black */
    fun themeMode(c: Context) = p(c).getInt("theme_mode", 0)
    fun setThemeMode(c: Context, v: Int) = p(c).edit().putInt("theme_mode", v).apply()

    /** Folder chosen by the user (a document-tree Uri), or empty for the default Downloads/rainax-yt-downloader. */
    fun saveTree(c: Context) = p(c).getString("save_tree", "").orEmpty()
    fun setSaveTree(c: Context, v: String) = p(c).edit().putString("save_tree", v).apply()


    fun autoRetry(c: Context) = p(c).getBoolean("auto_retry", true)
    fun setAutoRetry(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_retry", v).apply()

    /** A release whose APK turned out not to be newer (wrongly tagged): never force it again. */
    fun badRelease(c: Context) = p(c).getString("bad_release", "").orEmpty()
    fun setBadRelease(c: Context, v: String) = p(c).edit().putString("bad_release", v).apply()

    fun lastClip(c: Context) = p(c).getString("last_clip", "").orEmpty()
    fun setLastClip(c: Context, v: String) = p(c).edit().putString("last_clip", v).apply()

}
