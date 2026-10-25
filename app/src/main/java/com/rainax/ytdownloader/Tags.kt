package com.rainax.ytdownloader

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Song information inside downloaded files: title, artist and cover picture. */
object Tags {

    /** "Artist - Topic" channels (YouTube Music) are just the artist. */
    fun artist(uploader: String): String = uploader.removeSuffix(" - Topic").trim()

    /**
     * Blocking. The video's picture as a JPEG cover. [square] = cropped to the middle square (album art);
     * null when it can't be loaded.
     */
    fun cover(url: String?, square: Boolean): ByteArray? {
        val bmp = url?.let { InfoFetcher.loadBitmap(it) } ?: return null
        return try {
            val img = if (square && bmp.width != bmp.height) {
                val side = minOf(bmp.width, bmp.height)
                Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            } else bmp
            ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** An ID3v2.3 tag for the start of an MP3 file (title, artist, cover). */
    fun id3(title: String, artist: String, cover: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            val n = body.size
            frames.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(), 0, 0))
            frames.write(body)
        }
        fun text(id: String, value: String) {
            if (value.isBlank()) return
            // UTF-16 with byte-order mark: any language (Urdu, Hindi, ...) shows right
            frame(id, byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE))
        }
        text("TIT2", title)
        text("TPE1", artist)
        text("TSSE", "RAINAX Tube")
        if (cover != null) {
            val head = ByteArrayOutputStream()
            head.write(0)                                            // text encoding of the fields below
            head.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            head.write(0)
            head.write(3)                                            // front cover
            head.write(0)                                            // no description
            frame("APIC", head.toByteArray() + cover)
        }
        val body = frames.toByteArray()
        val n = body.size
        // the size is "synchsafe": 7 bits per byte
        val size = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + size + body
    }
}
