package com.rainax.ytdownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/** Turns a downloaded sound file (M4A/AAC, WebM/Opus) into MP3 on the phone: decode with Android, encode with native LAME. */
object AudioConverter {

    /** Blocking. [stop] ends early (pause/cancel); [progress] gets 0-100. */
    fun toMp3(input: File, output: File, bitrate: Int, stop: () -> Boolean, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        var codecRef: MediaCodec? = null
        var outRef: BufferedOutputStream? = null
        var encoder: Mp3Encoder? = null
        // everything is released at the end, also when setting up fails (bad file, no decoder...)
        try {
        ex.setDataSource(input.path)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No sound in this file")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        val codec = MediaCodec.createDecoderByType(mime).also { codecRef = it }
        codec.configure(format, null, null, 0)
        codec.start()
        val out = BufferedOutputStream(FileOutputStream(output), 1 shl 16).also { outRef = it }
        var pcm = ShortArray(0)
        var mp3 = ByteArray(16 * 1024)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastPct = -1
            while (!outputDone) {
                if (stop()) throw NativeDownloader.Stopped()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)          // never wait here: the decoder is busy, so output is coming
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 2_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val count: Int
                        if (isFloat) {
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
                        val n = enc.encode(pcm, frames, channels.coerceAtLeast(1), mp3)
                        out.write(mp3, 0, n)
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            encoder?.let { enc ->
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                val n = enc.finish(mp3)
                out.write(mp3, 0, n)
            }
            out.flush()
        } finally {
            runCatching { outRef?.close() }
            runCatching { encoder?.close() }
            runCatching { codecRef?.stop() }
            runCatching { codecRef?.release() }
            runCatching { ex.release() }
        }
        check(output.length() > 0) { "MP3 conversion failed" }
    }
}
