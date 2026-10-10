package com.rainax.ytdownloader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.ItemVideoHeaderBinding
import com.rainax.ytdownloader.databinding.PageVideoBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Video page: the in-app player (it plays in BgPlayService, so the sound can continue in the background),
 * Download and audio buttons, comments and related videos (each with its own Download button).
 */
@OptIn(UnstableApi::class)
class VideoScreen(
    private val act: AppCompatActivity,
    private val vb: PageVideoBinding,
    private val vm: MainViewModel,
    private val player: () -> Player?,
    private val download: (url: String, title: String?, audio: Boolean) -> Unit,
    private val openChannel: (url: String, name: String?, avatar: String?) -> Unit,
    private val signIn: () -> Unit,
    private val onChanged: () -> Unit
) {
    private val header = ItemVideoHeaderBinding.inflate(act.layoutInflater)
    private val related = VideoAdapter(true, { open(it.url, it.title, it.uploader, thumb = it.thumb) }, { download(it.url, it.title, false) })

    /** The video shown (null when the page is closed). */
    var url: String? = null
        private set
    private var details: VideoDetails? = null
    private var job: Job? = null
    private var commentsJob: Job? = null

    var fullscreen = false
        private set

    val isOpen get() = url != null

    fun setup() {
        vb.videoList.layoutManager = LinearLayoutManager(act)
        related.header = header.root
        vb.videoList.adapter = related
        vb.videoClose.setOnClickListener { minimize() }
        vb.playerView.setFullscreenButtonClickListener { full -> setFullscreen(full) }
        header.vDownload.setOnClickListener { url?.let { download(it, currentTitle(), false) } }
        header.vShare.setOnClickListener { share() }
        header.vCopy.setOnClickListener { copyLink() }
        vb.videoSettings.setOnClickListener { showPlayerMenu() }
        // the top buttons (close, quality) appear and hide together with the player controls
        vb.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> vb.playerTop.visibility = v })
        header.vDesc.setOnClickListener { showDescription() }
        header.vMeta.setOnClickListener { showDescription() }
        header.vComments.setOnClickListener { showComments() }
        header.vChannelRow.setOnClickListener {
            val d = details ?: return@setOnClickListener
            d.channelUrl?.let { openChannel(it, d.uploader, d.avatar) }
        }
        header.vSubscribe.setOnClickListener { toggleSubscribe() }
        header.vLike.setOnClickListener {
            if (!YtAccount.isSignedIn(act)) signIn() else rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE")
        }
        header.vDislike.setOnClickListener {
            if (!YtAccount.isSignedIn(act)) signIn() else rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE")
        }
        header.vChapters.setOnClickListener { showChapters() }
        header.vSave.setOnClickListener { saveWatchLater() }
        vb.vNextBar.setOnClickListener { showUpNext() }
        // + on Up next videos: pick several and download them together (the same picks as on Home)
        related.onPick = { Picks.toggle(it); if (Picks.has(it.url)) vm.warm(it.url) }
        related.isPicked = { Picks.has(it) }
        Picks.listen { showPicks() }
        vb.vPickClear.setOnClickListener { Picks.clear() }
        vb.vPickCount.setOnClickListener { PicksSheet.show(act) { downloadMany(it) } }
        vb.vPickDownload.setOnClickListener {
            val list = Picks.all()
            if (list.isEmpty()) return@setOnClickListener
            Picks.clear()
            downloadMany(list)
        }
        vb.playerUnlock.setOnClickListener { setLocked(false) }
    }

    /** Download for the videos picked with + (set by the main screen). */
    var downloadMany: (List<VideoItem>) -> Unit = {}

    private fun showPicks() {
        val n = Picks.size
        vb.vPickBar.isVisible = n > 0 && !fullscreen
        vb.vPickCount.text = if (n == 1) "1 selected · View" else "$n selected · View"
        vb.vPickDownload.text = if (n > 1) "Download $n" else "Download"
        if (n > 0) vb.vNextBar.isVisible = false else showNextBar(details)
        related.notifyDataSetChanged()
    }

    // ---------- Next / Mix bar and the Up next list ----------

    /** Bottom bar: "Next: <video>" and "Mix - <this video>" (tap for the whole list). */
    private fun showNextBar(d: VideoDetails?) {
        val next = d?.related?.firstOrNull { it.seconds > 0 }
        vb.vNextBar.isVisible = next != null && !fullscreen && Picks.size == 0
        if (next == null || d == null) return
        vb.vNextTitle.text = "Next: " + next.title
        vb.vNextFrom.text = "Mix - " + d.title
    }

    private fun showUpNext() {
        val d = details ?: return
        if (d.related.isEmpty()) return
        val dialog = BottomSheetDialog(act)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val dp = act.resources.displayMetrics.density
        val head = android.widget.TextView(act).apply {
            text = "Mix - " + d.title
            setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.rx_text))
            textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((18 * dp).toInt(), (18 * dp).toInt(), (18 * dp).toInt(), (2 * dp).toInt())
        }
        val sub = android.widget.TextView(act).apply {
            text = "Mixes are playlists made for you"
            setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.rx_text2))
            textSize = 13f
            setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), (10 * dp).toInt())
        }
        val list = androidx.recyclerview.widget.RecyclerView(act)
        list.layoutManager = LinearLayoutManager(act)
        val rows = VideoAdapter(false, {
            dialog.dismiss()
            open(it.url, it.title, it.uploader, thumb = it.thumb)
        }, { download(it.url, it.title, false) })
        rows.onPick = { Picks.toggle(it); if (Picks.has(it.url)) vm.warm(it.url); rows.notifyDataSetChanged() }
        rows.isPicked = { Picks.has(it) }
        list.adapter = rows
        rows.submit(d.related.filter { !it.isPlaylist && !it.isChannel })
        box.addView(head)
        box.addView(sub)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (act.resources.displayMetrics.heightPixels * 0.6).toInt()))
        dialog.setContentView(box)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    // ---------- Lock screen ----------

    private var locked = false

    /** Lock screen: the player ignores touches (for kids, or in a pocket) until Unlock is tapped. */
    private fun setLocked(on: Boolean) {
        locked = on
        vb.playerLock.isVisible = on
        if (on) {
            vb.playerView.hideController()
            vb.playerView.useController = false
            vb.playerTop.visibility = View.GONE
        } else {
            vb.playerView.useController = true
            vb.playerView.showController()
        }
    }

    // ---------- account actions (subscribe, like, Watch later) ----------

    private var subscribed: Boolean? = null
    private var likeStatus: String? = null
    private var stateJob: Job? = null

    private fun videoId(u: String?): String? = u?.let { Regex("(?:[?&]v=|shorts/)([\\w-]{6,})").find(it)?.groupValues?.get(1) }

    /** Shows Subscribe (and Like / Dislike / Save when signed in) and reads their current state. */
    private fun showAccountActions(d: VideoDetails) {
        val signedIn = YtAccount.isSignedIn(act)
        subscribed = null
        likeStatus = null
        header.vSubscribe.isVisible = d.channelId != null
        Ui.subscribeButton(header.vSubscribe, false)
        likeCount = d.likes
        dislikeCount = -1
        showCounts()
        // like and dislike counts for everyone (Return YouTube Dislike); liking needs the YouTube account
        listOf(header.vLike, header.vDislike).forEach {
            it.isVisible = true
            Ui.toggleButton(it, false)
        }
        header.vSave.isVisible = signedIn
        Ui.toggleButton(header.vSave, false)
        loadDislikes(d.url)
        if (!signedIn) return
        val id = videoId(d.url) ?: return
        stateJob?.cancel()
        stateJob = act.lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { YtAccount.videoState(id) }.getOrNull() } ?: return@launch
            if (videoId(url) != id) return@launch
            subscribed = st.subscribed
            likeStatus = st.likeStatus
            Ui.subscribeButton(header.vSubscribe, st.subscribed == true)
            Ui.toggleButton(header.vLike, st.likeStatus == "LIKE")
            Ui.toggleButton(header.vDislike, st.likeStatus == "DISLIKE")
        }
    }

    private var dislikeJob: Job? = null
    private var likeCount = -1L
    private var dislikeCount = -1L

    private fun showCounts() {
        header.vLike.text = if (likeCount > 0) YtCatalog.count(likeCount) else "Like"
        header.vDislike.text = if (dislikeCount > 0) YtCatalog.count(dislikeCount) else "Dislike"
    }

    /** Your like/dislike changes the shown counts right away (from [before] to [now]). */
    private fun moveCounts(before: String?, now: String?) {
        if (before == "LIKE") likeCount = (likeCount - 1).coerceAtLeast(0)
        if (before == "DISLIKE") dislikeCount = (dislikeCount - 1).coerceAtLeast(0)
        if (now == "LIKE") likeCount = likeCount.coerceAtLeast(0) + 1
        if (now == "DISLIKE") dislikeCount = dislikeCount.coerceAtLeast(0) + 1
        showCounts()
    }

    private fun loadDislikes(page: String) {
        val id = youtubeId(page) ?: return
        dislikeJob?.cancel()
        dislikeJob = act.lifecycleScope.launch {
            val (likes, n) = withContext(Dispatchers.IO) { Dislikes.votes(id) }
            if (youtubeId(url) != id) return@launch
            // YouTube didn't give the likes (e.g. videos made for kids): the dislike service's count
            if (likeCount <= 0 && likes > 0) { likeCount = likes + if (likeStatus == "LIKE") 1 else 0; showCounts() }
            if (n >= 0) {
                // your own dislike (made in RAINAX) is counted even before the dislike service sees it
                dislikeCount = n + if (likeStatus == "DISLIKE" && n == 0L) 1 else 0
                showCounts()
            }
        }
    }

    private fun toggleSubscribe() {
        if (!YtAccount.isSignedIn(act)) { signIn(); return }
        val channelId = details?.channelId ?: return
        val now = subscribed == true
        subscribed = !now
        Ui.subscribeButton(header.vSubscribe, !now)
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.subscribe(channelId, !now) }.isSuccess }
            if (!ok) {
                subscribed = now
                Ui.subscribeButton(header.vSubscribe, now)
                act.toast("Couldn't change the subscription. Try again")
            } else {
                vm.feedCache.keys.removeAll { it.startsWith("tab:acc:") }
            }
        }
    }

    private fun rate(status: String) {
        val id = videoId(url) ?: return
        val before = likeStatus
        likeStatus = status
        Ui.toggleButton(header.vLike, status == "LIKE")
        Ui.toggleButton(header.vDislike, status == "DISLIKE")
        moveCounts(before, status)
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.rate(id, status) }.isSuccess }
            if (!ok) {
                moveCounts(status, before)
                likeStatus = before
                Ui.toggleButton(header.vLike, before == "LIKE")
                Ui.toggleButton(header.vDislike, before == "DISLIKE")
                act.toast("Couldn't save that. Try again")
            }
        }
    }

    private fun saveWatchLater() {
        val id = videoId(url) ?: return
        Ui.toggleButton(header.vSave, true)
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.watchLater(id) }.isSuccess }
            act.toast(if (ok) "Saved to Watch later" else "Couldn't save. Try again")
            if (!ok) Ui.toggleButton(header.vSave, false)
        }
    }

    private var attached: Player? = null
    private var autoMode = false

    /** Hides the picture placeholder once the video really plays. */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) showPlaceholder(false)
        }

        override fun onRenderedFirstFrame() = showPlaceholder(false)

        /** Auto quality: show the quality the player chose right now (it changes with the network). */
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (autoMode && videoSize.height > 0) {
                vb.qualityBadge.text = "AUTO " + qualityLabel(videoSize.height, 0, short = false).trim().substringBefore(' ')
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            vb.playerLoading.isVisible = false
        }
    }

    fun attach(p: Player?) {
        attached?.removeListener(playerListener)
        attached = p
        p?.addListener(playerListener)
        vb.playerView.player = p
    }

    private fun showPlaceholder(on: Boolean) {
        vb.playerThumb.isVisible = on
        vb.playerLoading.isVisible = on
    }

    private fun currentTitle(): String? = details?.title ?: header.vTitle.text?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Shows a video and starts playing it. [resume]: the player already plays this video (back in the app,
     * or the screen was rebuilt), so keep it going instead of starting again.
     */
    fun open(
        url: String, title: String? = null, uploader: String? = null, startMs: Long = 0,
        resume: Boolean = false, thumb: String? = null, keepMinimized: Boolean = false
    ) {
        val clean = FastExtractor.videoUrl(url)
        this.url = clean
        if (!keepMinimized && minimized) setMinimized(false)
        details = null
        commentsOff = false
        onChanged()

        header.vTitle.text = title.orEmpty()
        header.vMeta.text = ""
        header.vUploader.text = uploader.orEmpty()
        header.vSubs.text = ""
        Img.load(header.vAvatar, null)
        header.vLoading.isVisible = true
        header.vError.isVisible = false
        header.vComments.isVisible = false
        header.vDesc.isVisible = false
        header.vDesc.maxLines = 3
        header.vUpNext.isVisible = false
        vb.vNextBar.isVisible = false
        if (locked && !resume) setLocked(false)          // the next song/video keeps the lock
        listOf(header.vSubscribe, header.vLike, header.vDislike, header.vSave, header.vChapters).forEach { it.isVisible = false }
        vb.playerView.setExtraAdGroupMarkers(null, null)
        related.submit(emptyList())
        vb.videoList.scrollToPosition(0)
        vm.prefetch(clean)                    // the download sheet then has the sizes at once

        val p = player()
        val keep = resume && p != null && p.mediaItemCount > 0 && p.currentMediaItem?.mediaId == clean &&
            p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_VIDEO) == true
        if (!keep) {
            p?.pause()
            Img.load(vb.playerThumb, thumb ?: youtubeThumb(clean), widthPx = 960)
            showPlaceholder(true)
        } else {
            showPlaceholder(false)
        }

        job?.cancel()
        job = act.lifecycleScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { YtCatalog.video(clean) }
                if (this@VideoScreen.url != clean) return@launch
                details = d
                fill(d)
                // continue where you stopped watching (unless a start time was asked for)
                val start = if (startMs == 0L && AppPrefs.resumeVideos(act)) WatchHistory.resumeAt(act, clean) else startMs
                if (!keep) play(d, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@VideoScreen.url != clean) return@launch
                header.vLoading.isVisible = false
                vb.playerLoading.isVisible = false
                val text = friendlyError(e.message ?: "Couldn't open this video")
                showError(if (text == NO_INTERNET) text else text + "\nYou can still try Download.")
            }
        }
        loadComments(clean)
    }

    private var refreshedAt = 0L

    /**
     * The video's stream addresses expired (e.g. paused for hours) or only its sound is playing:
     * ask YouTube again and continue from the same spot. At most once a minute.
     */
    fun refreshStreams(): Boolean {
        val u = url ?: return false
        val now = System.currentTimeMillis()
        if (now - refreshedAt < 60_000) return false
        refreshedAt = now
        val pos = player()?.currentPosition?.coerceAtLeast(0) ?: 0
        FastExtractor.forget(u)
        job?.cancel()
        job = act.lifecycleScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { YtCatalog.video(u) }
                if (this@VideoScreen.url != u) return@launch
                val filled = details != null
                details = d
                if (!filled) fill(d)                 // the page was still loading: show it too
                play(d, pos)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@VideoScreen.url == u) showError("Couldn't load this video again. Check your internet.")
            }
        }
        return true
    }

    /** The player moved on (next track, or a new video started elsewhere): show that video. */
    fun follow(p: Player) {
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isBlank() || id == url) return
        open(
            id, item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString(),
            p.currentPosition.coerceAtLeast(0), resume = true,
            thumb = item.mediaMetadata.artworkUri?.toString(), keepMinimized = minimized
        )
    }

    // ---------- mini player ----------

    /** The page is folded into the floating mini player (the video keeps playing in it). */
    var minimized = false
        private set

    fun minimize() {
        if (url == null) return
        if (fullscreen) exitFullscreen()
        setMinimized(true)
    }

    fun expand() {
        if (url == null) return
        setMinimized(false)
    }

    private fun setMinimized(on: Boolean) {
        minimized = on
        onChanged()                            // the picture moves to the floating player and keeps playing
    }

    /** The page's player view (the main screen moves the picture between it and the floating player). */
    val playerView: PlayerView get() = vb.playerView

    fun close() {
        if (locked) setLocked(false)
        if (fullscreen) exitFullscreen()
        if (minimized) setMinimized(false)
        job?.cancel()
        commentsJob?.cancel()
        url = null
        details = null
        player()?.run {
            // songs of the Music player (a video was opened over them and didn't start): leave them
            if (!MusicPlayer.isMusic(currentMediaItem)) {
                stop()
                clearMediaItems()
            }
        }
        onChanged()
    }

    fun showError(text: String) {
        val offline = !Net.online(act)
        header.vError.text = if (offline) "$NO_INTERNET\nTap here for internet settings." else text
        header.vError.setOnClickListener { if (!Net.online(act)) Net.openSettings(act) }
        header.vError.isVisible = true
    }

    // ---------- playback ----------

    /** A video waiting for the player to connect (opened right at app start). */
    private var pendingPlay: Pair<VideoDetails, Long>? = null

    /** The player is connected now: start the video that was waiting for it. */
    fun onPlayerReady() {
        val (d, start) = pendingPlay ?: return
        pendingPlay = null
        if (url == d.url) play(d, start)
    }

    private fun play(d: VideoDetails, startMs: Long) {
        val p = player() ?: run { pendingPlay = d to startMs; return }      // starts as soon as it connects
        pendingPlay = null
        val src = d.play
        // Auto: one manifest with every quality, the player picks and changes it with the network speed
        val adaptive = AppPrefs.playerQuality(act) <= 0 && src.dash != null
        val chosen = if (src.audio != null && !adaptive) pick(d) else null
        autoMode = adaptive
        vb.qualityBadge.text = if (adaptive) "AUTO" else chosen?.let { qualityLabel(it.height, it.fps, short = true) }.orEmpty()
        vb.qualityBadge.isVisible = adaptive || chosen != null
        if (adaptive) RxMediaSourceFactory.putManifest(d.url, src.dash!!)
        val pictureUrl = chosen?.url ?: src.video
        val uri = when {
            adaptive -> Uri.Builder().scheme("rainax").authority("dash").appendQueryParameter("u", d.url).build()
            pictureUrl != null && src.audio != null -> Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", pictureUrl).appendQueryParameter("a", src.audio)
                .appendQueryParameter("u", d.url).build()
            src.hls != null -> Uri.Builder().scheme("rainax").authority("hls")
                .appendQueryParameter("h", src.hls).appendQueryParameter("u", d.url).build()
            src.muxed != null -> Uri.parse(src.muxed)
            else -> {
                showError("This video can't play here, but you can still download it.")
                return
            }
        }
        d.audioUrl?.let { BgPlayService.remember(d.url, it) }
        val item = MediaItem.Builder()
            .setMediaId(d.url)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(d.title)
                    .setArtist(d.uploader)
                    .setArtworkUri(d.thumb?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply { putBoolean(EXTRA_VIDEO, true) })
                    .build()
            )
            .build()
        // "Up next" videos are the next tracks (in the background they play as sound)
        val next = d.related.filter { it.seconds > 0 }.take(25).map { e ->
            val lazy = BgPlayService.lazyUri(e.url)
            MediaItem.Builder()
                .setMediaId(e.url)
                .setUri(lazy)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(lazy).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.title)
                        .setArtist(e.uploader)
                        .setArtworkUri(e.thumb?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
        try {
            p.setMediaItems(listOf(item) + next, 0, startMs)
            p.prepare()
            p.play()
        } catch (e: Exception) {
            showError("Couldn't start the video: " + (e.message ?: e.javaClass.simpleName).take(80))
        }
    }

    // ---------- details ----------

    private fun fill(d: VideoDetails) {
        showAccountActions(d)
        header.vLoading.isVisible = false
        header.vTitle.text = d.title
        val meta = mutableListOf<String>()
        if (d.views >= 0) meta += YtCatalog.count(d.views) + if (d.seconds < 0) " watching" else " views"
        d.uploaded?.takeIf { it.isNotBlank() }?.let { meta += it }
        if (d.likes > 0) meta += YtCatalog.count(d.likes) + " likes"
        header.vMeta.text = meta.joinToString(" • ") + "  ...more"
        header.vUploader.text = d.uploader
        header.vSubs.text = if (d.subscribers > 0) YtCatalog.count(d.subscribers) + " subscribers" else ""
        Img.load(header.vAvatar, d.avatar, circle = true, widthPx = 120)
        val desc = d.description.trim()
        if (desc.isNotEmpty()) {
            header.vDesc.text = if (desc.contains('<')) HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT) else desc
            header.vDesc.isVisible = true
        }
        related.submit(d.related)
        header.vUpNext.isVisible = d.related.isNotEmpty()
        showNextBar(d)
        vb.playerTitle.text = d.title
        showChapterMarks(d)
        onChanged()                            // the mini player shows the real title
    }

    // ---------- chapters ----------

    /** Chapter starts as marks on the seek bar, and the Chapters button. */
    private fun showChapterMarks(d: VideoDetails) {
        val list = d.chapters.filter { it.startMs > 0 }
        header.vChapters.isVisible = d.chapters.size >= 2
        if (list.isEmpty()) {
            vb.playerView.setExtraAdGroupMarkers(null, null)
            return
        }
        vb.playerView.setExtraAdGroupMarkers(list.map { it.startMs }.toLongArray(), BooleanArray(list.size))
    }

    private fun showChapters() {
        val d = details ?: return
        if (d.chapters.isEmpty()) return
        val pos = player()?.takeIf { it.currentMediaItem?.mediaId == d.url }?.currentPosition ?: 0L
        val current = d.chapters.indexOfLast { it.startMs <= pos }
        optionsSheet("Chapters", d.chapters.mapIndexed { i, c ->
            Opt(c.title.ifBlank { "Chapter ${i + 1}" }, YtCatalog.duration(c.startMs / 1000).ifBlank { "0:00" }, checked = i == current) {
                player()?.takeIf { it.currentMediaItem?.mediaId == d.url }?.let {
                    it.seekTo(c.startMs)
                    it.play()
                }
            }
        })
    }

    private fun loadComments(clean: String) {
        commentsJob?.cancel()
        commentsJob = act.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(clean, null) }.getOrNull() }
            // your own comment first (YouTube shows it on top to you)
            val list = videoId(clean)?.let { MyComments.forVideo(act, it) }.orEmpty() + page?.items.orEmpty().take(20)
            if (url != clean) return@launch
            // turned off by YouTube (always for videos made for kids): say so instead of showing nothing
            commentsOff = list.isEmpty() && (page?.disabled == true || YtFallback.served(videoId(clean)))
            header.vCommentsTitle.text = if (page != null && page.total > 0) "Comments  " + YtCatalog.count(page.total.toLong()) else "Comments"
            if (commentsOff) {
                Img.load(header.vCommentAvatar, null)
                header.vCommentAvatar.isVisible = false
                header.vCommentText.text = "Comments are turned off for this video"
                header.vComments.isVisible = true
                return@launch
            }
            if (list.isEmpty()) return@launch
            val first = list.first()
            header.vCommentAvatar.isVisible = true
            Img.load(header.vCommentAvatar, first.avatar, circle = true, widthPx = 80)
            header.vCommentText.text = plain(first)
            header.vComments.isVisible = true
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    private var commentsSheet: CommentsSheet? = null

    private var commentsOff = false

    private fun showComments() {
        val u = url ?: return
        if (commentsOff) { act.toast("Comments are turned off for this video"); return }
        if (commentsSheet?.isShowing == true) return          // a quick double tap opens it once
        // opens under the video (the video keeps playing above it), like YouTube
        val loc = IntArray(2)
        vb.playerBox.getLocationOnScreen(loc)
        val below = act.resources.displayMetrics.heightPixels - (loc[1] + vb.playerBox.height)
        commentsSheet = CommentsSheet(act, u, signIn, if (fullscreen) 0 else below).also { it.show() }
    }

    /**
     * Full description: likes, views, upload date, the chapters (tap to jump) and the text. Its timestamps
     * and links to this video jump in this player; links to other YouTube videos open here in RAINAX;
     * only other websites open in the browser.
     */
    private fun showDescription() {
        val d = details ?: return
        val sb = com.rainax.ytdownloader.databinding.SheetDescriptionBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        sb.descTitle.text = d.title
        sb.descLikes.text = if (likeCount > 0) YtCatalog.count(likeCount) else if (d.likes > 0) YtCatalog.count(d.likes) else "–"
        sb.descViews.text = if (d.views >= 0) YtCatalog.count(d.views) else "–"
        sb.descDate.text = d.date ?: d.uploaded ?: "–"

        // chapters, with the one playing now marked
        if (d.chapters.size >= 2) {
            sb.descChapterBox.isVisible = true
            val pos = player()?.takeIf { it.currentMediaItem?.mediaId == d.url }?.currentPosition ?: -1L
            val now = if (pos >= 0) d.chapters.indexOfLast { it.startMs <= pos } else -1
            d.chapters.forEachIndexed { i, c ->
                val row = act.layoutInflater.inflate(R.layout.item_desc_chapter, sb.descChapters, false)
                row.findViewById<android.widget.TextView>(R.id.chTime).text = YtCatalog.duration(c.startMs / 1000).ifBlank { "0:00" }
                row.findViewById<android.widget.TextView>(R.id.chTitle).text = c.title.ifBlank { "Chapter ${i + 1}" }
                row.findViewById<View>(R.id.chNow).isVisible = i == now
                row.setOnClickListener { seekHere(c.startMs, dialog) }
                sb.descChapters.addView(row)
            }
        }

        val desc = d.description.trim()
        if (desc.isEmpty()) {
            sb.descText.text = "No description"
        } else {
            sb.descText.text = richDescription(desc, dialog)
            sb.descText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            sb.descText.highlightColor = android.graphics.Color.TRANSPARENT
        }
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    /** The description with every link and timestamp handled inside the app (see [openDescLink]). */
    private fun richDescription(desc: String, dialog: BottomSheetDialog): CharSequence {
        val html = desc.contains('<')
        val text = android.text.SpannableStringBuilder(
            if (html) HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT) else desc
        )
        if (!html) android.text.util.Linkify.addLinks(text, android.text.util.Linkify.WEB_URLS)
        // plain "1:23" / "1:02:03" timestamps that aren't links yet
        val linked = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
            .map { text.getSpanStart(it) until text.getSpanEnd(it) }
        Regex("(?<![\\d:])(\\d{1,2}:)?\\d{1,2}:\\d{2}(?![\\d:])").findAll(text).forEach { m ->
            if (linked.none { m.range.first in it }) {
                val sec = m.value.split(':').fold(0L) { acc, x -> acc * 60 + (x.toLongOrNull() ?: 0) }
                text.setSpan(android.text.style.URLSpan("rxseek:$sec"), m.range.first, m.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val primary = androidx.core.content.ContextCompat.getColor(act, R.color.rx_primary)
        for (span in text.getSpans(0, text.length, android.text.style.URLSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val link = span.url
            val isTime = link.startsWith("rxseek:") || seekSeconds(link) != null && youtubeId(absolute(link)) == youtubeId(url)
            text.removeSpan(span)
            text.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = openDescLink(link, dialog)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = primary
                    ds.isUnderlineText = false
                    ds.isFakeBoldText = isTime                  // timestamps stand out like on YouTube
                }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun absolute(link: String): String = when {
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> "https://www.youtube.com$link"
        else -> link
    }

    /** "t=83", "t=83s", "t=1m23s", "t=1h2m3s" (also "start=") -> seconds; null when there is none. */
    private fun seekSeconds(link: String): Long? {
        val v = Regex("[?&#](?:t|start|time_continue)=([0-9hms]+)").find(link)?.groupValues?.get(1) ?: return null
        if (v.all { it.isDigit() }) return v.toLongOrNull()
        var total = 0L
        Regex("(\\d+)([hms])").findAll(v).forEach { m ->
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }
        return total
    }

    private fun openDescLink(raw: String, dialog: BottomSheetDialog) {
        if (raw.startsWith("rxseek:")) {
            raw.removePrefix("rxseek:").toLongOrNull()?.let { seekHere(it * 1000, dialog) }
            return
        }
        val link = absolute(raw)
        val id = youtubeId(link)
        val sec = seekSeconds(link)
        when {
            // this video at a time: jump there in this player
            id != null && id == youtubeId(url) -> seekHere((sec ?: 0) * 1000, dialog)
            // another YouTube video: open it here in RAINAX
            id != null -> {
                dialog.dismiss()
                open("https://www.youtube.com/watch?v=$id", startMs = (sec ?: 0) * 1000)
            }
            // a YouTube channel: its page here in RAINAX
            Regex("youtube\\.com/(@[^/?#]+|channel/[\\w-]+|c/[^/?#]+|user/[^/?#]+)").containsMatchIn(link) -> {
                dialog.dismiss()
                openChannel(link.substringBefore('?'), null, null)
            }
            else -> runCatching {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE))
            }.onFailure { act.toast("Can't open this link") }
        }
    }

    /** Jumps to [ms] in the playing video and closes the sheet. */
    private fun seekHere(ms: Long, dialog: BottomSheetDialog?) {
        val p = player()?.takeIf { it.currentMediaItem?.mediaId == url }
        if (p == null) { act.toast("Start the video first"); return }
        p.seekTo(ms.coerceAtLeast(0))
        p.play()
        dialog?.dismiss()
        vb.videoList.smoothScrollToPosition(0)               // the player in view
    }

    // ---------- quality and speed (like YouTube's gear menu) ----------

    /** Decoders this phone has (video/avc, video/x-vnd.on2.vp9, video/av01, ...). */
    private val decoders: List<android.media.MediaCodecInfo> by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        }.getOrDefault(emptyList())
    }

    /** True if this phone can show this picture quality smoothly. */
    private fun playable(o: VideoOption): Boolean {
        val mime = when (o.codec) {
            "avc" -> "video/avc"
            "vp9" -> "video/x-vnd.on2.vp9"
            "av1" -> "video/av01"
            else -> return false
        }
        val width = (o.height * 16 / 9).let { it + (it and 1) }
        return decoders.any { info ->
            info.supportedTypes.any { it.equals(mime, true) } && runCatching {
                val caps = info.getCapabilitiesForType(mime).videoCapabilities
                caps.isSizeSupported(width, o.height) || caps.isSizeSupported(o.height, width)
            }.getOrDefault(o.height <= 1080)
        }
    }

    /** One choice per height: H.264 first (lightest), then VP9, then AV1. */
    private fun choices(d: VideoDetails): List<VideoOption> {
        val rank = mapOf("avc" to 3, "vp9" to 2, "av1" to 1)
        return d.play.options.filter { playable(it) }
            .groupBy { it.height }
            .mapNotNull { (_, list) -> list.maxWithOrNull(compareBy<VideoOption> { rank[it.codec] ?: 0 }.thenBy { it.fps }) }
            .sortedByDescending { it.height }
    }

    /** Auto: 720p on Wi-Fi, 480p on mobile data (starts fast, saves data). */
    private fun autoHeight(): Int {
        val cm = act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (cm.isActiveNetworkMetered) 480 else 720
    }

    private fun pick(d: VideoDetails): VideoOption? {
        val list = choices(d)
        if (list.isEmpty()) return null
        val pref = AppPrefs.playerQuality(act)
        val target = if (pref <= 0) autoHeight() else pref
        return list.filter { it.height <= target }.maxByOrNull { it.height } ?: list.minByOrNull { it.height }
    }

    private fun qualityLabel(h: Int, fps: Int, short: Boolean = false): String {
        val base = "${h}p" + if (fps >= 50) fps.toString() else ""
        if (short) return when {
            h >= 4320 -> "8K"
            h >= 2160 -> "4K"
            h >= 1440 -> "2K"
            h >= 720 -> "HD"
            else -> base
        }
        return base + when {
            h >= 4320 -> "  8K"
            h >= 2160 -> "  4K"
            h >= 1440 -> "  2K"
            h >= 720 -> "  HD"
            else -> ""
        }
    }

    private fun speedLabel(x: Float) = if (x == 1f) "Normal" else (if (x == x.toInt().toFloat()) "${x.toInt()}x" else "${x}x")

    /** Gear menu: Quality and Playback speed. */
    private fun showPlayerMenu() {
        val d = details
        val p = player()
        val chosen = d?.let { pick(it) }
        val nowHeight = p?.videoSize?.height ?: 0
        val qValue = when {
            autoMode -> if (nowHeight > 0) "Auto (${nowHeight}p)" else "Auto"
            d == null || chosen == null -> "Auto"
            AppPrefs.playerQuality(act) <= 0 -> "Auto (" + qualityLabel(chosen.height, chosen.fps).trim() + ")"
            else -> qualityLabel(chosen.height, chosen.fps).trim()
        }
        val speed = p?.playbackParameters?.speed ?: 1f
        optionsSheet("Settings", listOf(
            Opt("Quality", qValue, R.drawable.ic_hd) { showQualityMenu() },
            Opt("Playback speed", speedLabel(speed), R.drawable.ic_speed) { showSpeedMenu() },
            Opt("Lock screen", null, R.drawable.ic_lock) { setLocked(true) },
            Opt("Copy link", null, R.drawable.ic_link) { copyLink() },
            Opt("Share", null, R.drawable.ic_share) { share() }
        ))
    }

    private fun showQualityMenu() {
        val d = details ?: return
        val list = choices(d)
        if (list.isEmpty() || d.play.audio == null) {
            act.toast("Quality can't be changed for this video")
            return
        }
        val pref = AppPrefs.playerQuality(act)
        val current = pick(d)
        val opts = mutableListOf(Opt("Auto", "Adjusts to your network", checked = pref <= 0) { setQuality(0) })
        list.forEach { o ->
            opts += Opt(qualityLabel(o.height, o.fps), null, checked = pref > 0 && current?.height == o.height) { setQuality(o.height) }
        }
        optionsSheet("Quality", opts)
    }

    private fun setQuality(h: Int) {
        AppPrefs.setPlayerQuality(act, h)
        val d = details ?: return
        val p = player() ?: return
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        play(d, pos)
        if (!wasPlaying) p.pause()
    }

    private fun showSpeedMenu() {
        val p = player() ?: return
        val now = p.playbackParameters.speed
        val opts = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { x ->
            Opt(speedLabel(x), null, checked = x == now) { player()?.setPlaybackSpeed(x) }
        }
        optionsSheet("Playback speed", opts)
    }

    private class Opt(
        val text: String,
        val value: String?,
        val icon: Int = 0,
        val checked: Boolean = false,
        val action: () -> Unit
    )

    private fun optionsSheet(title: String, opts: List<Opt>) {
        val sb = com.rainax.ytdownloader.databinding.SheetPlayerOptionsBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        sb.optTitle.text = title
        opts.forEach { o ->
            val row = com.rainax.ytdownloader.databinding.ItemOptionBinding.inflate(act.layoutInflater, sb.optList, false)
            row.optText.text = o.text
            row.optValue.text = o.value.orEmpty()
            row.optValue.isVisible = o.value != null
            row.optCheck.isVisible = o.checked
            if (o.icon != 0) {
                row.optIcon.setImageResource(o.icon)
                row.optIcon.isVisible = true
            }
            row.root.setOnClickListener {
                dialog.dismiss()
                o.action()
            }
            sb.optList.addView(row.root)
        }
        dialog.show()
    }

    private fun copyLink() {
        val u = url ?: return
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        AppPrefs.setClipSeen(act, u)                        // no "link copied" popup for our own copy
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Video link", u))
        if (android.os.Build.VERSION.SDK_INT < 33) act.toast("Link copied")
    }

    private fun youtubeThumb(u: String): String? =
        Regex("(?:[?&]v=|shorts/)([\\w-]{6,})").find(u)?.groupValues?.get(1)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    private fun share() {
        val u = url ?: return
        val text = listOfNotNull(currentTitle(), u).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            act.startActivity(Intent.createChooser(send, "Share video"))
        } catch (e: Exception) { }
    }

    // ---------- fullscreen ----------

    /** Phone turned sideways while watching: full screen, like YouTube (through the player's button, so its icon stays right). */
    fun enterFullscreen() {
        if (!isOpen || minimized || fullscreen) return
        val btn = vb.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (btn != null && btn.visibility == View.VISIBLE) btn.performClick() else setFullscreen(true)
    }

    /** Back while fullscreen: press the player's own fullscreen button so its icon stays right. */
    fun exitFullscreen() {
        val btn = vb.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (btn != null && btn.visibility == View.VISIBLE) btn.performClick() else setFullscreen(false)
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        vb.playerBox.fill = on
        val lp = vb.playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = if (on) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = if (on) 1f else 0f
        vb.playerBox.layoutParams = lp
        vb.videoList.isVisible = !on
        vb.videoClose.isVisible = !on
        vb.playerTitle.visibility = if (on) View.VISIBLE else View.INVISIBLE
        vb.playerTitle.text = currentTitle().orEmpty()
        if (on) { vb.vNextBar.isVisible = false; vb.vPickBar.isVisible = false } else showPicks()
        act.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onChanged()
    }

    private fun AppCompatActivity.toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        /** Marks queue entries that carry the picture (the "Up next" ones are sound only until opened). */
        const val EXTRA_VIDEO = "rainax_video"
    }
}
