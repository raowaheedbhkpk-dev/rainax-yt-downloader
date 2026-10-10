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
    val avatar: String? = null, // channel picture
    val isChannel: Boolean = false,
    val isShort: Boolean = false
)

/** A channel page: header and the id needed to subscribe. */
class ChannelDetails(
    val url: String,
    val id: String?,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val subscribers: Long,
    val description: String
)

class FeedPage(val items: List<VideoItem>, val next: Page?)

/** A row on the Music tab: songs (in columns of 4, like YouTube Music), song covers, or playlists. */
class MusicSection(val title: String, val items: List<VideoItem>, val kind: Int = PLAYLISTS) {
    companion object {
        const val SONGS = 0
        const val COVERS = 1
        const val PLAYLISTS = 2
    }
}

class Comment(
    val author: String,
    val avatar: String?,
    val text: String,
    val html: Boolean,
    val likes: String?,
    val date: String?,
    val replyCount: Int = 0,
    val replies: Page? = null,      // load with YtCatalog.comments(url, replies)
    val isReply: Boolean = false
)

/** One page of comments (or of replies to a comment). */
class CommentsPage(val items: List<Comment>, val next: Page?, val total: Int = -1, val disabled: Boolean = false)

/** One picture quality the player can switch to (sound is added separately). */
class VideoOption(val height: Int, val fps: Int, val url: String, val codec: String)

/**
 * What the in-app player streams: picture + sound (joined while playing), one file with both, or a live stream.
 * [options] = every picture quality (the app keeps those this phone can play).
 */
class PlaySource(
    val video: String?, val audio: String?, val muxed: String?, val hls: String?,
    val options: List<VideoOption> = emptyList(),
    val dash: String? = null        // Auto quality: every H.264 quality in one manifest, the player switches by network speed
)

/** A chapter of a video (from its description's timestamps). */
class Chapter(val startMs: Long, val title: String)

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
    val audioUrl: String?,
    val channelUrl: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val date: String? = null           // full upload date ("12 March 2024") for the description
) {
    /** "UC..." from the channel address (for Subscribe). */
    val channelId: String? get() = channelUrl?.let { Regex("/channel/([\\w-]+)").find(it)?.groupValues?.get(1) }
}

/** YouTube lists for the app's own screens (no website): Home tabs, search, video page, comments. */
object YtCatalog {

    const val HOME = "home"
    const val MUSIC = "trending_music"

    /** Home tabs: title -> list id ("home" = popular videos and the user's interests, "trending_music" = music charts). */
    val TABS = listOf(
        "All" to HOME,
        "Music" to MUSIC
    ) + TOPICS

    /** Topic chips (like YouTube's): each one is a video search ("q:<words>"). */
    val TOPICS: List<Pair<String, String>> get() = listOf(
        "Live" to "q:live now",
        "Gaming" to "q:gaming",
        "News" to "q:news today",
        "Podcasts" to "q:podcast",
        "Cricket" to "q:cricket highlights",
        "Movies" to "q:full movie",
        "Comedy" to "q:comedy",
        "Recently uploaded" to "q:new videos today"
    )

    /** Country the music charts work for (YouTube Charts skips some countries, e.g. Pakistan: then India, then US). */
    @Volatile private var musicCountry: String? = null

    private val yt get() = ServiceList.YouTube

    /** Blocking. One page of a Home tab. */
    fun kiosk(id: String, page: Page?, history: List<String> = emptyList()): FeedPage = guard {
        FastExtractor.init()
        when {
            id.startsWith("q:") -> videoSearch(id.removePrefix("q:"), page).let { p -> FeedPage(p.items.filter { !it.isPlaylist && !it.isChannel }, p.next) }
            id == HOME -> home(history, page)
            id == MUSIC -> music(page)
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

    /** Blocking. Playlists for a Music row (YouTube Music playlists, else normal YouTube playlists). */
    fun musicSection(query: String): List<VideoItem> = guard {
        FastExtractor.init()
        val fromMusic = runCatching {
            val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS), "")
            SearchInfo.getInfo(yt, handler).relatedItems.mapNotNull { item(it) }.filter { it.isPlaylist }
        }.getOrDefault(emptyList())
        fromMusic.ifEmpty {
            val handler = yt.searchQHFactory.fromQuery("$query playlist", listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
            SearchInfo.getInfo(yt, handler).relatedItems.mapNotNull { item(it) }.filter { it.isPlaylist }
        }.take(12)
    }

    // ---------- YouTube Music (the Music tab and its player) ----------

    /** Music tab song rows: title -> what to look for on YouTube Music. */
    val MUSIC_SHELVES = listOf(
        "Punjabi hits" to "punjabi hits",
        "Bollywood hits" to "bollywood hits",
        "Covers and remixes" to "remix songs hindi punjabi",
        "Ghazal & Sufi" to "sufi qawwali ghazal",
        "Pakistani hits" to "coke studio pakistan",
        "Romantic" to "romantic hindi songs",
        "Lofi & chill" to "lofi slowed reverb",
        "Global top hits" to "top hits"
    )

    /** Mood chips on the Music tab: chip -> its rows (title -> search). */
    val MUSIC_MOODS = listOf(
        "Relax" to listOf("Relaxing songs" to "relaxing songs", "Calm Hindi" to "calm hindi songs", "Soft Punjabi" to "soft punjabi songs"),
        "Energize" to listOf("Feel-good energy" to "energetic songs", "Bhangra beats" to "punjabi bhangra hits", "Bollywood dance" to "bollywood dance songs"),
        "Romance" to listOf("Love songs" to "romantic songs", "Romantic Hindi" to "romantic hindi songs", "Romantic Punjabi" to "romantic punjabi songs"),
        "Sad" to listOf("Sad songs" to "sad songs", "Sad Hindi" to "sad hindi songs", "Sad Punjabi" to "sad punjabi songs"),
        "Party" to listOf("Party starters" to "party songs", "Bollywood party" to "bollywood party songs", "Punjabi party" to "punjabi party songs"),
        "Workout" to listOf("Workout" to "workout songs", "Gym motivation" to "gym motivation songs", "Punjabi gym" to "punjabi gym songs"),
        "Focus" to listOf("Focus" to "focus music", "Lofi study" to "lofi study", "Piano" to "instrumental piano"),
        "Sleep" to listOf("Sleep" to "sleep music", "Slowed + reverb" to "slowed reverb songs", "Calm instrumental" to "calm instrumental"),
        "Commute" to listOf("Road trip" to "road trip songs", "Driving Hindi" to "driving songs hindi", "Driving Punjabi" to "punjabi driving songs"),
        "Qawwali" to listOf("Qawwali" to "qawwali", "Nusrat Fateh Ali Khan" to "nusrat fateh ali khan", "Sufi" to "sufi songs")
    )

    /** "top songs Pakistan": the most played songs in the phone's country (Quick picks before anything was played). */
    fun topSongsQuery(): String {
        val name = java.util.Locale("", deviceCountry()).getDisplayCountry(java.util.Locale.ENGLISH)
        return ("top songs " + name).trim()
    }

    /**
     * Blocking. Songs for a search, from YouTube Music (songs, else music videos), else normal YouTube videos
     * of song length. Every song gets a plain YouTube link and a big square cover where YouTube Music has one.
     */
    fun musicSongs(query: String, limit: Int = 20): List<VideoItem> = guard {
        FastExtractor.init()
        fun find(filter: String): List<VideoItem> = runCatching {
            val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
            SearchInfo.getInfo(yt, handler).relatedItems.mapNotNull { item(it) }
                .filter { !it.isPlaylist && !it.isChannel && it.seconds > 0 }
                .map { song(it) }
        }.getOrDefault(emptyList())
        val songs = find(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
            .ifEmpty { find(YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS) }
            .ifEmpty {
                videoSearch("$query song", null).items
                    .filter { !it.isPlaylist && !it.isChannel && !it.isShort && it.seconds in 60..900 }
                    .map { song(it) }
            }
        songs.distinctBy { youtubeId(it.url) }.take(limit)
    }

    /**
     * Blocking. Songs like [url]: YouTube's Mix for it (the radio YouTube Music plays after a song), else its
     * "up next" videos. The song itself is left out.
     */
    fun musicRadio(url: String): List<VideoItem> = guard {
        FastExtractor.init()
        val id = youtubeId(url) ?: return@guard emptyList()
        fun clean(list: List<VideoItem>) = list
            .filter { !it.isPlaylist && !it.isChannel && it.seconds > 0 && youtubeId(it.url) != id }
            .map { song(it) }
            .distinctBy { youtubeId(it.url) }
        for (mix in listOf("RDAMVM$id", "RD$id")) {
            val items = runCatching {
                org.schabi.newpipe.extractor.playlist.PlaylistInfo
                    .getInfo(yt, "https://www.youtube.com/watch?v=$id&list=$mix")
                    .relatedItems.mapNotNull { item(it) }
            }.getOrDefault(emptyList())
            val list = clean(items)
            if (list.size >= 5) return@guard list.take(50)
        }
        clean(FastExtractor.streamInfo(FastExtractor.videoUrl(url)).relatedItems.orEmpty().mapNotNull { item(it) }).take(30)
    }

    /** A song as the Music tab shows it: plain YouTube link, artist without " - Topic", a cover that fits. */
    private fun song(v: VideoItem): VideoItem {
        val id = youtubeId(v.url) ?: return v
        return v.copy(
            url = "https://www.youtube.com/watch?v=$id",
            uploader = v.uploader.removeSuffix(" - Topic"),
            thumb = MusicArt.sized(v.thumb, 226) ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        )
    }

    /** Blocking. One page of a playlist's videos. */
    fun playlist(url: String, page: Page?): FeedPage = guard {
        FastExtractor.init()
        val clean = FastExtractor.playlistUrl(url)
        if (page == null) {
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(clean)
            FeedPage(info.relatedItems.mapNotNull { item(it) }.filter { !hidden(it) }, if (info.hasNextPage()) info.nextPage else null)
        } else {
            val p = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(yt, clean, page)
            FeedPage(p.items.mapNotNull { item(it) }.filter { !hidden(it) }, if (p.hasNextPage()) p.nextPage else null)
        }
    }

    // ---------- channels ----------

    /** Videos tab of each opened channel (needed for the next pages). */
    private val channelTabs = java.util.concurrent.ConcurrentHashMap<String, org.schabi.newpipe.extractor.linkhandler.ListLinkHandler>()

    /** Blocking. A channel's header and its first videos. */
    fun channel(url: String): Pair<ChannelDetails, FeedPage> = guard {
        FastExtractor.init()
        val info = org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(yt, url)
        val details = ChannelDetails(
            url = url,
            id = info.id,
            name = info.name.orEmpty(),
            avatar = best(info.avatars, 176),
            banner = best(info.banners, 300),
            subscribers = info.subscriberCount,
            description = info.description.orEmpty()
        )
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(org.schabi.newpipe.extractor.channel.tabs.ChannelTabs.VIDEOS) }
            ?: info.tabs.firstOrNull()
            ?: return@guard details to FeedPage(emptyList(), null)
        channelTabs[url] = tab
        val tabInfo = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getInfo(yt, tab)
        details to FeedPage(tabInfo.relatedItems.mapNotNull { item(it) }, if (tabInfo.hasNextPage()) tabInfo.nextPage else null)
    }

    /** Blocking. More videos of a channel opened with [channel]. */
    fun channelMore(url: String, page: Page): FeedPage = guard {
        val tab = channelTabs[url] ?: error("Open the channel again")
        val p = org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo.getMoreItems(yt, tab, page)
        FeedPage(p.items.mapNotNull { item(it) }, if (p.hasNextPage()) p.nextPage else null)
    }

    private fun hidden(v: VideoItem): Boolean = v.title.lowercase().let { it == "[private video]" || it == "[deleted video]" }

    /**
     * Blocking. Shorts for the Shorts feed: YouTube's short videos (search pages; [query] picks the topic).
     * Only short videos (marked as Shorts, or up to 3 minutes) are kept.
     */
    fun shorts(query: String, page: Page?): FeedPage = guard {
        FastExtractor.init()
        val res = videoSearch(query, page)
        FeedPage(res.items.filter { !it.isPlaylist && !it.isChannel && (it.isShort || it.seconds in 1..180) }, res.next)
    }

    /** Blocking. One page of search results ([playlists] = playlists only, else videos). */
    /** Search result kinds (the chips): videos, channels, playlists. */
    val SEARCH_KINDS = listOf(
        "Videos" to YoutubeSearchQueryHandlerFactory.VIDEOS,
        "Channels" to YoutubeSearchQueryHandlerFactory.CHANNELS,
        "Playlists" to YoutubeSearchQueryHandlerFactory.PLAYLISTS
    )

    fun search(query: String, kind: Int, page: Page?): FeedPage = guard {
        FastExtractor.init()
        val filter = SEARCH_KINDS.getOrNull(kind)?.second ?: YoutubeSearchQueryHandlerFactory.VIDEOS
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
        var related = info.relatedItems.orEmpty().mapNotNull { item(it) }.filter { !it.isPlaylist }
        var avatar = best(info.uploaderAvatars, 88)
        var subscribers = info.uploaderSubscriberCount
        var likes = info.likeCount
        // opened the second way (videos made for kids): that answer has no channel picture, "up next" or likes,
        // so look them up separately (at the same time)
        if (YtFallback.served(info.id)) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                val ch = info.uploaderUrl?.takeIf { it.isNotBlank() }?.let { u ->
                    pool.submit<org.schabi.newpipe.extractor.channel.ChannelInfo?> {
                        runCatching { org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(yt, u) }.getOrNull()
                    }
                }
                val more = pool.submit<List<VideoItem>> {
                    val words = info.name.orEmpty().split('|', '-', '#').firstOrNull().orEmpty().trim().take(60)
                    runCatching { videoSearch("$words ${info.uploaderName.orEmpty()}".trim(), null).items }.getOrDefault(emptyList())
                }
                val votes = pool.submit<Pair<Long, Long>> { Dislikes.votes(info.id) }
                ch?.get(20, java.util.concurrent.TimeUnit.SECONDS)?.let { c ->
                    avatar = best(c.avatars, 88)
                    subscribers = c.subscriberCount
                }
                related = runCatching { more.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
                    .filter { !it.isPlaylist && !it.isChannel && youtubeId(it.url) != info.id }.take(25)
                val v = runCatching { votes.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                if (likes <= 0 && v != null && v.first > 0) likes = v.first
            } catch (e: Exception) {
                android.util.Log.w("RAINAX", "extra details failed: ${e.message}")
            } finally {
                pool.shutdownNow()
            }
        }
        VideoDetails(
            url = FastExtractor.videoUrl(url),
            title = info.name.orEmpty(),
            uploader = info.uploaderName.orEmpty(),
            avatar = avatar,
            subscribers = subscribers,
            views = info.viewCount,
            likes = likes,
            uploaded = niceDate(info.textualUploadDate),
            date = fullDate(info.textualUploadDate),
            description = runCatching { info.description?.content() }.getOrNull().orEmpty(),
            thumb = best(info.thumbnails, 720),
            seconds = info.duration,
            related = related,
            play = playSource(info),
            audioUrl = bestAudio(info),
            channelUrl = info.uploaderUrl,
            chapters = runCatching {
                info.streamSegments.orEmpty().map { Chapter(it.startTimeSeconds * 1000L, it.title.orEmpty()) }.sortedBy { it.startMs }
            }.getOrDefault(emptyList())
        )
    }

    /** Blocking. The first comments of a video (empty if turned off). */
    fun comments(url: String): List<Comment> = try {
        comments(url, null).items.take(20)
    } catch (e: Exception) {
        emptyList()
    }

    /** Blocking. A page of comments: the first ([page] null), the next one, or a comment's replies. */
    fun comments(url: String, page: Page?, replies: Boolean = false): CommentsPage = guard {
        FastExtractor.init()
        val clean = FastExtractor.videoUrl(url)
        if (page == null) {
            val info = CommentsInfo.getInfo(yt, clean)
            CommentsPage(
                info.relatedItems.map { comment(it, false) },
                if (info.hasNextPage()) info.nextPage else null,
                runCatching { info.commentsCount }.getOrDefault(-1),
                runCatching { info.isCommentsDisabled }.getOrDefault(false)
            )
        } else {
            val p = CommentsInfo.getMoreItems(yt, clean, page)
            CommentsPage(p.items.map { comment(it, replies) }, if (p.hasNextPage()) p.nextPage else null)
        }
    }

    private fun comment(c: org.schabi.newpipe.extractor.comments.CommentsInfoItem, reply: Boolean): Comment {
        val text = c.commentText
        return Comment(
            author = c.uploaderName.orEmpty(),
            avatar = best(c.uploaderAvatars, 48),
            text = text?.content().orEmpty(),
            html = text?.type() == org.schabi.newpipe.extractor.stream.Description.Type.HTML,
            likes = c.textualLikeCount?.takeIf { it.isNotBlank() && it != "0" },
            date = c.textualUploadDate,
            replyCount = runCatching { c.replyCount }.getOrDefault(0).coerceAtLeast(0),
            replies = runCatching { c.replies }.getOrNull(),
            isReply = reply
        )
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
                    views = i.viewCount, uploaded = niceDate(i.textualUploadDate),
                    avatar = runCatching { best(i.uploaderAvatars, 68) }.getOrNull(),
                    isShort = runCatching { i.isShortFormContent }.getOrDefault(false) || i.url.contains("/shorts/")
                )
            }
        }
        is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> {
            if (i.url.isNullOrBlank()) null
            else VideoItem(
                url = i.url, title = i.name.orEmpty(), uploader = "",
                thumb = best(i.thumbnails, 176), seconds = 0, views = i.subscriberCount, uploaded = null,
                count = i.streamCount, isChannel = true
            )
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
        val dash = runCatching { dashManifest(info) }.getOrNull()
        return if (video != null && audio != null) PlaySource(video.content, audio, muxed, null, options, dash)
        else PlaySource(null, audio, muxed ?: audio, info.hlsUrl?.takeIf { it.isNotBlank() }, if (audio != null) options else emptyList())
    }

    /**
     * A DASH manifest with every H.264 picture quality (144p-1080p) and the M4A sound, built from YouTube's own
     * byte ranges. The player starts small and moves up or down with the network: smooth on slow and fast links.
     */
    private fun dashManifest(info: StreamInfo): String? {
        fun ok(s: org.schabi.newpipe.extractor.stream.Stream): Boolean {
            val i = s.itagItem ?: return false
            return i.initStart >= 0 && i.initEnd > i.initStart && i.indexStart > i.initEnd && i.indexEnd > i.indexStart
        }
        val videos = usable(info.videoOnlyStreams)
            .filter { codecFamily(it) == "avc" && heightOf(it) in 1..1080 && ok(it) }
            .groupBy { heightOf(it) }.mapNotNull { (_, l) -> l.maxByOrNull { it.bitrate } }
            .sortedBy { heightOf(it) }
        val audios = usable(info.audioStreams).filter { it.format?.suffix == "m4a" && ok(it) }
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audios }
        val audio = original.maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate * 1000 else it.bitrate }
        if (videos.size < 2 || audio == null) return null
        val durMs = videos.first().itagItem?.approxDurationMs?.takeIf { it > 0 } ?: (info.duration * 1000)
        if (durMs <= 0) return null
        fun esc(u: String) = u.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
        sb.append("mediaPresentationDuration=\"PT").append(durMs / 1000.0).append("S\" minBufferTime=\"PT1.5S\">")
        sb.append("<Period>")
        sb.append("<AdaptationSet id=\"0\" contentType=\"video\" mimeType=\"video/mp4\" subsegmentAlignment=\"true\">")
        videos.forEachIndexed { n, v ->
            val i = v.itagItem!!
            val h = heightOf(v)
            val w = i.width.takeIf { it > 0 } ?: (h * 16 / 9)
            sb.append("<Representation id=\"v").append(n).append("\" codecs=\"").append(v.codec ?: "avc1.4d401f")
                .append("\" bandwidth=\"").append(i.bitrate.coerceAtLeast(1)).append("\" width=\"").append(w)
                .append("\" height=\"").append(h).append("\" frameRate=\"").append(v.fps.coerceAtLeast(1)).append("\">")
            sb.append("<BaseURL>").append(esc(v.content)).append("</BaseURL>")
            sb.append("<SegmentBase indexRange=\"").append(i.indexStart).append('-').append(i.indexEnd).append("\">")
            sb.append("<Initialization range=\"").append(i.initStart).append('-').append(i.initEnd).append("\"/></SegmentBase>")
            sb.append("</Representation>")
        }
        sb.append("</AdaptationSet>")
        val ai = audio.itagItem!!
        sb.append("<AdaptationSet id=\"1\" contentType=\"audio\" mimeType=\"audio/mp4\" subsegmentAlignment=\"true\">")
        sb.append("<Representation id=\"a0\" codecs=\"").append(audio.codec ?: "mp4a.40.2").append("\" bandwidth=\"")
            .append(ai.bitrate.coerceAtLeast(1)).append("\" audioSamplingRate=\"").append(ai.sampleRate.takeIf { it > 0 } ?: 44100).append("\">")
        sb.append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"")
            .append(ai.audioChannels.takeIf { it > 0 } ?: 2).append("\"/>")
        sb.append("<BaseURL>").append(esc(audio.content)).append("</BaseURL>")
        sb.append("<SegmentBase indexRange=\"").append(ai.indexStart).append('-').append(ai.indexEnd).append("\">")
        sb.append("<Initialization range=\"").append(ai.initStart).append('-').append(ai.initEnd).append("\"/></SegmentBase>")
        sb.append("</Representation></AdaptationSet></Period></MPD>")
        return sb.toString()
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

    /** "2024-03-12T09:00:05-07:00" -> "3 weeks ago"; text that is already readable stays. */
    fun niceDate(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val day = isoDay(t) ?: return t
        val days = java.time.temporal.ChronoUnit.DAYS.between(day, java.time.LocalDate.now())
        fun n(v: Long, unit: String) = "$v $unit" + (if (v == 1L) "" else "s") + " ago"
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> n(days, "day")
            days < 31 -> n(days / 7, "week")
            days < 365 -> n(days / 30, "month")
            else -> n(days / 365, "year")
        }
    }

    /** "2024-03-12..." -> "12 March 2024" (null when it isn't a date). */
    fun fullDate(text: String?): String? {
        val day = text?.trim()?.let { isoDay(it) } ?: return text?.trim()?.takeIf { it.isNotEmpty() }
        return day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH))
    }

    private fun isoDay(t: String): java.time.LocalDate? =
        if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(t)) runCatching { java.time.LocalDate.parse(t.take(10)) }.getOrNull() else null

    fun duration(s: Long): String = when {
        s < 0 -> "LIVE"
        s == 0L -> ""
        s >= 3600 -> "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}
