package com.rainax.ytdownloader

import android.content.Context

/**
 * Keeps Home's first videos from looking the same every time: videos shown at the top of earlier feeds (also
 * before the app was closed) move behind ones you haven't seen, and the new ones come in a new order each time.
 */
object FeedFreshen {

    private const val PREFS = "rx_feed_seen"
    private const val KEY = "urls"
    private const val KEEP = 400          // how many shown videos are remembered
    private const val TOP = 24            // how many of each feed count as "shown"

    fun apply(c: Context, items: List<VideoItem>): List<VideoItem> {
        if (items.size < 6) return items
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = LinkedHashSet(prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() })
        val (fresh, before) = items.partition { it.url !in seen }
        // new videos first, mixed; the ones seen before after them (oldest seen first)
        val order = seen.withIndex().associate { (i, u) -> u to i }
        val result = fresh.shuffled() + before.sortedBy { order[it.url] ?: 0 }
        result.take(TOP).forEach { seen.remove(it.url); seen.add(it.url) }
        while (seen.size > KEEP) seen.remove(seen.first())
        prefs.edit().putString(KEY, seen.joinToString("\n")).apply()
        return result
    }
}
