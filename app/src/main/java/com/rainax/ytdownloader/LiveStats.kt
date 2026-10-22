package com.rainax.ytdownloader

import java.util.concurrent.ConcurrentHashMap

/**
 * The live numbers of each running download (speed, time left, how much has arrived), kept in memory only.
 * The download service fills them about twice a second; the Downloads list shows them in its stat strip.
 */
object LiveStats {

    class Stats(val bytesPerSec: Double, val etaSec: Long, val done: Long, val total: Long)

    private val map = ConcurrentHashMap<String, Stats>()

    fun put(id: String, s: Stats) { map[id] = s }
    fun get(id: String): Stats? = map[id]
    fun clear(id: String) { map.remove(id) }

    /** 1:12, or 1:02:10 for an hour or more; "—" when it can't be worked out yet. */
    fun eta(sec: Long): String = when {
        sec < 0 || sec > 86_400 -> "—"
        sec >= 3600 -> String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
        else -> String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    /** A number and its unit apart ("2.4" to "MB/s"), so the unit can be drawn smaller. */
    fun split(bytes: Double): Pair<String, String> = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f", bytes / 1073741824.0) to "GB"
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0) to "MB"
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f", bytes / 1024.0) to "KB"
        else -> String.format(java.util.Locale.US, "%.0f", bytes.coerceAtLeast(0.0)) to "B"
    }
}
