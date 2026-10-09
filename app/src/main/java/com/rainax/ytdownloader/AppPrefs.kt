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

    /** Player picture quality: 0 = Auto (720p on Wi-Fi, 480p on mobile data), else the height (1080 = 1080p). */
    fun playerQuality(c: Context) = p(c).getInt("player_quality", 0)
    fun setPlayerQuality(c: Context, v: Int) = p(c).edit().putInt("player_quality", v).apply()

    /** Recent searches, newest first (max 20). */
    fun searchHistory(c: Context): List<String> =
        p(c).getString("search_history", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun addSearch(c: Context, q: String) {
        val list = (listOf(q.trim()) + searchHistory(c).filter { !it.equals(q.trim(), true) }).take(20)
        p(c).edit().putString("search_history", list.joinToString("\n")).apply()
    }

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

    /** SponsorBlock: skip sponsor, self-promotion and "subscribe" parts inside videos (on by default). */
    fun sponsorBlock(c: Context) = p(c).getBoolean("sponsor_block", true)
    fun setSponsorBlock(c: Context, v: Boolean) = p(c).edit().putBoolean("sponsor_block", v).apply()

    /** Videos continue where you stopped watching them (on by default). */
    fun resumeVideos(c: Context) = p(c).getBoolean("resume_videos", true)
    fun setResumeVideos(c: Context, v: Boolean) = p(c).edit().putBoolean("resume_videos", v).apply()
}
