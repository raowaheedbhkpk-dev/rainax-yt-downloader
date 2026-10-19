package com.rainax.ytdownloader

import android.content.Context

/**
 * "Not interested" and "Don't recommend channel" kept on the phone (YouTube is told too when signed in), so those
 * videos and channels stay out of Home and Up next, like on YouTube.
 */
object HiddenFeed {
    private const val PREFS = "hidden_feed"
    private const val MAX = 600
    @Volatile private var videos: Set<String> = emptySet()
    @Volatile private var channels: Set<String> = emptySet()
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        val p = prefs(c)
        videos = p.getStringSet("videos", emptySet()).orEmpty().toSet()
        channels = p.getStringSet("channels", emptySet()).orEmpty().toSet()
        loaded = true
    }

    private fun idOf(url: String) = Regex("(?:[?&]v=|shorts/|youtu\\.be/)([\\w-]{6,})").find(url)?.groupValues?.get(1) ?: url

    /** False for a video you said "Not interested" to, or from a channel you don't want recommended. */
    fun allowed(item: VideoItem): Boolean {
        if (videos.isEmpty() && channels.isEmpty()) return true
        if (idOf(item.url) in videos) return false
        return item.uploader.isBlank() || item.uploader.lowercase() !in channels
    }

    fun hideVideo(c: Context, item: VideoItem) {
        load(c)
        videos = (videos + idOf(item.url)).toList().takeLast(MAX).toSet()
        prefs(c).edit().putStringSet("videos", videos).apply()
    }

    fun hideChannel(c: Context, item: VideoItem) {
        if (item.uploader.isBlank()) return
        load(c)
        channels = (channels + item.uploader.lowercase()).toList().takeLast(MAX).toSet()
        prefs(c).edit().putStringSet("channels", channels).apply()
    }

    /** Settings: bring everything back. */
    fun clear(c: Context) {
        videos = emptySet()
        channels = emptySet()
        prefs(c).edit().clear().apply()
    }

    fun count() = videos.size + channels.size
}
