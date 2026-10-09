package com.rainax.ytdownloader

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Fast joining of a picture-only MP4 and a sound-only MP4 (YouTube's HD files) into one normal MP4.
 *
 * Nothing is decoded and no frame is handled one by one (Android's MediaMuxer does that, which takes
 * minutes for a long video): the picture and sound data are copied in big pieces, and only a new index
 * (where each frame is) is written. The index comes first in the file, so the video opens and seeks at once.
 * A 2-hour video joins in about the time it takes to copy the file.
 *
 * Works for fragmented MP4s (YouTube) and normal MP4s, with any codec (H.264, AV1, VP9, AAC, Opus).
 * Anything else throws [NotSupported] before a single byte is written, and the caller uses MediaMuxer.
 */
internal object Mp4Joiner {

    class NotSupported(msg: String) : IOException(msg)

    private const val MOVIE_SCALE = 1000L
    private const val MAX_HEADER = 64 * 1024 * 1024L

    class Ints {
        var a = IntArray(4096)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        operator fun get(i: Int) = a[i]
    }

    /** Samples stored together in an input file; becomes one chunk of the output. */
    class Chunk(val offset: Long, val length: Long, val count: Int, val time: Double)

    /** One input file's track. */
    class Track(val file: File, val wanted: String) {
        var trackId = 0
        var tkhd = ByteArray(0)          // payload
        var timescale = 0L
        var language = 0x55C4            // "und"
        var hdlr = ByteArray(0)          // whole box
        var minfExtras = mutableListOf<ByteArray>()
        var stsd = ByteArray(0)          // whole box
        var movieScale = 1000L
        var elst: List<LongArray>? = null    // segment duration, media time, rate
        var defDur = 0; var defSize = 0; var defFlags = 0

        val durations = Ints()
        val sizes = Ints()
        val cts = Ints()
        val syncs = Ints()               // 1-based numbers of key frames
        val chunks = ArrayList<Chunk>()
        var dts = 0L
        var anyCts = false
        var negativeCts = false
        var end = 0L                     // last moment shown (decode time + display offset + duration)
        var fragmented = false
        var start = -1L                  // first fragment's start time (fragmented files)

        val count get() = sizes.n
        val seconds get() = if (timescale > 0) dts.toDouble() / timescale else 0.0

        fun add(dur: Int, size: Int, sync: Boolean, c: Int) {
            durations.add(dur); sizes.add(size); cts.add(c)
            if (c != 0) anyCts = true
            if (c < 0) negativeCts = true
            if (sync) syncs.add(sizes.n)
            val d = dur.toLong() and 0xFFFFFFFFL
            end = maxOf(end, dts + c + d)
            dts += d
        }
    }

    /** Ready to write: both files were read and understood. */
    class Job internal constructor(
        private val v: Track, private val a: Track,
        private val order: List<Pair<Track, Chunk>>, private val header: ByteArray, val size: Long
    ) {
        /** Writes the joined MP4 to [out]. [progress] gets 0..100. Throws [NativeDownloader.Stopped] when [stopped]. */
        fun write(out: OutputStream, stopped: () -> Boolean, progress: (Int) -> Unit) {
            out.write(header)
            var written = header.size.toLong()
            var lastPct = -1
            val buf = ByteArray(1 shl 20)
            RandomAccessFile(v.file, "r").use { vf ->
                RandomAccessFile(a.file, "r").use { af ->
                    for ((t, c) in order) {
                        val raf = if (t === v) vf else af
                        raf.seek(c.offset)
                        var left = c.length
                        while (left > 0) {
                            if (stopped()) throw NativeDownloader.Stopped()
                            val n = minOf(left, buf.size.toLong()).toInt()
                            raf.readFully(buf, 0, n)
                            out.write(buf, 0, n)
                            left -= n
                            written += n
                            val pct = (written * 100 / size).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
            out.flush()
        }
    }

    /** Reads both files (only their headers, fast). Throws [NotSupported] when they can't be joined this way. */
    fun prepare(video: File, audio: File): Job {
        val v = read(Track(video, "vide"))
        val a = read(Track(audio, "soun"))

        // picture and sound pieces in time order, so a player finds both close together
        val order = ArrayList<Pair<Track, Chunk>>(v.chunks.size + a.chunks.size)
        var i = 0
        var j = 0
        while (i < v.chunks.size || j < a.chunks.size) {
            val takeVideo = j >= a.chunks.size || (i < v.chunks.size && v.chunks[i].time <= a.chunks[j].time)
            order += if (takeVideo) v to v.chunks[i++] else a to a.chunks[j++]
        }
        // where each piece will be, counted from the start of the data
        val rel = HashMap<Chunk, Long>(order.size * 2)
        var data = 0L
        for ((_, c) in order) { rel[c] = data; data += c.length }

        val ftyp = W().apply {
            box("ftyp") { ascii("isom"); int(0x200); ascii("isom"); ascii("iso2"); ascii("mp41") }
        }.bytes()
        val mdatHead = if (data + 8 > 0xFFFFFFFFL) 16 else 8
        var co64 = false
        var moov = moov(v, a, rel, 0, co64)
        var base = ftyp.size + moov.size + mdatHead.toLong()
        if (base + data > 0xFFFFFFFFL) {
            co64 = true
            moov = moov(v, a, rel, 0, co64)
            base = ftyp.size + moov.size + mdatHead.toLong()
        }
        moov = moov(v, a, rel, base, co64)          // same size, real positions

        val head = W()
        head.raw(ftyp)
        head.raw(moov)
        if (mdatHead == 16) {
            head.int(1); head.ascii("mdat"); head.long(data + 16)
        } else {
            head.int((data + 8).toInt()); head.ascii("mdat")
        }
        val header = head.bytes()
        return Job(v, a, order, header, header.size + data)
    }

    // ---------- reading ----------

    private fun read(t: Track): Track {
        RandomAccessFile(t.file, "r").use { raf ->
            val len = raf.length()
            var pos = 0L
            var moovSeen = false
            val hdr = ByteArray(16)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(hdr, 0, 8)
                var size = u32(hdr, 0)
                val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                var hl = 8
                if (size == 1L) {
                    raf.readFully(hdr, 8, 8)
                    size = ByteBuffer.wrap(hdr, 8, 8).long
                    hl = 16
                } else if (size == 0L) {
                    size = len - pos
                }
                if (size < hl || pos + size > len) throw NotSupported("file cut short ($type)")
                when (type) {
                    "moov", "moof" -> {
                        if (size > MAX_HEADER) throw NotSupported("$type too big")
                        val bytes = ByteArray(size.toInt())
                        raf.seek(pos)
                        raf.readFully(bytes)
                        val bb = ByteBuffer.wrap(bytes)
                        if (type == "moov") {
                            moov(t, bb, hl, bytes.size)
                            moovSeen = true
                        } else {
                            if (!moovSeen) throw NotSupported("moof before moov")
                            moof(t, bb, hl, bytes.size, pos)
                        }
                    }
                }
                pos += size
            }
            if (!moovSeen || t.count == 0) throw NotSupported("no samples")
            if (t.timescale <= 0) throw NotSupported("no timescale")
            for (c in t.chunks) if (c.offset < 0 || c.offset + c.length > len) throw NotSupported("data outside the file")
        }
        return t
    }

    private class B(val type: String, val start: Int, val body: Int, val end: Int)

    private fun kids(b: ByteBuffer, from: Int, to: Int): List<B> {
        val list = ArrayList<B>()
        var p = from
        while (p + 8 <= to) {
            var size = b.getInt(p).toLong() and 0xFFFFFFFFL
            val type = fourcc(b, p + 4)
            var body = p + 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = b.getLong(p + 8)
                body = p + 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < body - p || p + size > to) throw NotSupported("broken box $type")
            list += B(type, p, body, (p + size).toInt())
            p += size.toInt()
        }
        return list
    }

    private fun List<B>.find(type: String) = firstOrNull { it.type == type }

    private fun fourcc(b: ByteBuffer, p: Int) = String(CharArray(4) { (b.get(p + it).toInt() and 0xFF).toChar() })

    private fun u32(a: ByteArray, p: Int): Long = ByteBuffer.wrap(a, p, 4).int.toLong() and 0xFFFFFFFFL

    private fun copy(b: ByteBuffer, box: B): ByteArray = ByteArray(box.end - box.start).also {
        val d = b.duplicate()
        d.position(box.start)
        d.get(it)
    }

    private fun moov(t: Track, b: ByteBuffer, from: Int, to: Int) {
        val top = kids(b, from, to)
        top.find("mvhd")?.let { m ->
            val v = b.get(m.body).toInt()
            t.movieScale = (b.getInt(m.body + if (v == 1) 20 else 12).toLong() and 0xFFFFFFFFL).coerceAtLeast(1)
        }
        var trak: List<B>? = null
        var mdia: List<B>? = null
        for (tr in top.filter { it.type == "trak" }) {
            val tk = kids(b, tr.body, tr.end)
            val md = tk.find("mdia")?.let { kids(b, it.body, it.end) } ?: continue
            val h = md.find("hdlr") ?: continue
            if (fourcc(b, h.body + 8) == t.wanted) { trak = tk; mdia = md; break }
        }
        if (trak == null || mdia == null) throw NotSupported("no ${t.wanted} track")

        val tkhd = trak.find("tkhd") ?: throw NotSupported("no tkhd")
        t.tkhd = ByteArray(tkhd.end - tkhd.body).also { val d = b.duplicate(); d.position(tkhd.body); d.get(it) }
        val tv = b.get(tkhd.body).toInt()
        t.trackId = b.getInt(tkhd.body + if (tv == 1) 20 else 12)

        trak.find("edts")?.let { e -> kids(b, e.body, e.end).find("elst") }?.let { el ->
            val ev = b.get(el.body).toInt()
            val n = b.getInt(el.body + 4)
            var p = el.body + 8
            val list = ArrayList<LongArray>()
            repeat(n) {
                if (p + (if (ev == 1) 20 else 12) > el.end) return@repeat
                val seg: Long
                val mt: Long
                if (ev == 1) { seg = b.getLong(p); mt = b.getLong(p + 8); p += 16 } else {
                    seg = b.getInt(p).toLong() and 0xFFFFFFFFL; mt = b.getInt(p + 4).toLong(); p += 8
                }
                list += longArrayOf(seg, mt, b.getInt(p).toLong())
                p += 4
            }
            if (list.isNotEmpty()) t.elst = list
        }

        val mdhd = mdia.find("mdhd") ?: throw NotSupported("no mdhd")
        val mv = b.get(mdhd.body).toInt()
        t.timescale = b.getInt(mdhd.body + if (mv == 1) 20 else 12).toLong() and 0xFFFFFFFFL
        t.language = b.getShort(mdhd.body + if (mv == 1) 32 else 20).toInt() and 0xFFFF
        t.hdlr = copy(b, mdia.find("hdlr")!!)

        val minf = mdia.find("minf")?.let { kids(b, it.body, it.end) } ?: throw NotSupported("no minf")
        t.minfExtras = minf.filter { it.type != "stbl" }.map { copy(b, it) }.toMutableList()
        val stbl = minf.find("stbl")?.let { kids(b, it.body, it.end) } ?: throw NotSupported("no stbl")
        val stsd = stbl.find("stsd") ?: throw NotSupported("no stsd")
        if (b.getInt(stsd.body + 4) != 1) throw NotSupported("several sample descriptions")
        t.stsd = copy(b, stsd)

        top.find("mvex")?.let { mx ->
            kids(b, mx.body, mx.end).filter { it.type == "trex" }.firstOrNull { b.getInt(it.body + 4) == t.trackId }?.let { x ->
                t.defDur = b.getInt(x.body + 12)
                t.defSize = b.getInt(x.body + 16)
                t.defFlags = b.getInt(x.body + 20)
            }
        }
        sampleTable(t, b, stbl)
    }

    /** A normal (not fragmented) MP4: the frames are listed in the header. */
    private fun sampleTable(t: Track, b: ByteBuffer, stbl: List<B>) {
        val stsz = stbl.find("stsz") ?: return
        val fixed = b.getInt(stsz.body + 4)
        val n = b.getInt(stsz.body + 8)
        if (n <= 0) return                                   // fragmented: the frames come in the moofs
        if (fixed == 0 && stsz.body + 12 + 4L * n > stsz.end) throw NotSupported("stsz")
        fun size(i: Int) = if (fixed != 0) fixed else b.getInt(stsz.body + 12 + 4 * i)

        val offsets = ArrayList<Long>()
        stbl.find("stco")?.let { c ->
            val k = b.getInt(c.body + 4)
            for (i in 0 until k) offsets += b.getInt(c.body + 8 + 4 * i).toLong() and 0xFFFFFFFFL
        } ?: stbl.find("co64")?.let { c ->
            val k = b.getInt(c.body + 4)
            for (i in 0 until k) offsets += b.getLong(c.body + 8 + 8 * i)
        } ?: throw NotSupported("no chunk offsets")

        val durs = IntArray(n)
        stbl.find("stts")?.let { s ->
            var k = 0
            for (e in 0 until b.getInt(s.body + 4)) {
                val cnt = b.getInt(s.body + 8 + 8 * e)
                val d = b.getInt(s.body + 12 + 8 * e)
                repeat(cnt) { if (k < n) durs[k++] = d }
            }
        }
        val ctsArr = IntArray(n)
        stbl.find("ctts")?.let { s ->
            var k = 0
            for (e in 0 until b.getInt(s.body + 4)) {
                val cnt = b.getInt(s.body + 8 + 8 * e)
                val o = b.getInt(s.body + 12 + 8 * e)
                repeat(cnt) { if (k < n) ctsArr[k++] = o }
            }
        }
        val sync = stbl.find("stss")?.let { s ->
            BooleanArray(n).also { arr ->
                for (e in 0 until b.getInt(s.body + 4)) {
                    val idx = b.getInt(s.body + 8 + 4 * e) - 1
                    if (idx in 0 until n) arr[idx] = true
                }
            }
        }
        val stsc = stbl.find("stsc") ?: throw NotSupported("no stsc")
        val runs = b.getInt(stsc.body + 4)
        fun runFirst(r: Int) = b.getInt(stsc.body + 8 + 12 * r)
        fun runCount(r: Int) = b.getInt(stsc.body + 12 + 12 * r)

        var sample = 0
        var r = 0
        for (c in offsets.indices) {
            while (r + 1 < runs && runFirst(r + 1) <= c + 1) r++
            val per = if (runs > 0) runCount(r) else 0
            if (per <= 0 || sample + per > n) throw NotSupported("stsc")
            val time = t.seconds
            var len = 0L
            for (k in sample until sample + per) {
                t.add(durs[k], size(k), sync?.get(k) ?: true, ctsArr[k])
                len += size(k).toLong() and 0xFFFFFFFFL
            }
            t.chunks += Chunk(offsets[c], len, per, time)
            sample += per
        }
        if (sample != n) throw NotSupported("sample count")
    }

    /** One fragment of a fragmented MP4 (YouTube): its frames, and where their data is. */
    private fun moof(t: Track, b: ByteBuffer, from: Int, to: Int, moofStart: Long) {
        var prevEnd = moofStart
        for (traf in kids(b, from, to).filter { it.type == "traf" }) {
            val tk = kids(b, traf.body, traf.end)
            val tfhd = tk.find("tfhd") ?: continue
            val flags = b.getInt(tfhd.body) and 0xFFFFFF
            var p = tfhd.body + 4
            val id = b.getInt(p); p += 4
            var base = if (flags and 0x20000 != 0) moofStart else prevEnd
            if (flags and 0x1 != 0) { base = b.getLong(p); p += 8 }
            if (flags and 0x2 != 0) p += 4
            var defDur = t.defDur
            var defSize = t.defSize
            var defFlags = t.defFlags
            if (flags and 0x8 != 0) { defDur = b.getInt(p); p += 4 }
            if (flags and 0x10 != 0) { defSize = b.getInt(p); p += 4 }
            if (flags and 0x20 != 0) { defFlags = b.getInt(p) }
            if (id == t.trackId) {
                t.fragmented = true
                tk.find("tfdt")?.let { d ->
                    val decode = if (b.get(d.body).toInt() == 1) b.getLong(d.body + 4) else b.getInt(d.body + 4).toLong() and 0xFFFFFFFFL
                    if (t.start < 0) {
                        t.start = decode
                        if (decode > 0 && t.elst != null) throw NotSupported("start offset with edit list")
                    } else if (kotlin.math.abs(decode - t.start - t.dts) > t.timescale / 10) {
                        throw NotSupported("gap in the stream")      // MediaMuxer keeps the timing right
                    }
                }
            }
            var next = base
            for (trun in tk.filter { it.type == "trun" }) {
                val tf = b.getInt(trun.body) and 0xFFFFFF
                var q = trun.body + 4
                val count = b.getInt(q); q += 4
                if (tf and 0x1 != 0) { next = base + b.getInt(q); q += 4 }
                var first: Int? = null
                if (tf and 0x4 != 0) { first = b.getInt(q); q += 4 }
                val per = listOf(0x100, 0x200, 0x400, 0x800).count { tf and it != 0 } * 4
                if (count < 0 || q + count.toLong() * per > trun.end) throw NotSupported("trun")
                if (id != t.trackId) {
                    // another track's frames: only skip over their data
                    if (tf and 0x200 != 0) {
                        val sizeAt = if (tf and 0x100 != 0) 4 else 0
                        for (i in 0 until count) next += b.getInt(q + i * per + sizeAt).toLong() and 0xFFFFFFFFL
                    } else {
                        next += count.toLong() * (defSize.toLong() and 0xFFFFFFFFL)
                    }
                    continue
                }
                val time = t.seconds + maxOf(t.start, 0L).toDouble() / t.timescale
                var len = 0L
                for (i in 0 until count) {
                    val dur = if (tf and 0x100 != 0) b.getInt(q).also { q += 4 } else defDur
                    val size = if (tf and 0x200 != 0) b.getInt(q).also { q += 4 } else defSize
                    val fl = if (tf and 0x400 != 0) b.getInt(q).also { q += 4 } else if (i == 0 && first != null) first else defFlags
                    val c = if (tf and 0x800 != 0) b.getInt(q).also { q += 4 } else 0
                    // sound: every frame starts cleanly; picture: the "not a key frame" flag
                    val sync = t.wanted == "soun" || (fl shr 16) and 1 == 0
                    t.add(dur, size, sync, c)
                    len += size.toLong() and 0xFFFFFFFFL
                }
                if (count > 0) t.chunks += Chunk(next, len, count, time)
                next += len
            }
            prevEnd = next
        }
    }

    // ---------- writing the index ----------

    private class W {
        private val buf = ByteArrayOutputStream()
        val d = DataOutputStream(buf)
        fun bytes(): ByteArray = buf.toByteArray()
        fun int(v: Int) = d.writeInt(v)
        fun long(v: Long) = d.writeLong(v)
        fun short(v: Int) = d.writeShort(v)
        fun ascii(s: String) = d.writeBytes(s)
        fun raw(b: ByteArray) = d.write(b)
        fun box(type: String, body: W.() -> Unit) {
            val inner = W().apply(body).bytes()
            int(inner.size + 8); ascii(type); raw(inner)
        }
        fun full(type: String, version: Int, flags: Int, body: W.() -> Unit) =
            box(type) { int((version shl 24) or flags); body() }
    }

    private fun mediaDuration(t: Track) = t.dts

    private fun toMovie(value: Long, scale: Long) = if (scale <= 0) 0 else value * MOVIE_SCALE / scale

    /** The edit list in the new movie time scale (keeps the start offset of AAC sound and B-frame video). */
    private fun edits(t: Track): List<LongArray>? {
        val media = maxOf(t.end, mediaDuration(t))
        // a fragmented file whose first frame starts later: wait that long (an empty edit), then play it all
        if (t.elst == null && t.start > 0) {
            return listOf(longArrayOf(toMovie(t.start, t.timescale), -1, 0x10000), longArrayOf(toMovie(media, t.timescale), 0, 0x10000))
        }
        val list = t.elst ?: return null
        if (list.size == 1 && list[0][1] >= 0) {
            val mt = list[0][1]
            var seg = toMovie((media - mt).coerceAtLeast(0), t.timescale)
            // a normal MP4's own length can cut the end (the silent padding of AAC sound): keep that cut
            if (!t.fragmented && list[0][0] > 0) seg = minOf(seg, toMovie(list[0][0], t.movieScale))
            return listOf(longArrayOf(seg, mt, list[0][2]))
        }
        var used = 0L
        return list.map { e ->
            val seg = when {
                e[1] < 0 -> toMovie(e[0], t.movieScale)
                e[0] == 0L -> toMovie((media - e[1] - used).coerceAtLeast(0), t.timescale)
                else -> toMovie(e[0], t.movieScale)
            }
            if (e[1] >= 0) used += seg * t.timescale / MOVIE_SCALE
            longArrayOf(seg, e[1], e[2])
        }
    }

    private fun trackDuration(t: Track): Long =
        edits(t)?.sumOf { it[0] } ?: toMovie(mediaDuration(t), t.timescale)

    private fun moov(v: Track, a: Track, rel: Map<Chunk, Long>, base: Long, co64: Boolean): ByteArray {
        val w = W()
        val dur = maxOf(trackDuration(v), trackDuration(a))
        w.box("moov") {
            full("mvhd", 1, 0) {
                long(0); long(0); int(MOVIE_SCALE.toInt()); long(dur)
                int(0x10000); short(0x100); short(0); int(0); int(0)
                intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach { int(it) }
                repeat(6) { int(0) }
                int(3)
            }
            trak(this, v, 1, rel, base, co64)
            trak(this, a, 2, rel, base, co64)
        }
        return w.bytes()
    }

    private fun tkhd(t: Track, id: Int): ByteArray {
        val p = t.tkhd.copyOf()
        val bb = ByteBuffer.wrap(p)
        val v = p[0].toInt()
        p[1] = 0; p[2] = 0; p[3] = 3                       // enabled, in the movie
        val dur = trackDuration(t)
        if (v == 1 && p.size >= 36) {
            bb.putInt(20, id); bb.putLong(28, dur)
        } else if (p.size >= 24) {
            bb.putInt(12, id); bb.putInt(20, dur.coerceAtMost(0xFFFFFFFFL).toInt())
        }
        return p
    }

    private fun trak(w: W, t: Track, id: Int, rel: Map<Chunk, Long>, base: Long, co64: Boolean) = w.box("trak") {
        box("tkhd") { raw(tkhd(t, id)) }
        edits(t)?.let { list ->
            box("edts") {
                full("elst", 1, 0) {
                    int(list.size)
                    list.forEach { long(it[0]); long(it[1]); int(it[2].toInt()) }
                }
            }
        }
        box("mdia") {
            full("mdhd", 1, 0) { long(0); long(0); int(t.timescale.toInt()); long(mediaDuration(t)); short(t.language); short(0) }
            raw(t.hdlr)
            box("minf") {
                t.minfExtras.forEach { raw(it) }
                if (t.minfExtras.none { String(it, 4, 4, Charsets.ISO_8859_1) == "dinf" }) {
                    box("dinf") { full("dref", 0, 0) { int(1); full("url ", 0, 1) { } } }
                }
                box("stbl") { table(this, t, rel, base, co64) }
            }
        }
    }

    /** Equal values in a row as (count, value) pairs. */
    private fun runs(values: Ints, n: Int): Ints {
        val out = Ints()
        var i = 0
        while (i < n) {
            var k = i + 1
            while (k < n && values[k] == values[i]) k++
            out.add(k - i)
            out.add(values[i])
            i = k
        }
        return out
    }

    private fun table(w: W, t: Track, rel: Map<Chunk, Long>, base: Long, co64: Boolean) = with(w) {
        val n = t.count
        raw(t.stsd)
        // durations
        val stts = runs(t.durations, n)
        full("stts", 0, 0) { int(stts.n / 2); for (r in 0 until stts.n) int(stts[r]) }
        // display time offsets (B-frames)
        if (t.anyCts) {
            val ctts = runs(t.cts, n)
            full("ctts", if (t.negativeCts) 1 else 0, 0) { int(ctts.n / 2); for (r in 0 until ctts.n) int(ctts[r]) }
        }
        // key frames (left out when every frame is one)
        if (t.syncs.n < n) full("stss", 0, 0) { int(t.syncs.n); for (s in 0 until t.syncs.n) int(t.syncs[s]) }
        // sizes
        val same = (1 until n).all { t.sizes[it] == t.sizes[0] }
        full("stsz", 0, 0) {
            if (same) { int(t.sizes[0]); int(n) } else { int(0); int(n); for (s in 0 until n) int(t.sizes[s]) }
        }
        // frames per chunk
        val stsc = ArrayList<IntArray>()
        t.chunks.forEachIndexed { c, ch -> if (stsc.isEmpty() || stsc.last()[1] != ch.count) stsc += intArrayOf(c + 1, ch.count) }
        full("stsc", 0, 0) { int(stsc.size); stsc.forEach { int(it[0]); int(it[1]); int(1) } }
        // where each chunk is in the new file
        if (co64) {
            full("co64", 0, 0) { int(t.chunks.size); t.chunks.forEach { long(base + rel.getValue(it)) } }
        } else {
            full("stco", 0, 0) { int(t.chunks.size); t.chunks.forEach { int((base + rel.getValue(it)).toInt()) } }
        }
    }
}
