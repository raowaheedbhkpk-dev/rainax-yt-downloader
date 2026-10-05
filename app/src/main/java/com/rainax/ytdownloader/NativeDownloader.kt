package com.rainax.ytdownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * RAINAX's own downloader (replaces yt-dlp + FFmpeg):
 * - several connections at once, 1 MB pieces with HTTP Range requests (fast, and YouTube does not throttle it)
 * - resumes after errors, network loss or app restarts (a small ".state" file remembers finished pieces)
 * - joins HD video + sound with Android's built-in MediaMuxer (no re-encoding, so it takes seconds)
 * - converts YouTube subtitles to .srt
 */
object NativeDownloader {

    internal const val BLOCK = 1024 * 1024L      // piece size (also read by PartialFiles for paused downloads)
    private const val THREADS = 4
    private const val ATTEMPTS = 4

    class Stopped : IOException("stopped")

    /** This phone can't put this picture format and sound into one file (some phones can't with VP9/AV1). */
    class Unsupported(cause: Throwable) : IOException("Can't join this format on this phone", cause)

    private val stops = ConcurrentHashMap<String, AtomicBoolean>()
    private val conns = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()

    /** Call before a download attempt starts. */
    fun begin(id: String) { stops[id] = AtomicBoolean(false) }

    /** Pause / cancel: every connection of this download is cut at once. */
    fun stop(id: String) {
        stops[id]?.set(true)
        conns[id]?.forEach { runCatching { it.disconnect() } }
    }

    fun end(id: String) {
        stops.remove(id)
        conns.remove(id)
    }

    private fun track(id: String, con: HttpURLConnection) {
        conns.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(con)
    }

    private fun untrack(id: String, con: HttpURLConnection) {
        conns[id]?.remove(con)
    }

    private fun stopped(id: String) = stops[id]?.get() == true

    // ---------- HTTP ----------

    private fun open(url: String, from: Long, to: Long?): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 20_000
        con.readTimeout = 30_000
        con.instanceFollowRedirects = true
        // YouTube links made for the visionOS client want that client's User-Agent (as NewPipe does)
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null)
        else FastExtractor.UA
        con.setRequestProperty("User-Agent", ua)
        con.setRequestProperty("Accept", "*/*")
        con.setRequestProperty("Accept-Encoding", "identity")
        SocialExtractor.headersFor(url).forEach { (k, v) -> con.setRequestProperty(k, v) }   // TikTok cookies, Referer...
        if (to != null) con.setRequestProperty("Range", "bytes=$from-$to")
        return con
    }

    /** Total size and whether the server allows pieces (Range). */
    private fun probe(url: String): Pair<Long, Boolean> {
        val con = open(url, 0, 0)
        try {
            val code = con.responseCode
            if (code == 206) {
                val total = con.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
                return total to (total > 0)
            }
            if (code in 200..299) return con.contentLengthLong to false
            throw IOException("HTTP error $code")
        } finally {
            con.disconnect()
        }
    }

    /**
     * Downloads [url] into [out]. [onBytes] receives every newly written amount (also the part already done
     * when resuming), so the caller can show progress and speed. Throws [Stopped] on pause/cancel.
     */
    fun download(
        id: String, url: String, out: File, onResumed: (Long) -> Unit, onBytes: (Long) -> Unit,
        onTotal: (Long) -> Unit = {}
    ) {
        if (stopped(id)) throw Stopped()
        val (total, ranged) = probe(url)
        if (total > 0) onTotal(total)                    // the real size (the list may only have an estimate)
        // not enough room for the rest of this file: stop now with a clear message instead of failing later
        if (total > 0 && !out.exists()) {
            val free = out.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free < total + 20L * 1024 * 1024) throw IOException("Not enough storage")
        }
        if (!ranged || total <= 0) {
            single(id, url, out, onBytes)
            return
        }
        val state = File(out.path + ".state")
        val blocks = ((total + BLOCK - 1) / BLOCK).toInt()
        val done = BooleanArray(blocks)
        // resume: same file size => keep the pieces that are already finished
        if (state.exists() && out.exists()) {
            val lines = runCatching { state.readLines() }.getOrDefault(emptyList())
            if (lines.firstOrNull()?.toLongOrNull() == total) {
                lines.getOrNull(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                    ?.forEach { if (it in 0 until blocks) done[it] = true }
            } else {
                out.delete()
            }
        }
        val already = done.indices.filter { done[it] }.sumOf { blockLen(it, total) }
        if (already > 0) onResumed(already)        // already on disk: progress, but not speed

        val pendingCount = done.count { !it }
        val taken = BooleanArray(blocks) { done[it] }
        var cursor = 0
        val failure = AtomicReference<Throwable?>(null)
        val lock = Any()
        // the player can play the pieces that are already here (watch while downloading)
        val entry = PartialFiles.Entry(out, total, BLOCK, done).also { it.complete = pendingCount == 0 }

        /** Next piece to fetch: in order, but first the one the player is waiting for. */
        fun pick(): Int = synchronized(lock) {
            val w = entry.want
            if (w in 0 until blocks) { cursor = w; entry.want = -1 }
            for (k in 0 until blocks) {
                val b = (cursor + k) % blocks
                if (!taken[b]) {
                    taken[b] = true
                    cursor = b + 1
                    return@synchronized b
                }
            }
            -1
        }

        RandomAccessFile(out, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            PartialFiles.register(entry)                 // the file exists now: the player may read it
            val workers = (1..minOf(THREADS, pendingCount.coerceAtLeast(1))).map {
                Thread {
                    try {
                        while (failure.get() == null) {
                            val b = pick()
                            if (b < 0) break
                            fetchBlock(id, url, b, total, raf, lock, onBytes)
                            synchronized(lock) {
                                done[b] = true
                                entry.touch()
                                if (b % 8 == 0) saveState(state, total, done)
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            synchronized(lock) { saveState(state, total, done) }
        }
        failure.get()?.let { throw it }
        if (done.any { !it }) throw IOException("Download incomplete")
        entry.complete = true
        // the .state file stays: if a later step fails (sound part, joining, saving) this part isn't fetched again
    }

    private fun blockLen(b: Int, total: Long): Long = minOf(BLOCK, total - b * BLOCK)

    private fun fetchBlock(
        id: String, url: String, b: Int, total: Long,
        raf: RandomAccessFile, lock: Any, onBytes: (Long) -> Unit
    ) {
        val start = b * BLOCK
        val end = start + blockLen(b, total) - 1
        var attempt = 0
        while (true) {
            if (stopped(id)) throw Stopped()
            var written = 0L
            try {
                val con = open(url, start, end)
                track(id, con)
                try {
                    val code = con.responseCode
                    if (code != 206) throw IOException("HTTP error $code")
                    con.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var pos = start
                        while (true) {
                            if (stopped(id)) throw Stopped()
                            val n = input.read(buf)
                            if (n < 0) break
                            synchronized(lock) {
                                raf.seek(pos)
                                raf.write(buf, 0, n)
                            }
                            pos += n
                            written += n
                            onBytes(n.toLong())
                        }
                    }
                } finally {
                    untrack(id, con)
                    con.disconnect()
                }
                if (written != end - start + 1) throw IOException("Piece cut short")
                return
            } catch (e: Stopped) {
                if (written > 0) onBytes(-written)
                throw e
            } catch (e: IOException) {
                if (written > 0) onBytes(-written)        // this piece starts again: take its bytes back
                if (stopped(id)) throw Stopped()           // connection cut by pause/cancel
                attempt++
                // 403/404: the address expired; the caller asks for a fresh one
                if (attempt >= ATTEMPTS || e.message?.contains("HTTP error 4") == true) throw e
                // wait a little before trying this piece again, but stop at once on pause/cancel
                repeat(8 * attempt) {
                    if (stopped(id)) throw Stopped()
                    Thread.sleep(100)
                }
            }
        }
    }

    private fun saveState(state: File, total: Long, done: BooleanArray) {
        try {
            val list = done.indices.filter { done[it] }.joinToString(",")
            // write a new file and swap it in: a crash mid-write can never leave a broken list
            val tmp = File(state.path + ".tmp")
            tmp.writeText("$total\n$list")
            if (!tmp.renameTo(state)) { state.delete(); tmp.renameTo(state) }
        } catch (e: Exception) { }
    }

    /** Servers without pieces: one connection, start to end. */
    private fun single(id: String, url: String, out: File, onBytes: (Long) -> Unit) {
        val con = open(url, 0, null)
        track(id, con)
        try {
            val code = con.responseCode
            if (code !in 200..299) throw IOException("HTTP error $code")
            val entry = PartialFiles.Entry(out, con.contentLengthLong, 0, null)
            con.inputStream.use { input ->
                out.outputStream().use { o ->
                    PartialFiles.register(entry)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped(id)) throw Stopped()
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        entry.prefix += n
                        onBytes(n.toLong())
                    }
                }
            }
            entry.complete = true
        } catch (e: IOException) {
            if (stopped(id)) throw Stopped()
            throw e
        } finally {
            untrack(id, con)
            con.disconnect()
        }
    }

    // ---------- joining video + sound ----------

    /** Puts the picture of [video] and the sound of [audio] into one file. No re-encoding. */
    fun mux(id: String, video: File, audio: File, out: File, webm: Boolean) {
        out.delete()
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val vf: MediaFormat
            val af: MediaFormat
            val m: MediaMuxer
            val vOut: Int
            val aOut: Int
            // the phone can't read or join this format: the only case where a lower quality can help
            try {
                vEx.setDataSource(video.path)
                aEx.setDataSource(audio.path)
                val vt = track(vEx, "video/")
                val at = track(aEx, "audio/")
                vEx.selectTrack(vt)
                aEx.selectTrack(at)
                vf = vEx.getTrackFormat(vt)
                af = aEx.getTrackFormat(at)
                m = MediaMuxer(
                    out.path,
                    if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = m
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                vOut = m.addTrack(vf)
                aOut = m.addTrack(af)
                m.start()
            } catch (e: Exception) {
                throw Unsupported(e)
            }
            started = true

            val size = maxOf(maxInput(vf), maxInput(af), 4 * 1024 * 1024)
            val buf = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            while (!vDone || !aDone) {
                if (stopped(id)) throw Stopped()
                // keep picture and sound interleaved by time
                val useVideo = !vDone && (aDone || vEx.sampleTime <= aEx.sampleTime)
                val ex = if (useVideo) vEx else aEx
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, ex.sampleTime.coerceAtLeast(0), if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                try {
                    m.writeSampleData(if (useVideo) vOut else aOut, buf, info)
                } catch (e: IllegalStateException) {
                    throw Unsupported(e)
                } catch (e: IllegalArgumentException) {
                    throw Unsupported(e)
                }
                ex.advance()
            }
            m.stop()
            started = false
        } finally {
            try { if (started) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            vEx.release()
            aEx.release()
        }
    }

    private fun track(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        throw IOException("No ${prefix.trimEnd('/')} track found")
    }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    // ---------- subtitles ----------

    /** Downloads a subtitle file and returns it as SRT text. */
    fun subtitleSrt(url: String, ext: String): String {
        val con = open(url, 0, null)
        val text = try {
            if (con.responseCode !in 200..299) throw IOException("HTTP error ${con.responseCode}")
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        return when (ext) {
            "srt" -> text
            "vtt" -> vttToSrt(text)
            else -> ttmlToSrt(text)
        }
    }

    private val tagRe = Regex("<[^>]+>")

    private fun vttTime(t: String): String {
        val clean = t.trim().substringBefore(' ')
        val parts = clean.split(':')
        val full = if (parts.size == 2) "00:$clean" else clean
        return full.replace('.', ',')
    }

    private fun vttToSrt(vtt: String): String {
        val out = StringBuilder()
        var n = 0
        val blocks = vtt.replace("\r\n", "\n").split(Regex("\n{2,}"))
        var lastText = ""
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) continue
            val (a, b) = lines[timing].split("-->").let { it[0] to it.getOrElse(1) { "" } }
            val text = lines.drop(timing + 1).joinToString("\n") { decode(it.replace(tagRe, "")).trim() }.trim()
            if (text.isEmpty() || text == lastText) continue      // YouTube auto captions repeat lines
            lastText = text
            n++
            out.append(n).append('\n').append(vttTime(a)).append(" --> ").append(vttTime(b)).append('\n')
                .append(text).append("\n\n")
        }
        return out.toString()
    }

    private fun ttmlTime(t: String): String {
        val v = t.trim()
        val ms: Long = when {
            v.endsWith("ms") -> v.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 0
            v.endsWith("s") -> ((v.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong()
            else -> {
                val p = v.split(':')
                val h = p.getOrNull(0)?.toLongOrNull() ?: 0
                val m = p.getOrNull(1)?.toLongOrNull() ?: 0
                val s = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                ((h * 3600 + m * 60) * 1000 + s * 1000).toLong()
            }
        }
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    private fun ttmlToSrt(ttml: String): String {
        val p = Regex("<p[^>]*\\bbegin=\"([^\"]+)\"[^>]*\\bend=\"([^\"]+)\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val out = StringBuilder()
        var n = 0
        p.findAll(ttml).forEach { m ->
            val text = decode(m.groupValues[3].replace(Regex("<br\\s*/?>"), "\n").replace(tagRe, "")).trim()
            if (text.isEmpty()) return@forEach
            n++
            out.append(n).append('\n').append(ttmlTime(m.groupValues[1])).append(" --> ")
                .append(ttmlTime(m.groupValues[2])).append('\n').append(text).append("\n\n")
        }
        return out.toString()
    }

    private fun decode(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    // ---------- names ----------

    /** A safe file name: no characters Android refuses, at most 150 bytes (emoji take 4 bytes each). */
    fun safeName(title: String, ext: String): String {
        var name = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").replace(Regex("\\s+"), " ").trim()
            .trim('.')
        if (name.isEmpty()) name = "video"
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > 150) break
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString().trim() + "." + ext
    }
}
