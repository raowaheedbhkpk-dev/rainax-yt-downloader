package rainax.desktop

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/** One video in a list. */
class Video(
    val url: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val avatar: String?,
    val seconds: Long,
    val views: Long,
    val uploaded: String?,
    val live: Boolean
)

/** One way to download a video: a quality, its size and the address(es) to fetch. */
class Choice(
    val label: String,          // "1080p", "MP3"
    val sub: String,            // "MP4 · 298 MB"
    val audioOnly: Boolean,
    val videoUrl: String?,      // null for audio
    val audioUrl: String?,      // null when the video already has its sound
    val ext: String,            // file type of the result: mp4, m4a, mp3
    val size: Long,
    val toMp3: Boolean = false
)

/** RAINAX's extractor on Windows: the same NewPipe Extractor as the phone app. */
object Yt {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile private var ready = false

    @Synchronized
    fun init() {
        if (ready) return
        val locale = java.util.Locale.getDefault()
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        NewPipe.init(Http, Localization.fromLocale(locale), ContentCountry(country))
        ready = true
    }

    private val yt get() = ServiceList.YouTube

    /** A YouTube (or other supported) link, not words to search. */
    fun isLink(text: String) = text.trim().let { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("www.") || it.startsWith("youtu") }

    class Results(val items: List<Video>, val next: Page?)

    /** Blocking. Videos for [query]. */
    fun search(query: String): Results {
        init()
        val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(yt, handler)
        return Results(info.relatedItems.mapNotNull { video(it) }, info.nextPage)
    }

    /** Blocking. The next page of [query]. */
    fun more(query: String, page: Page): Results {
        init()
        val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val p = SearchInfo.getMoreItems(yt, handler, page)
        return Results(p.items.mapNotNull { video(it) }, p.nextPage)
    }

    private fun best(list: List<org.schabi.newpipe.extractor.Image>?, height: Int): String? {
        if (list.isNullOrEmpty()) return null
        val known = list.filter { it.height > 0 }.sortedBy { it.height }
        return (known.firstOrNull { it.height >= height } ?: known.lastOrNull() ?: list.last()).url
    }

    fun video(item: InfoItem): Video? {
        val i = item as? StreamInfoItem ?: return null
        val live = i.streamType == StreamType.LIVE_STREAM
        return Video(
            url = i.url, title = i.name, channel = i.uploaderName.orEmpty(),
            thumb = best(i.thumbnails, 300),
            avatar = runCatching { best(i.uploaderAvatars, 68) }.getOrNull(),
            seconds = if (live) -1 else i.duration.coerceAtLeast(0), views = i.viewCount,
            uploaded = i.textualUploadDate, live = live
        )
    }

    // ---------- one video ----------

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StreamInfo>>()

    /** Blocking. The video's details and stream addresses (kept for 20 minutes). */
    fun info(url: String): StreamInfo {
        init()
        cache[url]?.let { if (System.currentTimeMillis() - it.first < 20 * 60_000) return it.second }
        val info = StreamInfo.getInfo(url)
        cache[url] = System.currentTimeMillis() to info
        return info
    }

    fun forget(url: String) { cache.remove(url) }

    fun thumbOf(info: StreamInfo): String? = best(info.thumbnails, 720)
    fun avatarOf(info: StreamInfo): String? = runCatching { best(info.uploaderAvatars, 88) }.getOrNull()
    fun related(info: StreamInfo): List<Video> = info.relatedItems.orEmpty().mapNotNull { video(it) }.take(20)

    private fun <T : org.schabi.newpipe.extractor.stream.Stream> usable(list: List<T>?): List<T> =
        list.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.content.isNullOrBlank() }

    private fun sizeOf(len: Long?, bitsPerSec: Int, seconds: Long): Long = when {
        len != null && len > 0 -> len
        bitsPerSec > 0 && seconds > 0 -> bitsPerSec.toLong() * seconds / 8
        else -> 0L
    }

    private fun audioRate(a: AudioStream) = if (a.averageBitrate > 0) a.averageBitrate * 1000 else a.bitrate

    /**
     * The download choices, best first: every video height (a picture-only MP4 joined with the best M4A sound
     * by ffmpeg, or a stream that already has sound), then MP3 and M4A.
     */
    fun choices(info: StreamInfo): List<Choice> {
        val secs = info.duration
        val out = ArrayList<Choice>()
        val all = usable(info.audioStreams)
        // the video's own language (not a dubbed track)
        val audios = all.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { all }
        val m4a = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { audioRate(it) }
            ?: audios.maxByOrNull { audioRate(it) }
        val m4aSize = m4a?.let { sizeOf(it.itagItem?.contentLength, audioRate(it), secs) } ?: 0L

        val byHeight = LinkedHashMap<Int, Choice>()
        // picture-only MP4 + sound: every height YouTube has (needs ffmpeg to join)
        if (m4a != null && Ffmpeg.available()) {
            usable(info.videoOnlyStreams).filter { it.format == MediaFormat.MPEG_4 && heightOf(it) > 0 }
                .sortedWith(compareByDescending<VideoStream> { heightOf(it) }.thenByDescending { it.fps })
                .forEach { v ->
                    val h = heightOf(v)
                    if (byHeight.containsKey(h)) return@forEach
                    val size = sizeOf(v.itagItem?.contentLength, v.bitrate, secs) + m4aSize
                    byHeight[h] = Choice(label(v), "MP4 · ${mb(size)}", false, v.content, m4a.content, "mp4", size)
                }
        }
        // streams that already have their sound (usually 360p)
        usable(info.videoStreams).filter { heightOf(it) > 0 }.forEach { v ->
            val h = heightOf(v)
            if (byHeight.containsKey(h)) return@forEach
            val size = sizeOf(v.itagItem?.contentLength, v.bitrate, secs)
            val ext = v.format?.suffix ?: "mp4"
            byHeight[h] = Choice(label(v), "${ext.uppercase()} · ${mb(size)}", false, v.content, null, ext, size)
        }
        out += byHeight.values.sortedByDescending { it.label.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 }
        if (m4a != null) {
            if (Ffmpeg.available()) out += Choice("MP3", "Sound only · ${mb(m4aSize)}", true, null, m4a.content, "mp3", m4aSize, toMp3 = true)
            out += Choice("M4A", "Sound only · ${mb(m4aSize)}", true, null, m4a.content, m4a.format?.suffix ?: "m4a", m4aSize)
        }
        return out
    }

    private fun heightOf(v: VideoStream): Int = v.height.takeIf { it > 0 } ?: v.resolution.substringBefore('p').toIntOrNull() ?: 0

    private fun label(v: VideoStream): String = if (v.fps > 30) "${heightOf(v)}p${v.fps}" else "${heightOf(v)}p"

    fun mb(bytes: Long): String = when {
        bytes <= 0 -> "size unknown"
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    fun count(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1e6)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }.replace(".0", "")

    fun duration(s: Long): String {
        if (s < 0) return "LIVE"
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    /** NewPipe's way out to the internet (the same as the phone app's). */
    private object Http : Downloader() {
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
            } catch (e: Exception) {
                con.disconnect()
                throw e
            }
        }
    }
}
