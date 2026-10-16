package com.rainax.ytdownloader

/**
 * Opus decoder inside the app (libopus, BSD licence, built into librainaxlame). For converting it is many times faster
 * than Android's decoders, which run in a separate system process and pass every 20 ms of sound there and back.
 * Always 48 kHz; 1 or 2 channels. Call [close] when done.
 */
class OpusDecoder(val channels: Int) : AutoCloseable {

    private var handle: Long = nativeOpen(channels)

    init {
        check(handle != 0L) { "Opus decoder can't open ($channels channels)" }
    }

    /** Decodes one packet ([len] bytes from position 0 of the direct buffer [packet]) into [out]. Returns sample frames. */
    fun decode(packet: java.nio.ByteBuffer, len: Int, out: ShortArray): Int {
        check(handle != 0L) { "Opus decoder is closed" }
        val n = nativeDecode(handle, packet, len, out)
        check(n >= 0) { "Opus decoding failed ($n)" }
        return n
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        /** Longest Opus packet: 120 ms at 48 kHz. */
        const val MAX_FRAMES = 5760

        init { System.loadLibrary("rainaxlame") }

        @JvmStatic private external fun nativeOpen(channels: Int): Long
        @JvmStatic private external fun nativeDecode(handle: Long, packet: java.nio.ByteBuffer, len: Int, out: ShortArray): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
