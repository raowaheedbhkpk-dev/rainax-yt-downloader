package com.rainax.ytdownloader

import android.content.Context

object AppPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi_only", v).apply()

    fun maxParallel(c: Context) = p(c).getInt("max_parallel", 3)
    fun setMaxParallel(c: Context, v: Int) = p(c).edit().putInt("max_parallel", v).apply()

    fun clipDetect(c: Context) = p(c).getBoolean("clip_detect", true)
    fun setClipDetect(c: Context, v: Boolean) = p(c).edit().putBoolean("clip_detect", v).apply()

    fun autoClear(c: Context) = p(c).getBoolean("auto_clear", false)
    fun setAutoClear(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_clear", v).apply()

    fun adBlock(c: Context) = p(c).getBoolean("ad_block", true)
    fun setAdBlock(c: Context, v: Boolean) = p(c).edit().putBoolean("ad_block", v).apply()

    /** 0 = follow the phone, 1 = light, 2 = dark, 3 = AMOLED black */
    fun themeMode(c: Context) = p(c).getInt("theme_mode", 0)
    fun setThemeMode(c: Context, v: Int) = p(c).edit().putInt("theme_mode", v).apply()

    /** Folder chosen by the user (a document-tree Uri), or empty for the default Downloads/rainax-yt-downloader. */
    fun saveTree(c: Context) = p(c).getString("save_tree", "").orEmpty()
    fun setSaveTree(c: Context, v: String) = p(c).edit().putString("save_tree", v).apply()

    fun lastUpdate(c: Context) = p(c).getLong("last_update", 0L)
    fun setLastUpdate(c: Context, v: Long) = p(c).edit().putLong("last_update", v).apply()

    fun autoRetry(c: Context) = p(c).getBoolean("auto_retry", true)
    fun setAutoRetry(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_retry", v).apply()

    fun lastClip(c: Context) = p(c).getString("last_clip", "").orEmpty()
    fun setLastClip(c: Context, v: String) = p(c).edit().putString("last_clip", v).apply()

    fun recents(c: Context): List<String> =
        p(c).getString("recents", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addRecent(c: Context, q: String) {
        val list = (listOf(q.trim()) + recents(c).filter { !it.equals(q.trim(), true) }).take(8)
        p(c).edit().putString("recents", list.joinToString("\n")).apply()
    }

    fun clearRecents(c: Context) = p(c).edit().putString("recents", "").apply()
}
