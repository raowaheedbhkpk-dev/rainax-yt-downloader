package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Song covers: bigger sizes, square crops and the colour the player is painted with. */
object MusicArt {

    /** YouTube Music covers (googleusercontent) come in any size: ask for [px] x [px]. Other links stay. */
    fun sized(url: String?, px: Int): String? {
        val u = url?.takeIf { it.isNotBlank() } ?: return null
        if (!u.contains("googleusercontent.com") && !u.contains("ggpht.com")) return u
        return when {
            Regex("=w\\d+-h\\d+").containsMatchIn(u) -> u.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px")
            Regex("=s\\d+").containsMatchIn(u) -> u.replace(Regex("=s\\d+"), "=s$px")
            else -> u
        }
    }

    /** The cover for the big player: a large YouTube Music cover, else the video's best pictures (largest first). */
    fun big(page: String?, thumb: String?): List<String> {
        val out = mutableListOf<String>()
        val t = thumb?.takeIf { it.isNotBlank() }
        if (t != null && (t.contains("googleusercontent.com") || t.contains("ggpht.com"))) sized(t, 720)?.let { out += it }
        youtubeId(page)?.let { id ->
            out += "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
            out += "https://i.ytimg.com/vi/$id/sddefault.jpg"
            out += "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        }
        if (t != null && t !in out) out += t
        return out.distinct()
    }

    /** Small cover for lists (square, YouTube Music size when there is one). */
    fun small(page: String?, thumb: String?): String? =
        sized(thumb, 226) ?: youtubeId(page)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

    /** The middle square of a picture; YouTube's 4:3 thumbnails lose their black bars first. */
    fun square(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        var top = 0
        var bottom = h
        if (kotlin.math.abs(h * 4 - w * 3) <= 4) {          // 4:3 with a 16:9 picture inside
            val inner = w * 9 / 16
            top = (h - inner) / 2
            bottom = top + inner
        }
        val side = minOf(w, bottom - top)
        if (side == w && side == h) return b
        val x = (w - side) / 2
        val y = top + (bottom - top - side) / 2
        return runCatching { Bitmap.createBitmap(b, x, y, side, side) }.getOrDefault(b)
    }

    /**
     * The cover's main colour, made deep enough for white text on it (the player's background, like
     * YouTube Music). Grey covers give a soft dark grey.
     */
    fun tone(b: Bitmap): Int {
        val small = runCatching { Bitmap.createScaledBitmap(b, 24, 24, true) }.getOrNull() ?: return DEFAULT_TONE
        val weight = FloatArray(36)
        val red = FloatArray(36)
        val green = FloatArray(36)
        val blue = FloatArray(36)
        val hsv = FloatArray(3)
        var total = 0f
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.12f) continue                     // near black: no colour to give
            val w = hsv[1] * hsv[2] + 0.02f
            val bin = (hsv[0] / 10f).toInt().coerceIn(0, 35)
            weight[bin] += w
            red[bin] += Color.red(c) * w
            green[bin] += Color.green(c) * w
            blue[bin] += Color.blue(c) * w
            total += w
        }
        if (small !== b) small.recycle()
        if (total <= 0f) return DEFAULT_TONE
        // the strongest colour, together with its neighbours on the colour wheel
        val best = (0 until 36).maxByOrNull { weight[it] + 0.5f * (weight[(it + 35) % 36] + weight[(it + 1) % 36]) } ?: 0
        var w = 0f; var r = 0f; var g = 0f; var bl = 0f
        for (k in listOf((best + 35) % 36, best, (best + 1) % 36)) {
            w += weight[k]; r += red[k]; g += green[k]; bl += blue[k]
        }
        if (w <= 0f) return DEFAULT_TONE
        Color.colorToHSV(Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt()), hsv)
        if (hsv[1] < 0.12f || w / total < 0.08f) {
            hsv[1] = 0f
            hsv[2] = 0.20f
        } else {
            hsv[1] = hsv[1].coerceIn(0.35f, 0.78f)
            hsv[2] = 0.30f
        }
        return Color.HSVToColor(hsv)
    }

    /** [c] mixed with black ([keep] = how much of the colour stays). */
    fun darker(c: Int, keep: Float): Int =
        Color.rgb((Color.red(c) * keep).toInt(), (Color.green(c) * keep).toInt(), (Color.blue(c) * keep).toInt())

    const val DEFAULT_TONE = 0xFF2A2A30.toInt()
}

/**
 * Songs played in the Music player, newest first (Listen again, and Quick picks are made from the last one).
 * Kept on the phone only (filesDir/music_recent.json).
 */
object MusicHistory {

    private const val MAX = 40
    private val songs = ArrayList<VideoItem>()
    private var loaded = false
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Volatile var version = 0
        private set

    private fun file(c: Context) = File(c.applicationContext.filesDir, "music_recent.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(file(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                songs += VideoItem(
                    o.getString("u"), o.optString("t"), o.optString("a"), o.optString("th").ifBlank { null },
                    o.optLong("s"), -1, null
                )
            }
        }
    }

    /** Remembers that [song] was played (moves it to the front). */
    @Synchronized
    fun add(c: Context, song: VideoItem) {
        val id = youtubeId(song.url) ?: return
        load(c)
        songs.removeAll { youtubeId(it.url) == id }
        songs.add(0, song)
        while (songs.size > MAX) songs.removeAt(songs.size - 1)
        version++
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONObject().put("u", s.url).put("t", s.title).put("a", s.uploader).put("th", s.thumb ?: "").put("s", s.seconds))
        }
        val text = arr.toString()
        val f = file(c)
        writer.execute {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    /** Played songs, newest first. */
    @Synchronized
    fun recent(c: Context): List<VideoItem> {
        load(c)
        return songs.toList()
    }
}

/**
 * The Music player's queue, run next to the player itself (BgPlayService), so it keeps working with the app
 * closed: similar songs are added when the list runs out (like YouTube Music's radio), and the sleep timer.
 * Everything here runs on the main thread.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MusicQueue {

    /** The service's player (set by BgPlayService while it runs). */
    var player: androidx.media3.common.Player? = null

    /** Sleep timer: pause at this time (0 = off). */
    @Volatile var sleepAt = 0L
    /** Sleep timer: pause when the song playing now ends. */
    @Volatile var sleepEndOfSong = false

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var running: kotlinx.coroutines.Job? = null
    private var radioFor: String? = null
    private var advance = false

    /** A song as a player entry: its sound is looked up when it is reached. */
    fun songItem(s: VideoItem): androidx.media3.common.MediaItem {
        val lazy = BgPlayService.lazyUri(s.url)
        val art = MusicArt.sized(s.thumb, 544)?.takeIf { it.contains("googleusercontent.com") || it.contains("ggpht.com") }
            ?: youtubeId(s.url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            ?: s.thumb
        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(s.url)
            .setUri(lazy)
            .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setMediaUri(lazy).build())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(s.title)
                    .setArtist(s.uploader)
                    .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                    .setExtras(android.os.Bundle().apply {
                        putBoolean(MusicPlayer.EXTRA_MUSIC, true)
                        putString(MusicPlayer.EXTRA_THUMB, s.thumb)
                        putLong(MusicPlayer.EXTRA_SECONDS, s.seconds)
                    })
                    .build()
            )
            .build()
    }

    /** The song a player entry holds. */
    fun songOf(item: androidx.media3.common.MediaItem?): VideoItem? {
        item ?: return null
        val md = item.mediaMetadata
        return VideoItem(
            item.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
            md.extras?.getString(MusicPlayer.EXTRA_THUMB), md.extras?.getLong(MusicPlayer.EXTRA_SECONDS) ?: 0, -1, null
        )
    }

    /** A new list started: forget the radio of the old one. */
    fun reset() {
        running?.cancel()
        radioFor = null
        advance = false
    }

    /** Called by the player on every new song: near the end of a Music list, similar songs are added. */
    fun topUpIfNeeded(p: androidx.media3.common.Player) {
        val item = p.currentMediaItem
        if (!MusicPlayer.isMusic(item) || p.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) return
        val left = p.mediaItemCount - 1 - p.currentMediaItemIndex
        if (p.hasNextMediaItem() && (p.shuffleModeEnabled || left > 1)) return
        songOf(item)?.let { topUp(it, advanceAfter = false) }
    }

    /**
     * Adds songs like [seed] at the end of the list ([advanceAfter]: then plays the next one).
     * [onEmpty] is called when nothing new was found.
     */
    fun topUp(seed: VideoItem, advanceAfter: Boolean, onEmpty: () -> Unit = {}) {
        val p = player ?: return
        val id = youtubeId(seed.url) ?: return
        if (advanceAfter) advance = true
        if (radioFor == id && running?.isActive == true) return
        running?.cancel()
        radioFor = id
        running = scope.launch {
            val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { YtCatalog.musicRadio(seed.url) }.getOrDefault(emptyList())
            }
            val wantNext = advance
            advance = false
            if (player !== p || !MusicPlayer.isMusic(p.currentMediaItem)) return@launch
            val have = HashSet<String>()
            for (i in 0 until p.mediaItemCount) youtubeId(p.getMediaItemAt(i).mediaId)?.let { have += it }
            val add = songs.filter { s -> youtubeId(s.url)?.let { have.add(it) } == true }
            if (add.isEmpty()) {
                if (wantNext) onEmpty()
                return@launch
            }
            p.addMediaItems(add.map { songItem(it) })
            if (wantNext && p.hasNextMediaItem()) p.seekToNextMediaItem()
        }
    }

}
