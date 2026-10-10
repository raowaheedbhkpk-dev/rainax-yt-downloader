package com.rainax.ytdownloader

/**
 * Videos picked with + (Home, search, channels, the video page's Up next and Mix list), to download them
 * together. One list for the whole app, so picks made on the video page and on Home add up.
 */
object Picks {
    private val map = LinkedHashMap<String, VideoItem>()
    private val listeners = mutableListOf<() -> Unit>()

    val size get() = map.size

    fun has(url: String) = map.containsKey(url)

    fun all(): List<VideoItem> = map.values.toList()

    fun toggle(item: VideoItem) {
        if (map.remove(item.url) == null) map[item.url] = item
        changed()
    }

    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        changed()
    }

    /** A new main screen is being built (e.g. theme change): forget the old screen's listeners. */
    fun clearListeners() = listeners.clear()

    /** [l] runs on every change (the bars and + buttons update). */
    fun listen(l: () -> Unit) {
        listeners += l
    }

    private fun changed() = listeners.toList().forEach { it() }
}
