package com.rainax.ytdownloader

/**
 * MP3 encoder: LAME (LGPL) compiled for the phone (app/src/main/cpp), many times faster than a Java encoder.
 * Takes interleaved 16-bit PCM and writes constant-bitrate MP3 frames ([bitrate] in kbit/s),
 * which play on every device and car stereo. Call [close] when done.
 */
class Mp3Encoder(channels: Int, sampleRate: Int, bitrate: Int = 192) : AutoCloseable {

    private var handle: Long = nativeInit(channels, sampleRate, bitrate, QUALITY)

    init {
        check(handle != 0L) { "MP3 encoder can't use this audio ($sampleRate Hz)" }
    }

    /** Encodes [frames] sample frames from [pcm] ([inChannels] channels). Returns the MP3 bytes written into [out]. */
    fun encode(pcm: ShortArray, frames: Int, inChannels: Int, out: ByteArray): Int {
        check(handle != 0L) { "MP3 encoder is closed" }
        val n = nativeEncode(handle, pcm, frames, inChannels, out)
        check(n >= 0) { "MP3 encoding failed ($n)" }
        return n
    }

    /** The last MP3 frames. */
    fun finish(out: ByteArray): Int = if (handle == 0L) 0 else nativeFlush(handle, out).coerceAtLeast(0)

    /** Enough room for the MP3 bytes of [frames] sample frames (LAME's own rule: 1.25 x samples + 7200). */
    fun outSize(frames: Int) = (frames * 5 / 4) + 7200

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        /** LAME quality 5: its standard speed/quality balance (0 = slowest, 9 = fastest). */
        private const val QUALITY = 5

        init { System.loadLibrary("rainaxlame") }

        @JvmStatic private external fun nativeInit(channels: Int, sampleRate: Int, bitrate: Int, quality: Int): Long
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, inChannels: Int, out: ByteArray): Int
        @JvmStatic private external fun nativeFlush(handle: Long, out: ByteArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
