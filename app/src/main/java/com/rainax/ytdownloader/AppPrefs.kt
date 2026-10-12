package com.rainax.ytdownloader

import android.content.Context

object AppPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun wifiOnly(c: Context) = p(c).getBoolean("wifi_only", false)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi_only", v).apply()

    fun maxParallel(c: Context) = p(c).getInt("max_parallel", 3)
    fun setMaxParallel(c: Context, v: Int) = p(c).edit().putInt("max_parallel", v).apply()


    /** Autoplay in feed: 2 = always, 1 = on Wi-Fi only, 0 = off. */
    fun feedAutoplay(c: Context) = p(c).getInt("feed_autoplay", 2)
    fun setFeedAutoplay(c: Context, v: Int) = p(c).edit().putInt("feed_autoplay", v).apply()

    /**
     * A random key made on first use and kept only in the app's private settings: RAINAX adds it to the
     * player screens it opens, so other apps can't make the player open the app's own files.
     */
    @Synchronized
    fun innerKey(c: Context): String {
        p(c).getString("inner_key", null)?.let { return it }
        val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        val key = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        p(c).edit().putString("inner_key", key).commit()
        return key
    }

    /** WhatsApp status folder RAINAX may read, per app (tree address, folder id), or null. */
    fun statusFolder(c: Context, pkg: String): Pair<String, String>? {
        val tree = p(c).getString("status_tree_$pkg", null) ?: return null
        val doc = p(c).getString("status_doc_$pkg", null) ?: return null
        return tree to doc
    }
    fun setStatusFolder(c: Context, pkg: String, tree: String?, doc: String?) =
        p(c).edit().putString("status_tree_$pkg", tree).putString("status_doc_$pkg", doc).apply()

    /** Names of statuses already saved (newest last, at most 1000). */
    fun savedStatuses(c: Context): Set<String> =
        p(c).getString("status_saved", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
    fun addSavedStatus(c: Context, name: String) {
        val list = p(c).getString("status_saved", "").orEmpty().split('\n').filter { it.isNotBlank() && it != name } + name
        p(c).edit().putString("status_saved", list.takeLast(1000).joinToString("\n")).apply()
    }

    fun autoClear(c: Context) = p(c).getBoolean("auto_clear", false)
    fun setAutoClear(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_clear", v).apply()

    /** Player picture quality: 0 = Auto (720p on Wi-Fi, 480p on mobile data), else the height (1080 = 1080p). */
    fun playerQuality(c: Context) = p(c).getInt("player_quality", 0)
    fun setPlayerQuality(c: Context, v: Int) = p(c).edit().putInt("player_quality", v).apply()

    /** Recent searches, newest first (max 20). */
    fun searchHistory(c: Context): List<String> =
        p(c).getString("search_history", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun clearSearchHistory(c: Context) = p(c).edit().remove("search_history").apply()
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

    /** Offer to download video links copied in other apps (on by default). */
    fun clipDetect(c: Context) = p(c).getBoolean("clip_detect", true)
    fun setClipDetect(c: Context, v: Boolean) = p(c).edit().putBoolean("clip_detect", v).apply()

    /** The last copied link already offered (not asked again). */
    fun clipSeen(c: Context) = p(c).getString("clip_seen", "").orEmpty()
    fun setClipSeen(c: Context, v: String) = p(c).edit().putString("clip_seen", v).apply()

    /** The first-run questions (storage, app updates) were shown. */
    fun firstRunAsked(c: Context) = p(c).getBoolean("first_run_asked", false)
    fun setFirstRunAsked(c: Context) = p(c).edit().putBoolean("first_run_asked", true).apply()
}
