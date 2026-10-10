package com.rainax.ytdownloader

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * RAINAX's own extractor (NewPipe Extractor, pure Java/Kotlin, no Python):
 * reads video info, playlists and the direct stream addresses that [NativeDownloader] downloads.
 * Supported: YouTube (videos, Shorts, playlists), SoundCloud, Bandcamp, PeerTube, media.ccc.de.
 */
object FastExtractor {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val SUPPORTED = "YouTube, TikTok, Facebook, Instagram, SoundCloud and many more sites"

    @Volatile private var ready = false
    private const val REFUSED = YtFallback.REFUSED_TEXT

    @Synchronized
    internal fun init() {
        if (ready) return
        // the phone's language and country: Home shows what is popular where the user lives
        val locale = java.util.Locale.getDefault()
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        addMissingItags()
        NewPipe.init(
            HttpDownloader,
            org.schabi.newpipe.extractor.localization.Localization.fromLocale(locale),
            org.schabi.newpipe.extractor.localization.ContentCountry(country)
        )
        ready = true
    }

    @Volatile private var warmed = false

    /**
     * Background, once per app start: downloads YouTube's player code now, so the first "Download"
     * shows its sizes at once instead of waiting for it.
     */
    fun warmUp() {
        if (warmed) return
        warmed = true
        Thread {
            runCatching {
                init()
                org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                    .getSignatureTimestamp("dQw4w9WgXcQ")
                // also prepare the code that unlocks stream addresses (the slowest step of the first look-up)
                runCatching {
                    org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                        .getUrlWithThrottlingParameterDeobfuscated(
                            "dQw4w9WgXcQ", "https://rr1---sn-a5mekn6r.googlevideo.com/videoplayback?expire=1&n=AbCdEfGhIjKlMnOp&itag=18"
                        )
                }
            }.onFailure { warmed = false }          // offline: try again next time
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    // ---------- one lookup per video ----------
    // The video page, the download sheet and the download itself all need the same video info.
    // It is fetched once (even when asked for at the same moment) and kept for 20 minutes.

    private class CachedInfo(val at: Long, val info: StreamInfo)
    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, CachedInfo>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<StreamInfo>>()
    private const val INFO_MS = 20 * 60 * 1000L

    /** Blocking. The video's info, from memory when possible. */
    internal fun streamInfo(url: String): StreamInfo {
        init()
        val key = videoUrl(url)
        infoCache[key]?.let { if (System.currentTimeMillis() - it.at < INFO_MS) return it.info }
        val mine = java.util.concurrent.CompletableFuture<StreamInfo>()
        val running = inFlight.putIfAbsent(key, mine) ?: mine
        if (running === mine) {
            try {
                val info = try {
                    StreamInfo.getInfo(key)
                } catch (e: Exception) {
                    // "not available" (videos made for kids and others): try YouTube's TV app way once
                    if (!YtFallback.canHelp(e)) throw e
                    try { YtFallback.info(key) } catch (second: Exception) {
                        android.util.Log.w("RAINAX", "fallback failed: ${second.message}")
                        // YouTube's own reason, so the message says what really happened
                        val why = second.message.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)
                        throw IllegalStateException("$REFUSED $why".trim(), e)
                    }
                }
                if (infoCache.size > 40) {
                    infoCache.entries.minByOrNull { it.value.at }?.let { infoCache.remove(it.key) }
                }
                infoCache[key] = CachedInfo(System.currentTimeMillis(), info)
                mine.complete(info)
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
            } finally {
                inFlight.remove(key)
            }
        }
        try {
            return running.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** Drops the saved info (its stream addresses stopped working). */
    fun forget(url: String) {
        infoCache.remove(videoUrl(url))
    }

    /**
     * The extractor ignores picture formats it doesn't know, and its list has no 8K (AV1 402 / 571) and no
     * AV1 60fps (699-702). Teach it these by reusing the places of old formats YouTube no longer sends
     * (3GP 17/36, WebM 43-46). Downloads can then offer 8K when a video has it.
     */
    internal fun addMissingItags() = runCatching {
        val cls = org.schabi.newpipe.extractor.services.youtube.ItagItem::class.java
        val field = cls.getDeclaredField("ITAG_LIST").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val list = field.get(null) as Array<org.schabi.newpipe.extractor.services.youtube.ItagItem>
        val only = org.schabi.newpipe.extractor.services.youtube.ItagItem.ItagType.VIDEO_ONLY
        val mp4 = org.schabi.newpipe.extractor.MediaFormat.MPEG_4
        val extra = listOf(
            org.schabi.newpipe.extractor.services.youtube.ItagItem(571, only, mp4, "4320p"),
            org.schabi.newpipe.extractor.services.youtube.ItagItem(402, only, mp4, "4320p"),
            org.schabi.newpipe.extractor.services.youtube.ItagItem(702, only, mp4, "4320p60", 60),
            org.schabi.newpipe.extractor.services.youtube.ItagItem(701, only, mp4, "2160p60", 60),
            org.schabi.newpipe.extractor.services.youtube.ItagItem(700, only, mp4, "1440p60", 60),
            org.schabi.newpipe.extractor.services.youtube.ItagItem(699, only, mp4, "1080p60", 60)
        ).filter { !org.schabi.newpipe.extractor.services.youtube.ItagItem.isSupported(it.id) }
        val oldIds = setOf(17, 36, 43, 44, 45, 46)
        var next = 0
        for (i in list.indices) {
            if (next >= extra.size) break
            if (list[i].id in oldIds) list[i] = extra[next++]
        }
    }

    /** True for YouTube videos and Shorts (used for look-ahead, cache keys and thumbnails). */
    fun supports(url: String): Boolean {
        val u = url.lowercase()
        val yt = u.contains("youtube.com/watch") || u.contains("youtube.com/shorts/") || u.contains("youtu.be/") ||
            u.contains("youtubekids.com/watch")
        return yt && !u.contains("music.youtube.com")
    }

    /**
     * A clean single-video address. YouTube links opened from a mix or playlist carry "&list=...",
     * and then the extractor treats them as a playlist ("URL not accepted"). Keep only the video id.
     */
    fun videoUrl(url: String): String {
        val u = url.trim()
        val low = u.lowercase()
        val isYt = low.contains("youtube.com/") || low.contains("youtu.be/") || low.contains("youtubekids.com/")
        if (!isYt) return u
        Regex("[?&]v=([\\w-]{6,})").find(u)?.let { return "https://www.youtube.com/watch?v=" + it.groupValues[1] }
        Regex("youtube\\.com/shorts/([\\w-]{6,})", RegexOption.IGNORE_CASE).find(u)
            ?.let { return "https://www.youtube.com/shorts/" + it.groupValues[1] }
        Regex("youtu\\.be/([\\w-]{6,})", RegexOption.IGNORE_CASE).find(u)
            ?.let { return "https://www.youtube.com/watch?v=" + it.groupValues[1] }
        Regex("youtube\\.com/live/([\\w-]{6,})", RegexOption.IGNORE_CASE).find(u)
            ?.let { return "https://www.youtube.com/watch?v=" + it.groupValues[1] }
        return u
    }

    /** A clean playlist address (also from a watch link that has &list=...). */
    fun playlistUrl(url: String): String {
        val list = Regex("[?&]list=([\\w-]+)").find(url)?.groupValues?.get(1) ?: return url.trim()
        return "https://www.youtube.com/playlist?list=$list"
    }

    // ---------- info for the download sheet ----------

    /** Blocking. Title, thumbnail, qualities with sizes and subtitles. Throws a readable error. */
    fun fetch(url: String): PreviewState = guard {
        init()
        if (SocialExtractor.handles(url)) return@guard SocialExtractor.fetch(url)     // TikTok, Facebook, Instagram...
        // YouTube: formats and sizes from one quick question to YouTube (about a second), not the full look-up
        // (5 requests in a row + the player code). The full look-up then runs in the background for the download.
        if (supports(url) && infoCache[videoUrl(url)] == null) {
            quickSizes(url)?.let { quick ->
                warmFull(url)
                return@guard quick
            }
        }
        val info = streamInfo(url)
        checkPlayable(info)
        val duration = info.duration.toInt()

        val progressive = mutableMapOf<Int, Long>()
        val videoOnly = mutableMapOf<Int, Long>()
        for (v in usable(info.videoStreams)) {
            val h = heightOf(v) ?: continue
            progressive[h] = maxOf(progressive[h] ?: 0L, sizeOf(v.itagItem?.contentLength, v.bitrate, duration))
        }
        for (v in usable(info.videoOnlyStreams).filter { muxable(it) }) {
            val h = heightOf(v) ?: continue
            videoOnly[h] = maxOf(videoOnly[h] ?: 0L, sizeOf(v.itagItem?.contentLength, v.bitrate, duration))
        }
        val audios = usable(info.audioStreams)
        val bestAudio = audios.maxOfOrNull { audioSize(it, duration) } ?: 0L
        val bestM4a = audios.filter { it.format?.suffix == "m4a" }.maxOfOrNull { audioSize(it, duration) } ?: 0L
        if (progressive.isEmpty() && videoOnly.isEmpty() && audios.isEmpty()) {
            error("No downloadable formats for this link")
        }

        val subs = info.subtitles.orEmpty()
            .distinctBy { it.languageTag }
            .take(30)
            .map { s -> SubtitleOption(s.languageTag, subtitleName(s)) }

        val built = InfoFetcher.buildChoices(progressive, videoOnly, bestAudio, bestM4a, duration, subs)
        val thumbUrl = info.thumbnails
            .sortedBy { kotlin.math.abs((it.height.takeIf { h -> h > 0 } ?: 360) - 360) }
            .firstOrNull()?.url
        PreviewState(
            title = info.name,
            subtitle = subtitleLine(info.uploaderName, duration),
            quick = if (progressive.isEmpty() && videoOnly.isEmpty()) built.quick.filter { it.kind == KIND_AUDIO } else built.quick,
            all = if (progressive.isEmpty() && videoOnly.isEmpty()) built.all.filter { it.kind == KIND_AUDIO } else built.all,
            subtitles = built.subs,
            audioUrl = bestAudioUrl(audios),
            thumbUrl = info.thumbnails.maxByOrNull { it.height }?.url ?: thumbUrl,
            uploader = info.uploaderName,
            related = relatedOf(info)
        )
    }

    // ---------- quick sizes (download sheet) ----------

    private val fullPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** The full look-up (stream addresses for the download) in the background, so Download starts at once. */
    private fun warmFull(url: String) {
        fullPool.execute { runCatching { streamInfo(url) } }
    }

    /**
     * Blocking. Title, picture, formats with sizes and subtitles straight from YouTube's answer to the
     * visionOS app (the same answer the full look-up uses for its streams), without the extra web,
     * "up next" and player-code requests. Null when anything is unusual (age limits, kids videos, live,
     * changes on YouTube's side): then the normal full look-up runs.
     */
    private fun quickSizes(url: String): PreviewState? = runCatching {
        val id = Regex("(?:[?&]v=|youtu\\.be/|shorts/|/live/)([\\w-]{11})").find(url)?.groupValues?.get(1) ?: return null
        val helper = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper")
        val m = helper.getMethod(
            "getVisionOsPlayerResponse",
            org.schabi.newpipe.extractor.localization.ContentCountry::class.java,
            org.schabi.newpipe.extractor.localization.Localization::class.java,
            String::class.java, String::class.java
        )
        val res = m.invoke(
            null, NewPipe.getPreferredContentCountry(), NewPipe.getPreferredLocalization(), id,
            org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.generateContentPlaybackNonce()
        ) ?: return null
        // (the answer is the extractor's own JSON object: written back to text with its JSON writer)
        val writer = Class.forName("com.grack.nanojson.JsonWriter").methods
            .first { it.name == "string" && it.parameterTypes.size == 1 && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val text = writer.invoke(null, res) as String
        val json = org.json.JSONObject(text)
        if (json.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val details = json.optJSONObject("videoDetails") ?: return null
        if (details.optString("videoId") != id || details.optBoolean("isLive") || details.optBoolean("isPostLiveDvr")) return null
        val sd = json.optJSONObject("streamingData") ?: return null
        val duration = details.optString("lengthSeconds").toIntOrNull() ?: 0

        fun size(f: org.json.JSONObject): Long {
            val len = f.optString("contentLength").toLongOrNull() ?: 0L
            if (len > 0) return len
            val ms = f.optString("approxDurationMs").toLongOrNull()?.div(1000)?.toInt() ?: duration
            return sizeOf(null, f.optInt("averageBitrate", f.optInt("bitrate")), ms)
        }
        fun known(f: org.json.JSONObject) = runCatching {
            org.schabi.newpipe.extractor.services.youtube.ItagItem.isSupported(f.optInt("itag"))
        }.getOrDefault(false)
        fun codecs(f: org.json.JSONObject) = f.optString("mimeType").substringAfter("codecs=\"", "").substringBefore('"').lowercase()

        val progressive = mutableMapOf<Int, Long>()
        val formats = sd.optJSONArray("formats")
        for (i in 0 until (formats?.length() ?: 0)) {
            val f = formats!!.getJSONObject(i)
            if (!known(f) || !f.optString("mimeType").startsWith("video/")) continue
            val h = f.optInt("height")
            if (h > 0) progressive[h] = maxOf(progressive[h] ?: 0L, size(f))
        }
        val videoOnly = mutableMapOf<Int, Long>()
        var bestAudio = 0L
        var bestM4a = 0L
        val adaptive = sd.optJSONArray("adaptiveFormats")
        for (i in 0 until (adaptive?.length() ?: 0)) {
            val f = adaptive!!.getJSONObject(i)
            if (!known(f)) continue
            val mime = f.optString("mimeType")
            val c = codecs(f)
            if (mime.startsWith("video/")) {
                // only pictures Android can join with sound (same rule as the full look-up)
                val ok = when {
                    mime.startsWith("video/mp4") -> c.isEmpty() || c.startsWith("avc") || (c.startsWith("av01") && android.os.Build.VERSION.SDK_INT >= 31)
                    mime.startsWith("video/webm") -> c.isEmpty() || c.startsWith("vp9") || c.startsWith("vp09") || c.startsWith("vp8")
                    else -> false
                }
                val h = f.optInt("height")
                if (ok && h > 0) videoOnly[h] = maxOf(videoOnly[h] ?: 0L, size(f))
            } else if (mime.startsWith("audio/")) {
                // the original sound track (not dubbed copies), not the "stable volume" copy
                val track = f.optJSONObject("audioTrack")
                if (track != null && !track.optBoolean("audioIsDefault", true)) continue
                if (f.optBoolean("isDrc")) continue
                val sz = size(f)
                bestAudio = maxOf(bestAudio, sz)
                if (mime.startsWith("audio/mp4")) bestM4a = maxOf(bestM4a, sz)
            }
        }
        if (progressive.isEmpty() && videoOnly.isEmpty() && bestAudio == 0L) return null

        val subs = mutableListOf<SubtitleOption>()
        val tracks = json.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
        for (i in 0 until (tracks?.length() ?: 0)) {
            val t = tracks!!.getJSONObject(i)
            val code = t.optString("languageCode").takeIf { it.isNotBlank() } ?: continue
            val nameObj = t.optJSONObject("name")
            val name = nameObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                ?: nameObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
                ?: runCatching { java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
                ?: code
            if (subs.none { it.code == code }) subs += SubtitleOption(code, if (t.optString("kind") == "asr") "$name (auto)" else name)
        }

        val built = InfoFetcher.buildChoices(progressive, videoOnly, bestAudio, bestM4a, duration, subs.take(30))
        val onlyAudio = progressive.isEmpty() && videoOnly.isEmpty()
        val thumbs = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        PreviewState(
            title = details.optString("title"),
            subtitle = subtitleLine(details.optString("author"), duration),
            quick = if (onlyAudio) built.quick.filter { it.kind == KIND_AUDIO } else built.quick,
            all = if (onlyAudio) built.all.filter { it.kind == KIND_AUDIO } else built.all,
            subtitles = built.subs,
            thumbUrl = thumb,
            uploader = details.optString("author")
        )
    }.onFailure { android.util.Log.w("RAINAX", "quick sizes: ${it.message}") }.getOrNull()

    /** YouTube's "up next" videos (used as next/previous tracks in background play). */
    private fun relatedOf(info: StreamInfo): List<PlaylistEntry> = runCatching {
        info.relatedItems.orEmpty()
            .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
            .filter { !it.url.isNullOrBlank() && it.streamType != StreamType.LIVE_STREAM }
            .take(25)
            .map { item ->
                val thumb = item.thumbnails.maxByOrNull { it.height }?.url
                PlaylistEntry(item.url, item.name.orEmpty(), thumb, item.duration.coerceAtLeast(0))
            }
    }.getOrDefault(emptyList())

    /** Blocking. Just the sound address of a video (for the background player's next tracks). */
    fun audioUrl(url: String): String = guard {
        init()
        val info = streamInfo(url)
        checkPlayable(info)
        bestAudioUrl(usable(info.audioStreams)) ?: error("No audio found for this link")
    }

    /** Sound for background play: the original track, M4A preferred (plays everywhere). */
    private fun bestAudioUrl(audios: List<AudioStream>): String? {
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audios }
        return rankAudio(original, preferM4a = true).firstOrNull()?.content
    }

    /** Blocking. Up to 1000 playlist entries (private/deleted ones skipped). */
    fun fetchPlaylist(url: String): PreviewState = guard {
        init()
        val clean = playlistUrl(url)
        val info = PlaylistInfo.getInfo(clean)
        val items = info.relatedItems.toMutableList()
        var page: Page? = if (info.hasNextPage()) info.nextPage else null
        val seen = items.mapNotNullTo(HashSet()) { it.url }
        while (page != null && items.size < 1000) {
            val more = PlaylistInfo.getMoreItems(info.service, clean, page)
            val fresh = more.items.filter { it.url != null && seen.add(it.url) }
            if (fresh.isEmpty()) break                     // mixes repeat themselves: stop at the first page with nothing new
            items += fresh
            page = if (more.hasNextPage()) more.nextPage else null
        }
        var skipped = 0
        val list = items.take(1000).mapNotNull { item ->
            val name = item.name.orEmpty()
            val low = name.lowercase()
            if (low == "[private video]" || low == "[deleted video]" || item.url.isNullOrBlank()) {
                skipped++
                null
            } else {
                val thumb = item.thumbnails
                    .sortedBy { kotlin.math.abs((it.height.takeIf { h -> h > 0 } ?: 180) - 180) }
                    .firstOrNull()?.url
                PlaylistEntry(item.url, name, thumb, item.duration.coerceAtLeast(0))
            }
        }
        check(list.isNotEmpty()) {
            if (skipped > 0) "All $skipped videos in this playlist are private or deleted." else "Playlist is empty"
        }
        PreviewState(
            title = info.name ?: "Playlist",
            subtitle = "${list.size} videos" + playlistLength(list) +
                if (skipped > 0) "  •  $skipped private/deleted skipped" else "",
            playlist = list
        )
    }

    // ---------- what to download ----------

    class Part(val url: String, val size: Long, val ext: String)

    class Plan(
        val title: String,
        val thumbUrl: String?,
        val video: Part?,          // null for audio downloads or when [single] already has sound
        val audio: Part?,
        val single: Part?,         // one file that already has picture and sound (or audio only)
        val webm: Boolean,         // join as WebM (VP9 + Opus) instead of MP4
        val subtitle: SubtitlesStream?,
        val canFallBack: Boolean = false,  // 2K/4K (VP9/AV1): if this phone can't join it, retry as 1080p H.264
        val audioAlts: List<Part> = emptyList(),  // other sound streams to try if YouTube refuses the first one
        val uploader: String = ""                 // channel name (the artist in music files)
    )

    /** Blocking. Picks the streams for a quality choice ("video:720", "video:0" = best, "audio:..."). */
    fun plan(url: String, spec: String, subLang: String?): Plan = guard {
        init()
        if (SocialExtractor.handles(url)) return@guard SocialExtractor.plan(url, spec)
        val info = streamInfo(url)
        checkPlayable(info)
        val duration = info.duration.toInt()
        val thumb = info.thumbnails.maxByOrNull { it.height }?.url
        val sub = subLang?.let { lang ->
            val forLang = info.subtitles.orEmpty().filter { it.languageTag == lang && it.isUrl }
                .sortedBy { it.isAutoGenerated }          // real subtitles before automatic ones
            forLang.firstOrNull { it.extension == "srt" } ?: forLang.firstOrNull { it.extension == "vtt" }
                ?: forLang.firstOrNull { it.extension == "ttml" }
        }
        val audios = usable(info.audioStreams)
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }
            .ifEmpty { audios }

        if (spec.startsWith("audio")) {
            val ranked = rankAudio(original, preferM4a = true)
            val a = ranked.firstOrNull()
            if (a != null) {
                // backups: the same kind first (M4A), then any other sound stream (other formats and languages)
                val alts = (ranked.drop(1) + rankAudio(audios, preferM4a = true))
                    .distinctBy { it.content }.filter { it.content != a.content }.take(4)
                    .map { part(it, audioSize(it, duration)) }
                return@guard Plan(info.name, thumb, null, null, part(a, audioSize(a, duration)), false, null, audioAlts = alts,
                    uploader = info.uploaderName.orEmpty())
            }
            // no separate sound on this site: save the smallest video that has sound
            val v = usable(info.videoStreams).minByOrNull { heightOf(it) ?: Int.MAX_VALUE }
                ?: error("No audio found for this link")
            return@guard Plan(info.name, thumb, null, null, part(v, sizeOf(v.itagItem?.contentLength, v.bitrate, duration)), false, null,
                uploader = info.uploaderName.orEmpty())
        }

        val want = spec.split(':').getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val mp4Only = spec.endsWith(":mp4")          // after a failed WebM join on this phone
        val muxed = usable(info.videoStreams).filter { (heightOf(it) ?: 0) <= want }
            .maxByOrNull { heightOf(it) ?: 0 }
        val only = usable(info.videoOnlyStreams).filter {
            muxable(it) && (!mp4Only || isAvc(it)) && (heightOf(it) ?: 0) <= want
        }
        // highest quality first; at the same height prefer H.264 (plays everywhere), then VP9, then AV1
        val ranked = only.sortedWith(
            compareByDescending<VideoStream> { heightOf(it) ?: 0 }
                .thenByDescending { if (isAvc(it)) 3 else if (it.format?.suffix == "webm") 2 else 1 }
                .thenByDescending { it.bitrate }
        )
        val muxedH = muxed?.let { heightOf(it) } ?: -1
        val m4aList = rankAudio(original.filter { it.format?.suffix == "m4a" }, preferM4a = true)
        val webmList = rankAudio(original.filter { it.format?.suffix == "webm" }, preferM4a = false)
        val m4a = m4aList.firstOrNull()
        val webmAudio = webmList.firstOrNull()

        // the best picture that has a matching sound track (WebM needs Opus, MP4 needs AAC)
        for (v in ranked) {
            if ((heightOf(v) ?: 0) <= muxedH) break
            val webm = v.format?.suffix == "webm"
            val a = (if (webm) webmAudio else m4a) ?: continue
            val vp = part(v, sizeOf(v.itagItem?.contentLength, v.bitrate, duration))
            // backups must be the same kind (MP4 needs AAC, WebM needs Opus) to join with the picture
            val alts = (if (webm) webmList else m4aList).filter { it.content != a.content }.take(3)
                .map { part(it, audioSize(it, duration)) }
            return@guard Plan(info.name, thumb, vp, part(a, audioSize(a, duration)), null, webm, sub,
                canFallBack = !isAvc(v), audioAlts = alts, uploader = info.uploaderName.orEmpty())
        }
        if (muxed != null) {
            val s = part(muxed, sizeOf(muxed.itagItem?.contentLength, muxed.bitrate, duration))
            return@guard Plan(info.name, thumb, null, null, s, false, sub, uploader = info.uploaderName.orEmpty())
        }
        error("No video format found for this link")
    }

    // ---------- helpers ----------

    private fun playlistLength(list: List<PlaylistEntry>): String {
        val total = list.sumOf { it.seconds }
        if (total <= 0) return ""
        val h = total / 3600
        val m = (total % 3600) / 60
        return "  •  " + if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun checkPlayable(info: StreamInfo) {
        when (info.streamType) {
            StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> error("Live streams can't be downloaded")
            StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM -> error("This live stream has not been processed yet. Try later")
            else -> {}
        }
    }

    private fun <T : org.schabi.newpipe.extractor.stream.Stream> usable(list: List<T>?): List<T> =
        list.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }

    /** Video-only streams Android can join with sound: H.264 in MP4, VP9/VP8 in WebM. */
    private fun isAvc(v: VideoStream): Boolean {
        val codec = v.codec.orEmpty().lowercase()
        return v.format?.suffix == "mp4" && (codec.isEmpty() || codec.startsWith("avc"))
    }

    /**
     * Video-only streams Android can join with sound without re-encoding:
     * H.264 in MP4, VP9/VP8 in WebM, and AV1 in MP4 (Android 12+; YouTube gives 2K/4K mostly as AV1 or VP9).
     */
    private fun muxable(v: VideoStream): Boolean {
        val codec = v.codec.orEmpty().lowercase()
        return when (v.format?.suffix) {
            "mp4" -> codec.isEmpty() || codec.startsWith("avc") ||
                (codec.startsWith("av01") && android.os.Build.VERSION.SDK_INT >= 31)
            "webm" -> codec.isEmpty() || codec.startsWith("vp9") || codec.startsWith("vp09") || codec.startsWith("vp8")
            else -> false
        }
    }

    @Suppress("DEPRECATION")       // some sites only give the old "720p" text, not a number
    private fun heightOf(v: VideoStream): Int? =
        v.height.takeIf { it > 0 } ?: v.resolution.substringBefore('p').toIntOrNull()

    private fun sizeOf(len: Long?, bitrate: Int, duration: Int): Long = when {
        len != null && len > 0 -> len
        bitrate > 0 && duration > 0 -> bitrate.toLong() / 8 * duration
        else -> 0L
    }

    /**
     * Sound streams best first: normal sound before YouTube's "stable volume" (DRC) copies, M4A before WebM
     * when [preferM4a], then the highest bitrate.
     */
    private fun rankAudio(list: List<AudioStream>, preferM4a: Boolean): List<AudioStream> = list.sortedWith(
        compareBy<AudioStream> { if (it.itagItem?.isDrc() == true) 1 else 0 }
            .thenBy { if (preferM4a && it.format?.suffix != "m4a") 1 else 0 }
            .thenByDescending { audioRate(it) }
    )

    /** AudioStream.averageBitrate is in kbit/s; bitrate is in bit/s. */
    private fun audioRate(a: AudioStream): Int = if (a.averageBitrate > 0) a.averageBitrate * 1000 else a.bitrate

    private fun audioSize(a: AudioStream, duration: Int) = sizeOf(a.itagItem?.contentLength, audioRate(a), duration)

    private fun part(s: org.schabi.newpipe.extractor.stream.Stream, size: Long): Part {
        val ext = s.format?.suffix ?: "mp4"
        return Part(s.content, size, ext)
    }

    private fun subtitleName(s: SubtitlesStream): String {
        val name = runCatching { s.locale.getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: s.displayLanguageName ?: s.languageTag
        return if (s.isAutoGenerated) "$name (auto)" else name
    }

    private fun subtitleLine(uploader: String?, seconds: Int): String {
        val parts = mutableListOf<String>()
        if (!uploader.isNullOrBlank()) parts += uploader
        if (seconds > 0) {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            parts += if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
        return parts.joinToString("  •  ")
    }

    /** Turns extractor errors into messages the app understands (private, age limit, unsupported site...). */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IllegalStateException(readable(e), e)
    }

    fun readable(e: Throwable): String {
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        // no connection (also when it is hidden inside another error): one clear message
        val net = generateSequence(e) { it.cause }.take(6).any {
            it is java.net.UnknownHostException || it is java.net.ConnectException ||
                it is java.net.NoRouteToHostException || it is java.net.SocketTimeoutException
        }
        if (net || isNetworkError(msg)) {
            return if (!Net.online()) NO_INTERNET else "Connection problem. Check your internet, then try again."
        }
        return when {
            name.contains("PrivateContent") -> "Private video"
            name.contains("AccountTerminated") -> "Video unavailable: the channel was terminated"
            name.contains("AgeRestricted") -> "Age-restricted video (confirm your age)"
            name.contains("GeographicRestriction") -> "This video is not available in your country"
            name.contains("PaidContent") || name.contains("Premium") -> "This video is not available (paid or members-only)"
            name.contains("ContentNotSupported") -> "Unsupported URL: this kind of content can't be downloaded"
            name.contains("ContentNotAvailable") -> "Video unavailable: " + msg.take(80)
            name.contains("ReCaptcha") -> "YouTube is limiting requests right now. Trying again later"
            msg.contains("No service can handle", true) ->
                "Unsupported URL: RAINAX downloads from $SUPPORTED"
            e is IllegalStateException && msg.isNotBlank() -> msg
            else -> (msg.ifBlank { name }).take(160)
        }
    }

    /** Minimal HTTP client for the extractor (no extra library needed). */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val con = URL(request.url()).openConnection() as HttpURLConnection
            try {
                con.requestMethod = request.httpMethod()
                con.connectTimeout = 15_000
                con.readTimeout = 20_000
                con.instanceFollowRedirects = true
                con.setRequestProperty("User-Agent", UA)
                request.headers()?.forEach { (name, values) ->
                    if (name != null) {
                        con.setRequestProperty(name, values.firstOrNull() ?: "")
                        values.drop(1).forEach { con.addRequestProperty(name, it) }
                    }
                }
                val body = request.dataToSend()
                if (body != null) {
                    con.doOutput = true
                    con.outputStream.use { it.write(body) }
                }
                val code = con.responseCode
                if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                val stream = if (code >= 400) con.errorStream else con.inputStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }
                val headers = con.headerFields.filterKeys { it != null }
                // the body was read to the end and closed, so the connection goes back to the pool and the
                // next request skips the slow new handshake (only a failed request drops it)
                return Response(code, con.responseMessage, headers, text, con.url.toString())
            } catch (e: Exception) {
                con.disconnect()
                throw e
            }
        }
    }
}
