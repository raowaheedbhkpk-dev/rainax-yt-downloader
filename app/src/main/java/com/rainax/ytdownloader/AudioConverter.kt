package com.rainax.ytdownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Turns a downloaded sound file (M4A/AAC, WebM/Opus) into MP3 on the phone: decode with Android, encode with native LAME.
 *
 * Fast way (normal): the sound is decoded once into a work file, cut into equal parts on MP3 frame borders, and every
 * CPU core encodes one part at the same time. Each part starts 3 frames early (so the encoder is "warmed up") and those
 * frames are dropped; the frames are self-contained (no bit reservoir), so the parts join frame-exact with no gap or
 * click. Same length and quality as one encoder, about as many times faster as the phone has cores.
 * Anything unusual (odd sample rate, no room for the work file...): the one-core way below.
 */
object AudioConverter {

    private const val FRAME = 1152                 // samples per MP3 frame (MPEG-1 Layer III)
    private const val PRE_FRAMES = 3               // warm-up frames before each part (dropped)
    private const val TAIL = 4 * FRAME             // extra samples after each part, so its last frames are complete
    private const val MIN_PART_SECONDS = 15        // shorter songs use fewer cores

    /** Blocking. [stop] ends early (pause/cancel); [progress] gets 0-100. */
    fun toMp3(input: File, output: File, bitrate: Int, stop: () -> Boolean, progress: (Int) -> Unit) {
        try {
            toMp3Parallel(input, output, bitrate, stop, progress)
            return
        } catch (e: NativeDownloader.Stopped) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("RAINAX", "fast MP3 not possible, one core: ${e.message}")
            output.delete()
        }
        toMp3Serial(input, output, bitrate, stop, progress)
    }

    private class Pcm(val file: File, val rate: Int, val channels: Int, val frames: Long)

    private fun toMp3Parallel(input: File, output: File, bitrate: Int, stop: () -> Boolean, progress: (Int) -> Unit) {
        val dir = output.absoluteFile.parentFile ?: error("No folder")
        val pcmFile = File(dir, "mp3work.pcm")
        val parts = mutableListOf<File>()
        try {
            val pcm = decodeToPcm(input, pcmFile, stop) { progress(it * 25 / 100) }
            require(pcm.rate == 44100 || pcm.rate == 48000 || pcm.rate == 32000) { "sample rate ${pcm.rate}" }
            require(pcm.frames > 0) { "no sound" }
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val n = minOf(cores.toLong(), pcm.frames / (pcm.rate.toLong() * MIN_PART_SECONDS)).toInt().coerceAtLeast(1)
            val bounds = LongArray(n + 1) { k -> if (k == n) pcm.frames else (pcm.frames * k / n) / FRAME * FRAME }
            repeat(n) { parts += File(dir, "mp3part$it.mp3") }
            // encoders made one after another (LAME fills shared tables the first time)
            val encoders = ArrayList<Mp3Encoder>(n)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val done = java.util.concurrent.atomic.AtomicLong(0)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
            try {
                repeat(n) { encoders += Mp3Encoder(pcm.channels, pcm.rate, bitrate, independent = true) }
                val jobs = (0 until n).map { k ->
                    pool.submit(java.util.concurrent.Callable { encodePart(pcm, encoders[k], bounds, k, parts[k], cancelled, done) })
                }
                var last = -1
                while (jobs.any { !it.isDone }) {
                    if (stop()) cancelled.set(true)
                    val pct = 25 + (done.get() * 75 / pcm.frames).toInt().coerceIn(0, 74)
                    if (pct != last) { last = pct; progress(pct) }
                    Thread.sleep(120)
                }
                jobs.forEach { it.get() }                 // a failed part fails the fast way (then: one core)
                if (cancelled.get() || stop()) throw NativeDownloader.Stopped()
            } finally {
                pool.shutdownNow()
                encoders.forEach { runCatching { it.close() } }
            }
            // join: each part without its warm-up frames, exactly its own frames (the last part: all the rest)
            BufferedOutputStream(FileOutputStream(output), 1 shl 16).use { out ->
                for (k in 0 until n) {
                    val start = bounds[k]
                    val from = (start - PRE_FRAMES * FRAME).coerceAtLeast(0)
                    val skip = ((start - from) / FRAME).toInt()
                    val take = if (k == n - 1) -1 else ((bounds[k + 1] - start) / FRAME).toInt()
                    val copied = copyFrames(parts[k], out, skip, take)
                    check(take < 0 || copied == take) { "MP3 part $k has $copied of $take frames" }
                }
            }
            progress(100)
        } finally {
            pcmFile.delete()
            parts.forEach { it.delete() }
        }
        check(output.length() > 0) { "MP3 conversion failed" }
    }

    /** One part: samples from 3 frames before its start to a little after its end, written as MP3 frames. */
    private fun encodePart(
        pcm: Pcm, enc: Mp3Encoder, bounds: LongArray, k: Int, part: File,
        cancelled: java.util.concurrent.atomic.AtomicBoolean, done: java.util.concurrent.atomic.AtomicLong
    ) {
        val last = k == bounds.size - 2
        val start = bounds[k]
        val end = bounds[k + 1]
        val from = (start - PRE_FRAMES * FRAME).coerceAtLeast(0)
        val until = if (last) pcm.frames else minOf(pcm.frames, end + TAIL)
        val ch = pcm.channels
        val chunk = 8192
        val bytes = ByteArray(chunk * 2 * ch)
        val shorts = ShortArray(chunk * ch)
        var mp3 = ByteArray(enc.outSize(chunk))
        java.io.RandomAccessFile(pcm.file, "r").use { raf ->
            BufferedOutputStream(FileOutputStream(part), 1 shl 16).use { out ->
                raf.seek(from * 2 * ch)
                var pos = from
                while (pos < until) {
                    if (cancelled.get()) return
                    val frames = minOf(chunk.toLong(), until - pos).toInt()
                    raf.readFully(bytes, 0, frames * 2 * ch)
                    java.nio.ByteBuffer.wrap(bytes, 0, frames * 2 * ch).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, frames * ch)
                    val w = enc.encode(shorts, frames, ch, mp3)
                    out.write(mp3, 0, w)
                    val a = maxOf(pos, start)
                    val b = minOf(pos + frames, end)
                    if (b > a) done.addAndGet(b - a)
                    pos += frames
                }
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                out.write(mp3, 0, enc.finish(mp3))
            }
        }
    }

    private val BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val RATES = intArrayOf(44100, 48000, 32000)

    /**
     * Copies MPEG-1 Layer III frames of [src] to [out]: the first [skip] frames are left out, then [take] frames
     * (-1 = all the rest). Returns how many were copied.
     */
    internal fun copyFrames(src: File, out: java.io.OutputStream, skip: Int, take: Int): Int {
        var index = 0
        var copied = 0
        java.io.BufferedInputStream(java.io.FileInputStream(src), 1 shl 16).use { inp ->
            val head = ByteArray(4)
            val body = ByteArray(2048)
            fun read(b: ByteArray, n: Int): Int {
                var got = 0
                while (got < n) {
                    val r = inp.read(b, got, n - got)
                    if (r < 0) break
                    got += r
                }
                return got
            }
            while (take < 0 || copied < take) {
                val h = read(head, 4)
                if (h == 0) break
                check(h == 4) { "cut MP3 frame" }
                val b1 = head[1].toInt() and 0xFF
                val b2 = head[2].toInt() and 0xFF
                check((head[0].toInt() and 0xFF) == 0xFF && (b1 and 0xE0) == 0xE0 && ((b1 shr 3) and 3) == 3 && ((b1 shr 1) and 3) == 1) {
                    "not an MP3 frame at $index"
                }
                val br = BITRATES[(b2 shr 4) and 15]
                val sr = RATES.getOrNull((b2 shr 2) and 3) ?: error("bad MP3 sample rate")
                check(br > 0) { "free-format MP3" }
                val len = 144000 * br / sr + ((b2 shr 1) and 1)
                check(read(body, len - 4) == len - 4) { "cut MP3 frame" }
                if (index >= skip) {
                    out.write(head)
                    out.write(body, 0, len - 4)
                    copied++
                }
                index++
            }
        }
        return copied
    }

    /**
     * The decoder for [mime], Android's own software one first: phone makers' hardware sound decoders are made for
     * playing music in real time and can be about as slow as real time when converting; the software one is many
     * times faster for this. Any other decoder if that one can't take this sound.
     */
    private fun openDecoder(mime: String, format: MediaFormat): MediaCodec {
        val names = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                .sortedBy { if (it.isSoftwareOnly || it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) 0 else 1 }
                .map { it.name }
        }.getOrDefault(emptyList())
        for (name in names) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                android.util.Log.i("RAINAX", "MP3: decoding with $name")
                return codec
            } catch (e: Exception) {
                runCatching { codec.release() }
            }
        }
        return MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
    }

    /**
     * Runs the whole sound through [codec] as fast as it goes: every free input slot is filled at once and every
     * ready output is taken at once (it only waits when nothing moved). [onFormat] gets output format changes,
     * [onPcm] each piece of decoded sound.
     */
    private fun pump(
        ex: MediaExtractor, codec: MediaCodec, stop: () -> Boolean,
        onFormat: (MediaFormat) -> Unit, onPcm: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (stop()) throw NativeDownloader.Stopped()
            var moved = false
            while (!inputDone) {
                val i = codec.dequeueInputBuffer(0)
                if (i < 0) break
                val buf = codec.getInputBuffer(i)!!
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
                moved = true
            }
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (moved) 0 else 5_000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { onFormat(codec.outputFormat); moved = true; continue }
                if (o < 0) continue
                moved = true
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    onPcm(buf, info)
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
            }
        }
    }

    private class Source(val ex: MediaExtractor, val format: MediaFormat, val mime: String, val durationUs: Long)

    private fun openSource(input: File): Source {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(input.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No sound in this file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            return Source(ex, format, mime, durationUs)
        } catch (e: Exception) {
            ex.release()
            throw e
        }
    }

    private fun isFloat(f: MediaFormat) = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT

    /** Blocking. The whole sound as 16-bit stereo (or mono) samples in [pcmFile]. */
    private fun decodeToPcm(input: File, pcmFile: File, stop: () -> Boolean, progress: (Int) -> Unit): Pcm {
        val src = openSource(input)
        var codecRef: MediaCodec? = null
        try {
            var rate = src.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = src.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // room for the work file (plus the MP3 parts): otherwise the one-core way, which needs none
            if (src.durationUs > 0) {
                val need = src.durationUs / 1_000_000 * rate * 4 + (64L shl 20)
                val free = pcmFile.absoluteFile.parentFile?.usableSpace ?: 0L
                require(free > need) { "not enough free space" }
            }
            var float = false
            val codec = openDecoder(src.mime, src.format).also { codecRef = it }
            var outCh = -1                          // fixed by the first sound that comes out
            var written = 0L
            var bytes = ByteArray(0)
            var lastPct = -1
            BufferedOutputStream(FileOutputStream(pcmFile), 1 shl 18).use { out ->
                pump(src.ex, codec, stop, { f ->
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = isFloat(f)
                }) { buf, info ->
                    if (outCh < 0) outCh = inCh.coerceIn(1, 2)
                    check(inCh.coerceIn(1, 2) == outCh) { "channels changed" }
                    if (!float && inCh <= 2 && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                        // already what we want: copy as it is
                        if (bytes.size < info.size) bytes = ByteArray(info.size)
                        buf.get(bytes, 0, info.size)
                        out.write(bytes, 0, info.size)
                        written += info.size
                    } else {
                        // float sound, or more than 2 channels (keep the first two)
                        val frames = if (float) info.size / 4 / inCh else info.size / 2 / inCh
                        val need = frames * outCh * 2
                        if (bytes.size < need) bytes = ByteArray(need)
                        var w = 0
                        val floats = if (float) buf.asFloatBuffer() else null
                        val shorts = if (!float) buf.asShortBuffer() else null
                        for (fr in 0 until frames) for (c in 0 until outCh) {
                            val v: Int = if (floats != null) (floats.get(fr * inCh + c).coerceIn(-1f, 1f) * 32767f).toInt()
                            else shorts!!.get(fr * inCh + c).toInt()
                            bytes[w++] = (v and 0xFF).toByte()
                            bytes[w++] = ((v shr 8) and 0xFF).toByte()
                        }
                        out.write(bytes, 0, w)
                        written += w
                    }
                    if (src.durationUs > 0) {
                        val pct = (info.presentationTimeUs * 100 / src.durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
            }
            check(outCh > 0) { "No sound came out" }
            return Pcm(pcmFile, rate, outCh, written / (2L * outCh))
        } finally {
            runCatching { codecRef?.stop() }
            runCatching { codecRef?.release() }
            runCatching { src.ex.release() }
        }
    }

    /** The one-core way: decode and encode in one go (any sample rate). */
    private fun toMp3Serial(input: File, output: File, bitrate: Int, stop: () -> Boolean, progress: (Int) -> Unit) {
        val src = openSource(input)
        var codecRef: MediaCodec? = null
        var encoder: Mp3Encoder? = null
        try {
            var sampleRate = src.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = src.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val codec = openDecoder(src.mime, src.format).also { codecRef = it }
            var pcm = ShortArray(0)
            var mp3 = ByteArray(16 * 1024)
            var lastPct = -1
            BufferedOutputStream(FileOutputStream(output), 1 shl 16).use { out ->
                pump(src.ex, codec, stop, { f ->
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = isFloat(f)
                }) { buf, info ->
                    val count: Int
                    if (float) {
                        val floats = buf.asFloatBuffer()
                        count = floats.remaining()
                        if (pcm.size < count) pcm = ShortArray(count)
                        for (k in 0 until count) pcm[k] = (floats.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        val shorts = buf.asShortBuffer()
                        count = shorts.remaining()
                        if (pcm.size < count) pcm = ShortArray(count)
                        shorts.get(pcm, 0, count)
                    }
                    val enc = encoder ?: Mp3Encoder(channels.coerceAtMost(2), sampleRate, bitrate).also { encoder = it }
                    val frames = count / channels.coerceAtLeast(1)
                    val need = enc.outSize(frames)
                    if (mp3.size < need) mp3 = ByteArray(need)
                    out.write(mp3, 0, enc.encode(pcm, frames, channels.coerceAtLeast(1), mp3))
                    if (src.durationUs > 0) {
                        val pct = (info.presentationTimeUs * 100 / src.durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; progress(pct) }
                    }
                }
                encoder?.let { enc ->
                    if (mp3.size < 7200) mp3 = ByteArray(7200)
                    out.write(mp3, 0, enc.finish(mp3))
                }
            }
        } finally {
            runCatching { encoder?.close() }
            runCatching { codecRef?.stop() }
            runCatching { codecRef?.release() }
            runCatching { src.ex.release() }
        }
        check(output.length() > 0) { "MP3 conversion failed" }
    }
}
