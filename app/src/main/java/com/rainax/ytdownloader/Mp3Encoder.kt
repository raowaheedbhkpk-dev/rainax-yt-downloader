package com.rainax.ytdownloader

import com.rainax.lame.mp3.BitStream
import com.rainax.lame.mp3.GainAnalysis
import com.rainax.lame.mp3.GetAudio
import com.rainax.lame.mp3.ID3Tag
import com.rainax.lame.mp3.Lame
import com.rainax.lame.mp3.LameGlobalFlags
import com.rainax.lame.mp3.MPEGMode
import com.rainax.lame.mp3.Parse
import com.rainax.lame.mp3.Presets
import com.rainax.lame.mp3.Quantize
import com.rainax.lame.mp3.QuantizePVT
import com.rainax.lame.mp3.Reservoir
import com.rainax.lame.mp3.Takehiro
import com.rainax.lame.mp3.VBRTag
import com.rainax.lame.mp3.Version
import com.rainax.lame.mpg.Common
import com.rainax.lame.mpg.Interface
import com.rainax.lame.mpg.MPGLib

/**
 * MP3 encoder (LAME, pure Java/Kotlin, no native code). Takes 16-bit PCM and writes MP3 frames.
 * [bitrate] in kbit/s (constant bitrate, plays on every device and car stereo).
 */
class Mp3Encoder(channels: Int, sampleRate: Int, bitrate: Int = 192) {

    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val stereo = channels >= 2

    init {
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = if (stereo) 2 else 1
        gfp.in_samplerate = sampleRate
        gfp.brate = bitrate
        gfp.mode = if (stereo) MPEGMode.JOINT_STEREO else MPEGMode.MONO
        gfp.quality = 5                      // LAME's default balance of speed and quality
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        check(lame.lame_init_params(gfp) >= 0) { "MP3 encoder can't use this audio (${sampleRate} Hz)" }
    }

    private var left = IntArray(0)
    private var right = IntArray(0)

    /**
     * Encodes [frames] sample frames of interleaved 16-bit PCM from [pcm] (little endian, [inChannels] channels).
     * Returns the MP3 bytes written into [out].
     */
    fun encode(pcm: ShortArray, frames: Int, inChannels: Int, out: ByteArray): Int {
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
        }
        for (i in 0 until frames) {
            val l = pcm[i * inChannels].toInt()
            val r = if (inChannels > 1) pcm[i * inChannels + 1].toInt() else l
            left[i] = l shl 16
            right[i] = r shl 16
        }
        val n = lame.lame_encode_buffer_int(gfp, left, right, frames, out, 0, out.size)
        check(n >= 0) { "MP3 encoding failed ($n)" }
        return n
    }

    /** The last MP3 frames. */
    fun finish(out: ByteArray): Int = lame.lame_encode_flush(gfp, out, 0, out.size).coerceAtLeast(0)

    /** Enough room for the MP3 bytes of [frames] sample frames. */
    fun outSize(frames: Int) = (frames * 5 / 4) + 7200
}
