package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.lang.ref.WeakReference

/**
 * Autoplay in feed, like YouTube: when the list stops scrolling, the video most in view plays silently inside its
 * picture (with a speaker button and a thin progress bar, and the time left on the duration badge). It stops when
 * the card scrolls away, a video or song plays, the app goes to the background, or Settings turn it off.
 * One small player (about 360p) is shared by every list. Everything here runs on the main thread.
 */
/** A card whose picture can show the preview (a big video row of a list). */
interface PreviewCard {
    /** The picture's frame: the playing video is put inside it. */
    val thumbBox: ViewGroup
    /** The video this card shows now. */
    val item: VideoItem?
    /** Normal videos only (not playlists, channels, Shorts or live streams). */
    val canPreview: Boolean
    /** The time on the duration badge (the preview shows the time left in it). */
    var badgeText: CharSequence
}

/** A list of cards that can play their videos while it rests (a RecyclerView or a Compose list). */
interface PreviewList {
    val view: View
    val idle: Boolean
    val padTop: Int
    val padBottom: Int
    fun cards(): List<PreviewCard>
}

/** A RecyclerView of [VideoAdapter] rows. */
private class RecyclerPreviewList(val rv: RecyclerView) : PreviewList {
    override val view: View get() = rv
    override val idle get() = rv.scrollState == RecyclerView.SCROLL_STATE_IDLE
    override val padTop get() = rv.paddingTop
    override val padBottom get() = rv.paddingBottom
    override fun cards(): List<PreviewCard> =
        (0 until rv.childCount).mapNotNull { rv.getChildViewHolder(rv.getChildAt(it)) as? VideoAdapter.Row }
}

/**
 * A Compose list (Home's feed): its video cards register themselves while they are on screen, and the list
 * tells when it scrolls and rests.
 */
class ComposePreviewList(override val view: View) : PreviewList {
    private val shown = LinkedHashSet<PreviewCard>()
    override var idle = true
        private set
    override val padTop get() = 0
    override var padBottom = 0
    override fun cards(): List<PreviewCard> = shown.filter { it.thumbBox.isAttachedToWindow }

    fun add(c: PreviewCard) { shown += c; FeedAutoplay.listChanged(this) }
    fun remove(c: PreviewCard) { shown -= c; FeedAutoplay.listChanged(this) }

    /** Called as the list moves ([scrolling] = true) and once when it rests. */
    fun setScrolling(scrolling: Boolean) {
        val wasIdle = idle
        idle = !scrolling
        if (scrolling) FeedAutoplay.listScrolled(this) else if (!wasIdle) FeedAutoplay.listChanged(this)
    }
}

@OptIn(UnstableApi::class)
object FeedAutoplay {

    /** Set by the main screen: true while something covers the lists (a video page, the music player...). */
    var blocked: () -> Boolean = { false }

    private const val START_DELAY_MS = 650L
    private const val MIN_SHOWN = 0.6f        // a card needs this much of its picture on screen to start
    private const val KEEP_SHOWN = 0.4f       // ... and keeps playing until less than this is left

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lists = mutableListOf<WeakReference<PreviewList>>()

    private var app: Context? = null
    private var player: ExoPlayer? = null
    private var preview: View? = null
    private var previewPlayer: PlayerView? = null
    private var muteBtn: ImageButton? = null
    private var bar: ProgressBar? = null

    private var host: PreviewCard? = null
    private var hostList: PreviewList? = null
    private var url: String? = null
    private var savedBadge: CharSequence? = null
    private var job: Job? = null
    /** The playing stream came from the one-request quick look-up (on an error the full look-up is tried). */
    private var quick = false
    private var resumed = false
    private var muted = true

    /** Videos that ended or failed: not started again by themselves. */
    private val skip = LinkedHashSet<String>()
    /** Where the last preview was (so opening the video carries on from there). */
    private var lastUrl: String? = null
    private var lastPos = 0L

    private val pick = Runnable { pickNow() }
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (url == null) return
            if (!allowed()) { stop(); return }
            val dur = p.duration
            val pos = p.currentPosition
            if (dur > 0) {
                bar?.progress = (pos * 1000 / dur).toInt()
                host?.badgeText = YtCatalog.duration(((dur - pos) / 1000).coerceAtLeast(0))
            }
            if (p.isPlaying) { lastUrl = url; lastPos = pos }
            main.postDelayed(this, 500)
        }
    }

    /** Lets [rv] (a list of big video cards) play its videos while it rests. */
    fun attach(rv: RecyclerView) {
        if (app == null) app = rv.context.applicationContext
        lists.removeAll { it.get() == null || (it.get() as? RecyclerPreviewList)?.rv === rv }
        val list = RecyclerPreviewList(rv)
        lists += WeakReference(list)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) schedule() else main.removeCallbacks(pick)
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (hostList === list) verify()
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) schedule()      // moved by the app
            }
        })
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) schedule()          // new videos shown
            }

            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        // rows are bound again after list changes: keep the video with its card
        rv.viewTreeObserver.addOnGlobalLayoutListener { if (hostList === list) verify() }
    }

    /** A Compose list (see [ComposePreviewList]). */
    fun attach(list: ComposePreviewList) {
        if (app == null) app = list.view.context.applicationContext
        lists.removeAll { it.get() == null || it.get() === list }
        lists += WeakReference(list)
    }

    /** The Compose list moved: follow (or stop) the playing card; no new choice while it scrolls. */
    internal fun listScrolled(list: ComposePreviewList) {
        main.removeCallbacks(pick)
        if (hostList === list) verify()
    }

    /** The Compose list rests, or its cards changed: choose again a moment later. */
    internal fun listChanged(list: ComposePreviewList) {
        if (hostList === list) verify()
        if (list.idle) schedule()
    }

    /** Something changed (tab, video page, settings): stop or start as needed. */
    fun refresh() {
        if (url != null && (!allowed() || hostList?.view?.isShown != true)) stop()
        schedule()
    }

    fun onResume() {
        resumed = true
        schedule()
    }

    fun onPause() {
        resumed = false
        stop()
    }

    /** Where the preview of [page] was, so the video page starts there (0 = from the start). */
    fun startFor(page: String): Long {
        val same = lastUrl?.let { youtubeId(it) } == youtubeId(page) && youtubeId(page) != null
        return if (same && lastPos > 1500) lastPos else 0L
    }

    fun release() {
        stop()
        main.removeCallbacks(pick)
        player?.release()
        player = null
        previewPlayer?.player = null
        preview = null
        previewPlayer = null
        muteBtn = null
        bar = null
        lists.clear()
        app = null
        blocked = { false }
    }

    // ---------- choosing ----------

    private fun schedule() {
        main.removeCallbacks(pick)
        if (resumed) main.postDelayed(pick, START_DELAY_MS)
    }

    private fun allowed(): Boolean {
        val c = app ?: return false
        if (!resumed || blocked()) return false
        if (MusicQueue.player?.isPlaying == true) return false      // a video or song is playing
        return when (AppPrefs.feedAutoplay(c)) {
            0 -> false
            1 -> runCatching {
                val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
                cm != null && !cm.isActiveNetworkMetered
            }.getOrDefault(false)
            else -> true
        }
    }

    /** How much of [box] (0..1) is on screen inside the list (not under its top or bottom padding). */
    private fun shown(list: PreviewList, box: View): Float {
        if (!box.isShown || box.height == 0) return 0f
        val r = Rect()
        if (!box.getGlobalVisibleRect(r)) return 0f
        val area = Rect()
        if (!list.view.getGlobalVisibleRect(area)) return 0f
        area.top += list.padTop
        area.bottom -= list.padBottom
        if (!r.intersect(area)) return 0f
        return r.height().toFloat() / box.height
    }

    private fun pickNow() {
        if (!allowed()) { stop(); return }
        val rv = lists.mapNotNull { it.get() }.firstOrNull { it.view.isShown && it.view.isAttachedToWindow } ?: run { stop(); return }
        if (!rv.idle) return
        // the topmost card that is fully in view, else the one most in view
        val seen = rv.cards().filter { it.canPreview }.map { it to shown(rv, it.thumbBox) }.filter { it.second >= MIN_SHOWN }
        fun fresh(r: PreviewCard) = r.item?.url?.let { it !in skip } == true
        fun top(r: PreviewCard): Int { val a = IntArray(2); r.thumbBox.getLocationInWindow(a); return a[1] }
        val best = seen.filter { it.second >= 0.95f && fresh(it.first) }.minByOrNull { top(it.first) }?.first
            ?: seen.filter { fresh(it.first) }.maxByOrNull { it.second }?.first
        val now = url
        if (now != null && hostList === rv && seen.any { it.first.item?.url == now }) {
            // keep the playing one unless a fully shown card is clearly the one you stopped on
            val cur = seen.first { it.first.item?.url == now }
            if (best == null || cur.second >= 0.95f || best.item?.url == now) return
        }
        if (best == null) { stop(); return }
        start(rv, best)
    }

    // ---------- playing ----------

    private fun ensurePlayer(c: Context): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(c)
            .setMediaSourceFactory(RxMediaSourceFactory())
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 20_000, 700, 1_500).build())
            .build()
        p.volume = if (muted) 0f else 1f
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                preview?.animate()?.alpha(1f)?.setDuration(220)?.start()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    url?.let { skip += it }
                    lastPos = 0L
                    stop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val u = url ?: return
                if (quick) load(u, full = true)          // the quick addresses didn't open: the full look-up
                else { skip += u; stop() }
            }
        })
        player = p
        return p
    }

    private fun ensureView(c: Context): View {
        preview?.let { return it }
        val v = LayoutInflater.from(c).inflate(R.layout.view_feed_preview, null, false)
        previewPlayer = v.findViewById(R.id.previewPlayer)
        muteBtn = v.findViewById(R.id.previewMute)
        bar = v.findViewById(R.id.previewBar)
        muteBtn?.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            showMute()
        }
        preview = v
        return v
    }

    private fun showMute() {
        muteBtn?.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        muteBtn?.contentDescription = if (muted) "Turn sound on" else "Turn sound off"
    }

    /** Puts the preview inside [row]'s picture (above the picture, under the duration badge). */
    private fun place(row: PreviewCard) {
        val v = preview ?: return
        val box = row.thumbBox
        if (v.parent === box) return
        (v.parent as? ViewGroup)?.removeView(v)
        box.addView(v, 1.coerceAtMost(box.childCount), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun start(rv: PreviewList, row: PreviewCard) {
        val item = row.item ?: return
        val c = app ?: return
        stop()
        val p = ensurePlayer(c)
        val v = ensureView(rv.view.context)
        url = item.url
        host = row
        hostList = rv
        savedBadge = row.badgeText
        v.alpha = 0f
        bar?.progress = 0
        showMute()
        previewPlayer?.player = p
        place(row)
        load(item.url, full = false)
    }

    /** Looks up [want]'s stream (quick first, [full] = the full look-up) and plays it. */
    private fun load(want: String, full: Boolean) {
        val p = player ?: return
        job?.cancel()
        job = scope.launch {
            var fromQuick = false
            val src = withContext(Dispatchers.IO) {
                (if (full) null else runCatching { quickSource(want) }.getOrNull()?.also { fromQuick = true })
                    ?: runCatching { source(want) }.getOrNull()
            }
            if (url != want) return@launch
            if (src == null) {
                skip += want
                stop()
                return@launch
            }
            quick = fromQuick
            p.setMediaItem(MediaItem.Builder().setUri(src).setMediaId(want).build())
            p.prepare()
            p.playWhenReady = true
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    /** The card moved or was bound again: follow it, or stop when it left the screen. */
    private fun verify() {
        val rv = hostList ?: return
        val u = url ?: return
        if (!rv.view.isShown) { stop(); return }
        var row = host
        val cards = rv.cards()
        if (row == null || row.item?.url != u || !row.thumbBox.isAttachedToWindow || row !in cards) {
            row = cards.firstOrNull { it.item?.url == u && it.canPreview }
            if (row == null) { stop(); return }
            host = row
            savedBadge = row.badgeText
            place(row)
        }
        if (shown(rv, row.thumbBox) < KEEP_SHOWN) stop()
    }

    fun stop() {
        job?.cancel()
        job = null
        main.removeCallbacks(tick)
        val u = url
        url = null
        player?.let {
            if (u != null && it.currentPosition > 0 && it.playbackState != Player.STATE_ENDED) { lastUrl = u; lastPos = it.currentPosition }
            it.stop()
            it.clearMediaItems()
        }
        preview?.let { v ->
            v.animate().cancel()
            v.alpha = 0f
            (v.parent as? ViewGroup)?.removeView(v)
        }
        host?.let { h -> if (u != null && h.item?.url == u) savedBadge?.let { h.badgeText = it } }
        host = null
        hostList = null
        savedBadge = null
        if (skip.size > 200) skip.remove(skip.first())
    }

    // ---------- the stream ----------

    /**
     * Blocking. The same small streams from one quick request (YouTube's answer to the visionOS app), so the
     * preview starts in about a second. Null when that answer has no direct addresses.
     */
    private fun quickSource(page: String): Uri? {
        val id = youtubeId(page) ?: return null
        val json = FastExtractor.visionOsJson(id) ?: return null
        if (json.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val details = json.optJSONObject("videoDetails")
        if (details?.optString("videoId") != id || details.optBoolean("isLive")) return null
        val sd = json.optJSONObject("streamingData") ?: return null
        var video: org.json.JSONObject? = null
        var audio: org.json.JSONObject? = null
        val adaptive = sd.optJSONArray("adaptiveFormats")
        for (i in 0 until (adaptive?.length() ?: 0)) {
            val f = adaptive!!.optJSONObject(i) ?: continue
            if (f.optString("url").isBlank()) continue
            val mime = f.optString("mimeType")
            if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                val h = f.optInt("height")
                if (h in 1..480 && (video == null || kotlin.math.abs(h - 360) < kotlin.math.abs(video.optInt("height") - 360))) video = f
            } else if (mime.startsWith("audio/mp4")) {
                if (f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault", true) == false) continue    // dubbed
                if (audio == null || f.optInt("bitrate") < audio.optInt("bitrate")) audio = f
            }
        }
        if (video != null && audio != null) {
            return Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", video.optString("url")).appendQueryParameter("a", audio.optString("url"))
                .appendQueryParameter("u", FastExtractor.videoUrl(page)).build()
        }
        val muxed = sd.optJSONArray("formats")
        var best: org.json.JSONObject? = null
        for (i in 0 until (muxed?.length() ?: 0)) {
            val f = muxed!!.optJSONObject(i) ?: continue
            if (f.optString("url").isBlank() || !f.optString("mimeType").startsWith("video/")) continue
            if (best == null || f.optInt("height") < best.optInt("height")) best = f
        }
        return best?.let { Uri.parse(it.optString("url")) }
    }

    /** Blocking. A small (about 360p) H.264 picture with the lightest sound, else one small file with both. */
    @Suppress("DEPRECATION")
    private fun source(page: String): Uri? {
        val info = FastExtractor.streamInfo(page)
        if (info.streamType != StreamType.VIDEO_STREAM) return null
        fun ok(s: org.schabi.newpipe.extractor.stream.Stream) =
            s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !s.content.isNullOrBlank()
        fun h(v: VideoStream) = v.height.takeIf { it > 0 } ?: v.resolution.substringBefore('p').toIntOrNull() ?: 0
        val video = info.videoOnlyStreams.orEmpty()
            .filter { ok(it) && it.format?.suffix == "mp4" && it.codec.orEmpty().lowercase().let { c -> c.isEmpty() || c.startsWith("avc") } }
            .filter { h(it) in 1..480 }
            .minByOrNull { kotlin.math.abs(h(it) - 360) }
        val audios = info.audioStreams.orEmpty().filter { ok(it) && it.format?.suffix == "m4a" }
        val audio = audios.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audios }
            .minByOrNull { if (it.averageBitrate > 0) it.averageBitrate * 1000 else it.bitrate }
        if (video != null && audio != null) {
            return Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", video.content).appendQueryParameter("a", audio.content)
                .appendQueryParameter("u", FastExtractor.videoUrl(page)).build()
        }
        val muxed = info.videoStreams.orEmpty().filter { ok(it) }.minByOrNull { h(it) } ?: return null
        return Uri.parse(muxed.content)
    }
}
