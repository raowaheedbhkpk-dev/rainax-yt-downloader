package com.rainax.ytdownloader

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/** One video (or playlist) in a list: Home, search results, related videos. */
data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val thumb: String?,
    val seconds: Long,          // 0 = unknown, -1 = live
    val views: Long,            // -1 = unknown
    val uploaded: String?,
    val isPlaylist: Boolean = false,
    val count: Long = -1,       // playlist size
    val avatar: String? = null  // channel picture
)

class FeedPage(val items: List<VideoItem>, val next: Page?)

class Comment(val author: String, val avatar: String?, val text: String, val html: Boolean, val likes: String?, val date: String?)

/** One picture quality the player can switch to (sound is added separately). */
class VideoOption(val height: Int, val fps: Int, val url: String, val codec: String)

/**
 * What the in-app player streams: picture + sound (joined while playing), one file with both, or a live stream.
 * [options] = every picture quality (the app keeps those this phone can play).
 */
class PlaySource(val video: String?, val audio: String?, val muxed: String?, val hls: String?, val options: List<VideoOption> = emptyList())

class VideoDetails(
    val url: String,
    val title: String,
    val uploader: String,
    val avatar: String?,
    val subscribers: Long,
    val views: Long,
    val likes: Long,
    val uploaded: String?,
    val description: String,
    val thumb: String?,
    val seconds: Long,
    val related: List<VideoItem>,
    val play: PlaySource,
    val audioUrl: String?
)

/** YouTube lists for the app's own screens (no website): Home tabs, search, video page, comments. */
object YtCatalog {

    const val HOME = "home"
    const val MUSIC = "trending_music"

    /** Home tabs: title -> list id ("home" = popular videos and the user's interests, "trending_music" = music charts). */
    val TABS = listOf(
        "YouTube" to HOME,
        "Music" to MUSIC
    )

    /** Country the music charts work for (YouTube Charts skips some countries, e.g. Pakistan: then India, then US). */
    @Volatile private var musicCountry: String? = null

    private val yt get() = ServiceList.YouTube

    /** Blocking. One page of a Home tab. */
    fun kiosk(id: String, page: Page?, history: List<String> = emptyList()): FeedPage = guard {
        FastExtractor.init()
        when (id) {
            HOME -> home(history, page)
            MUSIC -> music(page)
            else -> kioskPage(id, page, null)
        }
    }

    private fun kioskPage(id: String, page: Page?, country: String?): FeedPage {
        val ex = yt.kioskList.getExtractorById(id, page)
        if (country != null) ex.forceContentCountry(org.schabi.newpipe.extractor.localization.ContentCountry(country))
        val p = if (page == null) { ex.fetchPage(); ex.initialPage } else ex.getPage(page)
        return FeedPage(p.items.mapNotNull { item(it) }, if (p.hasNextPage()) p.nextPage else null)
    }

    private fun deviceCountry(): String = java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

    /** Music charts for the phone's country, or the nearest supported one. */
    private fun music(page: Page?): FeedPage {
        musicCountry?.let { return kioskPage(MUSIC, page, it) }
        var last: Exception? = null
        for (c in listOf(deviceCountry(), "IN", "US").distinct()) {
            try {
                val res = kioskPage(MUSIC, page, c)
                musicCountry = c
                return res
            } catch (e: org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException) {
                last = e                               // not supported in this country: try the next one
            }
        }
        throw last ?: IllegalStateException("Music charts are not available")
    }

    /** "Trending videos <country>" search: real popular videos, more pages while scrolling. */
    private fun trendingQuery(): String {
        val name = java.util.Locale("", deviceCountry()).getDisplayCountry(java.util.Locale.ENGLISH)
        return ("trending videos " + name).trim()
    }

    private fun videoSearch(q: String, page: Page?): FeedPage {
        val handler = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        return if (page == null) {
            val info = SearchInfo.getInfo(yt, handler)
            FeedPage(info.relatedItems.mapNotNull { item(it) }, if (info.hasNextPage()) info.nextPage else null)
        } else {
            val p = SearchInfo.getMoreItems(yt, handler, page)
            FeedPage(p.items.mapNotNull { item(it) }, if (p.hasNextPage()) p.nextPage else null)
        }
    }

    /**
     * YouTube tab: popular videos in the user's country mixed with videos about their recent searches
     * (first page), then more popular videos while scrolling.
     */
    private fun home(history: List<String>, page: Page?): FeedPage {
        val trending = videoSearch(trendingQuery(), page)
        if (page != null) return trending
        val lists = mutableListOf(trending.items)
        for (q in history.take(2)) {
            runCatching { videoSearch(q, null).items.shuffled().take(8) }.getOrNull()?.let { lists += it }
        }
        val mixed = mutableListOf<VideoItem>()
        val seen = HashSet<String>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) if (i < l.size && seen.add(l[i].url)) mixed += l[i]
            i++
        }
        return FeedPage(mixed.filter { !it.isPlaylist }, trending.next)
    }

    /** Blocking. One page of search results ([playlists] = playlists only, else videos). */
    fun search(query: String, playlists: Boolean, page: Page?): FeedPage = guard {
        FastExtractor.init()
        val filter = if (playlists) YoutubeSearchQueryHandlerFactory.PLAYLISTS else YoutubeSearchQueryHandlerFactory.VIDEOS
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
        if (page == null) {
            val info = SearchInfo.getInfo(yt, handler)
            FeedPage(info.relatedItems.mapNotNull { item(it) }, if (info.hasNextPage()) info.nextPage else null)
        } else {
            val p = SearchInfo.getMoreItems(yt, handler, page)
            FeedPage(p.items.mapNotNull { item(it) }, if (p.hasNextPage()) p.nextPage else null)
        }
    }

    /** Blocking. Search suggestions while typing. */
    fun suggestions(query: String): List<String> = try {
        FastExtractor.init()
        yt.suggestionExtractor.suggestionList(query).take(10)
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. Everything the video page shows, plus what the player streams. */
    fun video(url: String): VideoDetails = guard {
        FastExtractor.init()
        val info = FastExtractor.streamInfo(url)
        val related = info.relatedItems.orEmpty().mapNotNull { item(it) }.filter { !it.isPlaylist }
        VideoDetails(
            url = FastExtractor.videoUrl(url),
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            avatar = best(info.uploaderAvatars, 88),
            subscribers = info.uploaderSubscriberCount,
            views = info.viewCount,
            likes = info.likeCount,
            uploaded = info.textualUploadDate,
            description = runCatching { info.description?.content() }.getOrNull().orEmpty(),
            thumb = best(info.thumbnails, 720),
            seconds = info.duration,
            related = related,
            play = playSource(info),
            audioUrl = bestAudio(info)
        )
    }

    /** Blocking. The first comments of a video (empty if turned off). */
    fun comments(url: String): List<Comment> = try {
        FastExtractor.init()
        val info = CommentsInfo.getInfo(yt, FastExtractor.videoUrl(url))
        info.relatedItems.take(20).map { c ->
            val text = c.commentText
            Comment(
                author = c.uploaderName.orEmpty(),
                avatar = best(c.uploaderAvatars, 48),
                text = text?.content().orEmpty(),
                html = text?.type() == org.schabi.newpipe.extractor.stream.Description.Type.HTML,
                likes = c.textualLikeCount?.takeIf { it.isNotBlank() && it != "0" },
                date = c.textualUploadDate
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- helpers ----------

    private fun item(i: InfoItem): VideoItem? = when (i) {
        is StreamInfoItem -> {
            if (i.url.isNullOrBlank()) null
            else {
                val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
                VideoItem(
                    url = i.url, title = i.name.orEmpty(), uploader = i.uploaderName.orEmpty(),
                    thumb = best(i.thumbnails, 480), seconds = if (live) -1 else i.duration.coerceAtLeast(0),
                    views = i.viewCount, uploaded = i.textualUploadDate,
                    avatar = runCatching { best(i.uploaderAvatars, 68) }.getOrNull()
                )
            }
        }
        is PlaylistInfoItem -> {
            if (i.url.isNullOrBlank()) null
            else VideoItem(
                url = i.url, title = i.name.orEmpty(), uploader = i.uploaderName.orEmpty(),
                thumb = best(i.thumbnails, 480), seconds = 0, views = -1, uploaded = null,
                isPlaylist = true, count = i.streamCount
            )
        }
        else -> null
    }

    /** The image closest to [height] px (not smaller when possible). */
    private fun best(list: List<Image>?, height: Int): String? {
        val l = list.orEmpty().filter { it.url.isNotBlank() }
        if (l.isEmpty()) return null
        return (l.filter { it.height >= height }.minByOrNull { it.height } ?: l.maxByOrNull { it.height })?.url
    }

    private fun <T : org.schabi.newpipe.extractor.stream.Stream> usable(list: List<T>?): List<T> =
        list.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }

    @Suppress("DEPRECATION")
    private fun heightOf(v: VideoStream): Int = v.height.takeIf { it > 0 } ?: v.resolution.substringBefore('p').toIntOrNull() ?: 0

    /** In-app playback: H.264 picture up to 720p + M4A sound (smooth on every phone), else one file, else live. */
    private fun playSource(info: StreamInfo): PlaySource {
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (live) return PlaySource(null, null, null, info.hlsUrl?.takeIf { it.isNotBlank() })
        val audio = bestAudio(info)
        val video = usable(info.videoOnlyStreams)
            .filter { it.format?.suffix == "mp4" && it.codec.orEmpty().lowercase().let { c -> c.isEmpty() || c.startsWith("avc") } }
            .filter { heightOf(it) in 1..720 }
            .maxWithOrNull(compareBy<VideoStream> { heightOf(it) }.thenBy { it.bitrate })
        val muxed = usable(info.videoStreams).maxByOrNull { heightOf(it) }?.content
        // every quality: per height the best stream of each codec (H.264, VP9, AV1)
        val options = usable(info.videoOnlyStreams)
            .filter { heightOf(it) > 0 }
            .groupBy { heightOf(it) to codecFamily(it) }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.bitrate } }
            .map { VideoOption(heightOf(it), it.fps, it.content, codecFamily(it)) }
            .sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps })
        return if (video != null && audio != null) PlaySource(video.content, audio, muxed, null, options)
        else PlaySource(null, audio, muxed ?: audio, info.hlsUrl?.takeIf { it.isNotBlank() }, if (audio != null) options else emptyList())
    }

    /** "avc", "vp9", "av1" or "other". */
    private fun codecFamily(v: VideoStream): String {
        val c = v.codec.orEmpty().lowercase()
        return when {
            c.startsWith("avc") || (c.isEmpty() && v.format?.suffix == "mp4") -> "avc"
            c.startsWith("vp9") || c.startsWith("vp09") || (c.isEmpty() && v.format?.suffix == "webm") -> "vp9"
            c.startsWith("av01") -> "av1"
            else -> "other"
        }
    }

    private fun bestAudio(info: StreamInfo): String? {
        val audios = usable(info.audioStreams)
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audios }
        fun rate(a: org.schabi.newpipe.extractor.stream.AudioStream) = if (a.averageBitrate > 0) a.averageBitrate * 1000 else a.bitrate
        return (original.filter { it.format?.suffix == "m4a" }.maxByOrNull { rate(it) } ?: original.maxByOrNull { rate(it) })?.content
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IllegalStateException(FastExtractor.readable(e), e)
    }

    // ---------- text for the lists ----------

    fun count(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> trim(n / 1e9) + "B"
        n >= 1_000_000 -> trim(n / 1e6) + "M"
        n >= 1_000 -> trim(n / 1e3) + "K"
        else -> n.toString()
    }

    private fun trim(v: Double): String = if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)

    fun duration(s: Long): String = when {
        s < 0 -> "LIVE"
        s == 0L -> ""
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}
