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
    const val SUPPORTED = "YouTube, SoundCloud, Bandcamp and PeerTube"

    @Volatile private var ready = false

    @Synchronized
    private fun init() {
        if (ready) return
        NewPipe.init(HttpDownloader)
        ready = true
    }

    /** True for YouTube videos and Shorts (used for look-ahead, cache keys and thumbnails). */
    fun supports(url: String): Boolean {
        val u = url.lowercase()
        val yt = u.contains("youtube.com/watch") || u.contains("youtube.com/shorts/") || u.contains("youtu.be/")
        return yt && !u.contains("music.youtube.com")
    }

    /**
     * A clean single-video address. YouTube links opened from a mix or playlist carry "&list=...",
     * and then the extractor treats them as a playlist ("URL not accepted"). Keep only the video id.
     */
    fun videoUrl(url: String): String {
        val u = url.trim()
        val low = u.lowercase()
        val isYt = low.contains("youtube.com/") || low.contains("youtu.be/")
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
        val info = StreamInfo.getInfo(videoUrl(url))
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
            thumb = thumbUrl?.let { InfoFetcher.loadBitmap(it) },
            quick = if (progressive.isEmpty() && videoOnly.isEmpty()) built.quick.filter { it.kind == KIND_AUDIO } else built.quick,
            all = if (progressive.isEmpty() && videoOnly.isEmpty()) built.all.filter { it.kind == KIND_AUDIO } else built.all,
            subtitles = built.subs,
            audioUrl = bestAudioUrl(audios),
            thumbUrl = info.thumbnails.maxByOrNull { it.height }?.url ?: thumbUrl,
            uploader = info.uploaderName
        )
    }

    /** Sound for background play: the original track, M4A preferred (plays everywhere). */
    private fun bestAudioUrl(audios: List<AudioStream>): String? {
        val original = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audios }
        return (original.filter { it.format?.suffix == "m4a" }.maxByOrNull { audioRate(it) }
            ?: original.maxByOrNull { audioRate(it) })?.content
    }

    /** Blocking. Up to 1000 playlist entries (private/deleted ones skipped). */
    fun fetchPlaylist(url: String): PreviewState = guard {
        init()
        val clean = playlistUrl(url)
        val info = PlaylistInfo.getInfo(clean)
        val items = info.relatedItems.toMutableList()
        var page: Page? = if (info.hasNextPage()) info.nextPage else null
        while (page != null && items.size < 1000) {
            val more = PlaylistInfo.getMoreItems(info.service, clean, page)
            items += more.items
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
        val canFallBack: Boolean = false   // 2K/4K (VP9/AV1): if this phone can't join it, retry as 1080p H.264
    )

    /** Blocking. Picks the streams for a quality choice ("video:720", "video:0" = best, "audio:..."). */
    fun plan(url: String, spec: String, subLang: String?): Plan = guard {
        init()
        val info = StreamInfo.getInfo(videoUrl(url))
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
            val a = original.filter { it.format?.suffix == "m4a" }.maxByOrNull { audioRate(it) }
                ?: original.maxByOrNull { audioRate(it) }
            if (a != null) return@guard Plan(info.name, thumb, null, null, part(a, audioSize(a, duration)), false, null)
            // no separate sound on this site: save the smallest video that has sound
            val v = usable(info.videoStreams).minByOrNull { heightOf(it) ?: Int.MAX_VALUE }
                ?: error("No audio found for this link")
            return@guard Plan(info.name, thumb, null, null, part(v, sizeOf(v.itagItem?.contentLength, v.bitrate, duration)), false, null)
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
        val m4a = original.filter { it.format?.suffix == "m4a" }.maxByOrNull { audioRate(it) }
        val webmAudio = original.filter { it.format?.suffix == "webm" }.maxByOrNull { audioRate(it) }

        // the best picture that has a matching sound track (WebM needs Opus, MP4 needs AAC)
        for (v in ranked) {
            if ((heightOf(v) ?: 0) <= muxedH) break
            val webm = v.format?.suffix == "webm"
            val a = (if (webm) webmAudio else m4a) ?: continue
            val vp = part(v, sizeOf(v.itagItem?.contentLength, v.bitrate, duration))
            return@guard Plan(info.name, thumb, vp, part(a, audioSize(a, duration)), null, webm, sub, canFallBack = !isAvc(v))
        }
        if (muxed != null) {
            val s = part(muxed, sizeOf(muxed.itagItem?.contentLength, muxed.bitrate, duration))
            return@guard Plan(info.name, thumb, null, null, s, false, sub)
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
            e is java.net.UnknownHostException -> "Unable to resolve host (no internet)"
            e is java.net.SocketTimeoutException -> "Connection timed out"
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
                return Response(code, con.responseMessage, headers, text, con.url.toString())
            } finally {
                con.disconnect()
            }
        }
    }
}
