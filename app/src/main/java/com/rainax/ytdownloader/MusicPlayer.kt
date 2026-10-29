package com.rainax.ytdownloader

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.PageMusicPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The Music player, like YouTube Music: a big player (cover in the cover's colours, Song / Video switch,
 * like, comments, download, share, shuffle and repeat, Up next, sleep timer) and a mini bar above the
 * bottom bar. Songs play through BgPlayService like videos, so they keep going in the background, on the
 * lock screen and in the notification. When a queue ends, similar songs are added (radio), like YouTube Music.
 */
@OptIn(UnstableApi::class)
class MusicPlayer(
    private val act: AppCompatActivity,
    private val pg: PageMusicPlayerBinding,
    /** The mini music bar above the bottom bar (Compose, MusicPage.kt). */
    private val mini: androidx.compose.ui.platform.ComposeView,
    private val player: () -> Player?,
    private val download: (url: String, title: String?, audio: Boolean) -> Unit,
    /** Closes the video page first (one thing plays at a time). */
    private val beforePlay: () -> Unit,
    private val signIn: () -> Unit,
    /** The main screen updates the mini bar, Back and where the video picture shows. */
    private val onChanged: () -> Unit
) {
    /** Songs are loaded in the player (the mini bar or the big player shows). */
    var active = false
        private set

    /** The big player is open. */
    var expanded = false
        private set

    /** Called with the playing song's link when it changes (the Music tab marks it). */
    var onNowPlaying: (String?) -> Unit = {}

    /** Called with the playing song's cover colour (the Music tab glows in it). */
    var onTone: (Int) -> Unit = {}

    /** Where the video of a song shows (the main screen gives it the player's picture). */
    val videoView: PlayerView get() = art.mpVideo

    /** The big player shows a song's video now. */
    val wantsPicture: Boolean get() = active && expanded && videoMode

    private var videoMode = false
    private var queueName = ""
    private var shownId: String? = null
    private var attached: Player? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var dragging = false
    private var videoJob: Job? = null
    /** The song last switched to its video (if it comes back as sound only, its video failed). */
    private var pictureFor: String? = null
    private var infoJob: Job? = null
    private var commentsJob: Job? = null
    private var likeStatus: String? = null
    private var likeCount = -1L
    private val commentCounts = HashMap<String, String>()
    private var queueSheet: QueueSheet? = null
    /** What the big player shows (Compose, MusicPage.kt) and its cover / video (a real view). */
    private val ui = MusicPageUi()
    private val art = com.rainax.ytdownloader.databinding.LayoutMusicArtBinding.inflate(act.layoutInflater)

    // the background takes the cover's colour
    private var tone = MusicArt.DEFAULT_TONE
    private var toneAnim: ValueAnimator? = null
    private val pageBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tone, tone, tone))

    // ---------- setup ----------

    @SuppressLint("ClickableViewAccessibility")
    fun setup() {
        pg.root.background = pageBg
        art.mpArtBox.clipToOutline = true
        applyTone(tone)

        val actions = MusicPageActions(
            collapse = { collapse() },
            mode = { setVideoMode(it) },
            more = { playerMenu() },
            like = { rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE") },
            dislike = { rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE") },
            comments = { showComments() },
            download = { current()?.let { download(it.url, it.title, true) } },
            share = { current()?.let { share(it) } },
            lyrics = { showLyrics() },
            seekStart = { at -> dragging = true; showSeek(at) },
            seekMove = { at -> showSeek(at) },
            seekEnd = { at ->
                dragging = false
                val d = duration()
                if (d > 0) player()?.seekTo(d * at / 1000)
            },
            shuffle = { player()?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
            previous = { previous() },
            play = { togglePlay() },
            next = { next() },
            repeat = { cycleRepeat() },
            upNext = { showQueue() }
        )
        pg.mpCompose.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        pg.mpCompose.setContent { RxTheme { MusicPageScreen(ui, art.root, actions) } }

        // swipe the cover down: the big player folds into the mini bar
        val fling = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (velocityY > 1200 && dy > 80 && abs(velocityY) > abs(velocityX)) {
                    collapse()
                    return true
                }
                return false
            }
        })
        art.mpArtArea.setOnTouchListener { _, e -> fling.onTouchEvent(e) }

        mini.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        mini.setContent { RxTheme { MusicMiniBar(ui, MusicMiniActions({ expand() }, { togglePlay() }, { next() }, { close() })) } }
        updateModes()
    }

    /** The player connected (or went away): follow it. */
    fun attach(p: Player?) {
        attached?.removeListener(listener)
        attached = p
        p?.addListener(listener)
        sync()
    }

    /** The activity is closing: stop timers and animations (the music itself keeps playing). */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        ticking = false
        toneAnim?.cancel()
        // the turning record never stops by itself: without this, every rebuilt screen (theme or colour change
        // while music played) left an old one spinning in memory
        vinylSpin?.cancel()
        vinylSpin = null
        attached?.removeListener(listener)
        attached = null
    }

    /** Shows what the player holds now (after connecting, or back in the app). */
    fun sync() {
        val p = player()
        val item = p?.currentMediaItem
        if (p == null || item == null || !isMusic(item)) {
            if (active) deactivate()
            return
        }
        if (!active) videoMode = hasPicture(item)          // first look (app opened while songs play)
        active = true
        updateModes()
        showSong(item)
        updatePlayState()
        updateProgress()
        if (p.isPlaying) startTicker()
        // video mode, but the song came back as sound only (e.g. renewed in the background): video again
        if (videoMode && !hasPicture(item) && videoJob?.isActive != true) loadVideo()
        onChanged()
    }

    /** The app is on screen (not in the background). */
    private fun onScreen() = act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player() ?: return
            if (mediaItem == null || !isMusic(mediaItem)) {
                if (active) deactivate()
                return
            }
            if (!active) {
                active = true
                onChanged()
            }
            showSong(mediaItem)
            val failedVideo = videoMode && !hasPicture(mediaItem) && mediaItem.mediaId == pictureFor &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            when {
                // the player gave up on this song's video and plays its sound: stay with the sound
                failedVideo -> {
                    pictureFor = null
                    videoMode = false
                    updateModes()
                    onChanged()
                    toast("The video couldn't play. Playing the song")
                }
                // (in the background only the sound plays: the video is loaded when the app is back)
                videoMode && !hasPicture(mediaItem) && onScreen() -> loadVideo()
                // a song played as video before, now in song mode: back to sound only (no video data)
                !videoMode && hasPicture(mediaItem) && onScreen() -> swap(p, mediaItem, BgPlayService.lazyUri(mediaItem.mediaId), picture = false)
            }
            prefetchNext(p)
            updateProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayState()
            spinVinyl(isPlaying)
            if (isPlaying) startTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayState()
            updateProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePlayState()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateModes()
            updateUpNext()
            queueSheet?.refresh()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateModes()
            queueSheet?.refresh()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!active) return
            updateUpNext()
            queueSheet?.refresh()
        }
    }

    // ---------- playing ----------

    /**
     * Plays [songs] from [index]. [from] names the list ("Punjabi hits"); [radio]: similar songs follow
     * (YouTube's Mix of the song), like tapping a song in YouTube Music.
     */
    fun play(songs: List<VideoItem>, index: Int, from: String, radio: Boolean) {
        if (songs.isEmpty()) return
        val p = player()
        if (p == null) {
            toast("The player is starting. Try again in a moment")
            return
        }
        beforePlay()
        val start = index.coerceIn(0, songs.size - 1)
        queueName = if (radio) "${songs[start].title} radio" else from
        videoMode = false
        pictureFor = null
        MusicQueue.reset()
        p.shuffleModeEnabled = false
        p.setMediaItems(songs.map { MusicQueue.songItem(it) }, start, 0)
        p.prepare()
        p.play()
        active = true
        updateModes()
        // similar songs follow (they are added by the player once it has the list)
        if (radio || songs.size == 1) MusicQueue.topUp(songs[start], advanceAfter = false)
        expand()
        onChanged()
    }

    /** A playlist from the Music tab: its songs in the player. */
    fun playPlaylist(item: VideoItem) {
        toast("Opening ${item.title.ifBlank { "playlist" }}…")
        act.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { runCatching { YtCatalog.playlist(item.url, null) }.getOrNull() }
            val songs = page?.items.orEmpty().filter { !it.isPlaylist && !it.isChannel && it.seconds > 0 }
            if (songs.isEmpty()) {
                toast("Couldn't open this playlist. Check your internet")
                return@launch
            }
            play(songs, 0, item.title.ifBlank { "Playlist" }, false)
        }
    }

    fun playNext(song: VideoItem) {
        val p = player() ?: return
        if (!active || p.mediaItemCount == 0) {
            play(listOf(song), 0, song.title, true)
            return
        }
        p.addMediaItem((p.currentMediaItemIndex + 1).coerceAtMost(p.mediaItemCount), MusicQueue.songItem(song))
        toast(if (p.shuffleModeEnabled) "Added to queue (shuffle is on)" else "Plays next")
    }

    fun addToQueue(song: VideoItem) {
        val p = player() ?: return
        if (!active || p.mediaItemCount == 0) {
            play(listOf(song), 0, song.title, true)
            return
        }
        p.addMediaItem(MusicQueue.songItem(song))
        toast("Added to queue")
    }

    /** ⋮ on a song in the Music tab. */
    fun songMenu(song: VideoItem) {
        options(song.title, listOf(
            Opt("Play next", R.drawable.ic_queue_next) { playNext(song) },
            Opt("Add to queue", R.drawable.ic_playlist_add) { addToQueue(song) },
            Opt("Start radio", R.drawable.ic_radio) { play(listOf(song), 0, song.title, true) },
            Opt("Download song", R.drawable.ic_audio) { download(song.url, song.title, true) },
            Opt("Download video", R.drawable.ic_video) { download(song.url, song.title, false) },
            Opt("Share", R.drawable.ic_share) { share(song) }
        ))
    }

    /** Stops the music and closes the player. */
    fun close() {
        setSleep(0, quiet = true)
        val p = player()
        if (p != null && isMusic(p.currentMediaItem)) {
            p.stop()
            p.clearMediaItems()
        }
        deactivate()
    }

    private fun deactivate() {
        active = false
        videoJob?.cancel()
        infoJob?.cancel()
        commentsJob?.cancel()
        MusicQueue.reset()
        MusicQueue.sleepAt = 0                           // the sleep timer was for the music
        MusicQueue.sleepEndOfSong = false
        pictureFor = null
        shownId = null
        queueSheet?.dismiss()
        if (expanded) {
            expanded = false
            hidePage(animate = false)
            restoreBars()
        }
        onNowPlaying(null)
        onChanged()
    }

    private fun togglePlay() {
        val p = player() ?: return
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    private fun previous() {
        val p = player() ?: return
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    private fun next() {
        val p = player() ?: return
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        } else {
            val s = current() ?: return
            toast("Finding more songs…")
            MusicQueue.topUp(s, advanceAfter = true) { toast("No more songs found") }
        }
    }

    private fun cycleRepeat() {
        val p = player() ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        toast(when (p.repeatMode) {
            Player.REPEAT_MODE_ALL -> "Repeat all"
            Player.REPEAT_MODE_ONE -> "Repeat this song"
            else -> "Repeat off"
        })
    }

    /** Finds the next song's sound early, so it starts at once. */
    private fun prefetchNext(p: Player) {
        val i = p.nextMediaItemIndex
        if (i == C.INDEX_UNSET || i >= p.mediaItemCount) return
        val page = p.getMediaItemAt(i).mediaId
        if (!FastExtractor.supports(page)) return
        act.lifecycleScope.launch(Dispatchers.IO) { runCatching { BgPlayService.audioFor(page, false) } }
    }

    // ---------- Song / Video ----------

    private fun setVideoMode(on: Boolean) {
        if (videoMode == on) return
        val p = player() ?: return
        val item = p.currentMediaItem ?: return
        if (!isMusic(item)) return
        videoMode = on
        updateModes()
        if (on) {
            loadVideo()
        } else {
            videoJob?.cancel()
            art.mpArtLoading.isVisible = false
            if (hasPicture(item)) swap(p, item, BgPlayService.lazyUri(item.mediaId), picture = false)
        }
        onChanged()
    }

    /** Video mode: the song's video (picture + sound) replaces its sound, at the same moment. */
    private fun loadVideo() {
        val p = player() ?: return
        val item = p.currentMediaItem ?: return
        if (hasPicture(item)) return
        val url = item.mediaId
        videoJob?.cancel()
        art.mpArtLoading.isVisible = true
        videoJob = act.lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { YtCatalog.video(url) }.getOrNull() }
            art.mpArtLoading.isVisible = false
            val pl = player() ?: return@launch
            val now = pl.currentMediaItem ?: return@launch
            if (!videoMode || now.mediaId != url) return@launch
            val uri = d?.let { videoUri(it) }
            if (d == null || uri == null) {
                toast("This song has no video here. Playing the song")
                videoMode = false
                updateModes()
                onChanged()
                return@launch
            }
            d.audioUrl?.let { BgPlayService.remember(d.url, it) }
            pictureFor = url
            swap(pl, now, uri, picture = true)
        }
    }

    /** H.264 picture (720p on Wi-Fi, 480p on mobile data) + sound, else one file with both. */
    private fun videoUri(d: VideoDetails): Uri? {
        val src = d.play
        val cm = act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val target = if (cm.isActiveNetworkMetered) 480 else 720
        val picture = src.options.filter { it.codec == "avc" && it.height in 1..target }.maxByOrNull { it.height }?.url ?: src.video
        val audio = src.audio
        return when {
            picture != null && audio != null -> Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", picture).appendQueryParameter("a", audio)
                .appendQueryParameter("u", d.url).build()
            src.muxed != null -> Uri.parse(src.muxed)
            else -> null
        }
    }

    /** Replaces the playing entry's address (song <-> video), keeping its place and moment. */
    private fun swap(p: Player, item: MediaItem, uri: Uri, picture: Boolean) {
        val index = p.currentMediaItemIndex
        val pos = p.currentPosition.coerceAtLeast(0)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putBoolean(EXTRA_MUSIC, true)
            putBoolean(EXTRA_PICTURE, picture)
        }
        val fresh = item.buildUpon()
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
        p.replaceMediaItem(index, fresh)
        p.seekTo(index, pos)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    private val vinylOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setOval(0, 0, view.width, view.height)
    }
    private var vinylSpin: android.animation.ObjectAnimator? = null

    /** The record turns (one turn in 8 s) while the song plays, and stops where it is when paused. */
    private fun spinVinyl(playing: Boolean) {
        if (videoMode || !android.animation.ValueAnimator.areAnimatorsEnabled()) { vinylSpin?.pause(); return }
        val a = vinylSpin ?: android.animation.ObjectAnimator.ofFloat(art.mpArt, View.ROTATION, 0f, 360f).apply {
            duration = 8000
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            vinylSpin = this
        }
        when {
            playing && !a.isStarted -> a.start()
            playing && a.isPaused -> a.resume()
            !playing && a.isRunning -> a.pause()
        }
    }

    private fun updateModes() {
        ui.videoMode = videoMode
        art.mpArtBox.ratio = if (videoMode) 9f / 16f else 1f
        art.mpVideo.isVisible = videoMode
        art.mpArt.isVisible = !videoMode
        // song mode: a round record (the cover spins while it plays); video mode: the normal picture box
        art.mpVinylGrooves.isVisible = !videoMode
        art.mpVinylHole.isVisible = !videoMode
        art.mpArtBox.outlineProvider = if (videoMode) android.view.ViewOutlineProvider.BACKGROUND else vinylOutline
        art.mpArtBox.invalidateOutline()
        if (videoMode) { vinylSpin?.cancel(); art.mpArt.rotation = 0f } else spinVinyl(player()?.isPlaying == true)
        if (!videoMode) art.mpArtLoading.isVisible = false
        val p = player()
        ui.shuffle = p?.shuffleModeEnabled == true
        ui.repeat = p?.repeatMode ?: Player.REPEAT_MODE_OFF
    }

    // ---------- showing the song ----------

    private fun showSong(item: MediaItem) {
        val md = item.mediaMetadata
        val url = item.mediaId
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        ui.title = title
        ui.artist = artist
        updateUpNext()
        val id = youtubeId(url) ?: url
        if (id == shownId) return
        shownId = id
        val thumb = md.extras?.getString(EXTRA_THUMB)
        val big = MusicArt.big(url, thumb)
        Img.loadSquare(art.mpArt, big.firstOrNull(), widthPx = 900, fallbacks = big.drop(1)) { bmp ->
            if (shownId == id) paint(MusicArt.tone(bmp), animate = true)
        }
        ui.miniArt = MusicArt.small(url, thumb)
        songOf(item)?.let { MusicHistory.add(act, it) }
        onNowPlaying(url)
        loadInfo(url)
        if (expanded) loadCommentCount(url)
    }

    private fun updateUpNext() {
        val p = player() ?: return
        val i = p.nextMediaItemIndex
        val next = if (i != C.INDEX_UNSET && i < p.mediaItemCount) p.getMediaItemAt(i).mediaMetadata.title?.toString() else null
        ui.upNext = if (next.isNullOrBlank()) "Up next" else "Up next  ·  $next"
    }

    private fun updatePlayState() {
        val p = player()
        val showPause = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        ui.playing = showPause
        ui.buffering = p != null && p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player()
            updateProgress()
            if (p != null && p.isPlaying && active && onScreen()) handler.postDelayed(this, 500) else ticking = false
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.post(tick)
    }

    private fun duration(): Long {
        val p = player() ?: return 0
        val d = p.duration
        if (d > 0 && d != C.TIME_UNSET) return d
        val s = p.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_SECONDS) ?: 0
        return s * 1000
    }

    private fun updateProgress() {
        val p = player() ?: return
        val d = duration()
        val pos = p.currentPosition.coerceAtLeast(0)
        val part = if (d > 0) (pos * 1000 / d).toInt().coerceIn(0, 1000) else 0
        if (!dragging) {
            ui.part = part
            ui.buffered = if (d > 0) (p.bufferedPosition * 1000 / d).toInt().coerceIn(0, 1000) else 0
            ui.pos = time(pos)
        }
        ui.dur = if (d > 0) time(d) else "0:00"
    }

    /** The seek bar is being dragged: the thumb and the time follow the finger. */
    private fun showSeek(at: Int) {
        ui.part = at
        val d = duration()
        if (d > 0) ui.pos = time(d * at / 1000)
    }

    private fun time(ms: Long) = YtCatalog.duration(ms / 1000).ifBlank { "0:00" }

    // ---------- colours ----------

    private fun paint(to: Int, animate: Boolean) {
        toneAnim?.cancel()
        val from = tone
        tone = to
        onTone(to)
        if (!animate || from == to) {
            applyTone(to)
            return
        }
        toneAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 450
            addUpdateListener { applyTone(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyTone(c: Int) {
        pageBg.colors = intArrayOf(c, MusicArt.darker(c, 0.6f), MusicArt.darker(c, 0.3f))
        ui.miniTone = MusicArt.darker(c, 0.9f)
        if (expanded) setBars(c)
    }

    private var barsSaved = false
    private var savedStatus = 0
    private var savedNav = 0
    private var savedLightStatus = false
    private var savedLightNav = false

    /** The phone's status and navigation bars take the player's colour while it is open. */
    private fun setBars(c: Int) {
        val w = act.window
        val ctl = WindowCompat.getInsetsController(w, w.decorView)
        if (!barsSaved) {
            savedStatus = w.statusBarColor
            savedNav = w.navigationBarColor
            savedLightStatus = ctl.isAppearanceLightStatusBars
            savedLightNav = ctl.isAppearanceLightNavigationBars
            barsSaved = true
        }
        @Suppress("DEPRECATION")
        w.statusBarColor = c
        @Suppress("DEPRECATION")
        w.navigationBarColor = MusicArt.darker(c, 0.3f)
        ctl.isAppearanceLightStatusBars = false
        ctl.isAppearanceLightNavigationBars = false
    }

    private fun restoreBars() {
        if (!barsSaved) return
        barsSaved = false
        val w = act.window
        val ctl = WindowCompat.getInsetsController(w, w.decorView)
        @Suppress("DEPRECATION")
        w.statusBarColor = savedStatus
        @Suppress("DEPRECATION")
        w.navigationBarColor = savedNav
        ctl.isAppearanceLightStatusBars = savedLightStatus
        ctl.isAppearanceLightNavigationBars = savedLightNav
    }

    // ---------- open / close the big player ----------

    fun expand() {
        if (!active || expanded) return
        expanded = true
        val v = pg.root
        v.animate().cancel()
        v.isVisible = true
        val h = (v.height.takeIf { it > 0 } ?: act.resources.displayMetrics.heightPixels).toFloat()
        v.translationY = h
        v.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()
        setBars(tone)
        updateProgress()
        updatePlayState()
        startTicker()
        currentUrl()?.let { loadCommentCount(it) }
        onChanged()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        hidePage(animate = true)
        restoreBars()
        onChanged()
    }

    /** Back: closes the big player first. */
    fun back(): Boolean {
        if (!expanded) return false
        collapse()
        return true
    }

    private fun hidePage(animate: Boolean) {
        val v = pg.root
        v.animate().cancel()
        if (!animate || v.height == 0) {
            v.isVisible = false
            v.translationY = 0f
            return
        }
        v.animate().translationY(v.height.toFloat()).setDuration(220).setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction {
                if (!expanded) {
                    v.isVisible = false
                    v.translationY = 0f
                }
            }.start()
    }

    // ---------- likes and comments ----------

    private fun loadInfo(url: String) {
        infoJob?.cancel()
        likeStatus = null
        likeCount = -1
        showLike()
        ui.commentsText = commentCounts[url] ?: "Comments"
        val id = youtubeId(url) ?: return
        infoJob = act.lifecycleScope.launch {
            val votes = withContext(Dispatchers.IO) { Dislikes.votes(id) }
            if (youtubeId(currentUrl()) != id) return@launch
            if (votes.first > 0) {
                likeCount = votes.first
                showLike()
            }
            if (YtAccount.isSignedIn(act)) {
                val st = withContext(Dispatchers.IO) { runCatching { YtAccount.videoState(id) }.getOrNull() }
                if (st != null && youtubeId(currentUrl()) == id) {
                    likeStatus = st.likeStatus
                    showLike()
                }
            }
        }
    }

    /** The number of comments (looked up only while the big player is open). */
    private fun loadCommentCount(url: String) {
        if (commentCounts.containsKey(url)) {
            ui.commentsText = commentCounts[url] ?: "Comments"
            return
        }
        commentsJob?.cancel()
        commentsJob = act.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(url, null) }.getOrNull() } ?: return@launch
            val text = when {
                page.disabled -> "Off"
                page.total > 0 -> YtCatalog.count(page.total.toLong())
                else -> "Comments"
            }
            if (commentCounts.size > 100) commentCounts.clear()
            commentCounts[url] = text
            if (currentUrl() == url) ui.commentsText = text
        }
    }

    private fun showComments() {
        val s = current() ?: return
        if (commentCounts[s.url] == "Off") {
            toast("Comments are turned off for this song")
            return
        }
        CommentsSheet(act, s.url, signIn).show()
    }

    private fun showLike() {
        ui.liked = likeStatus == "LIKE"
        ui.disliked = likeStatus == "DISLIKE"
        ui.likeText = if (likeCount > 0) YtCatalog.count(likeCount) else "Like"
    }

    /** Like / dislike on YouTube (needs the YouTube account). */
    private fun rate(status: String) {
        if (!YtAccount.isSignedIn(act)) {
            signIn()
            return
        }
        val id = youtubeId(currentUrl()) ?: return
        val before = likeStatus
        val countBefore = likeCount
        likeStatus = status
        if (likeCount >= 0) {
            if (before == "LIKE") likeCount = (likeCount - 1).coerceAtLeast(0)
            if (status == "LIKE") likeCount += 1
        }
        showLike()
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.rate(id, status) }.isSuccess }
            if (!ok && youtubeId(currentUrl()) == id) {
                likeStatus = before
                likeCount = countBefore
                showLike()
                toast("Couldn't save that. Try again")
            }
        }
    }

    // ---------- menus ----------

    private fun playerMenu() {
        val s = current() ?: return
        val sleepAt = MusicQueue.sleepAt
        val sleep = when {
            MusicQueue.sleepEndOfSong -> "End of song"
            sleepAt > System.currentTimeMillis() -> "${((sleepAt - System.currentTimeMillis()) / 60_000 + 1)} min left"
            else -> "Off"
        }
        options(s.title, listOf(
            Opt("Download song", R.drawable.ic_audio) { download(s.url, s.title, true) },
            Opt("Download video", R.drawable.ic_video) { download(s.url, s.title, false) },
            Opt("Start radio", R.drawable.ic_radio) { play(listOf(s), 0, s.title, true) },
            Opt("Up next", R.drawable.ic_playlist) { showQueue() },
            Opt("Sleep timer", R.drawable.ic_timer, value = sleep) { sleepMenu() },
            Opt("Share", R.drawable.ic_share) { share(s) },
            Opt("Close player", R.drawable.ic_close) { close() }
        ))
    }

    /** The sleep timer runs in the player itself (BgPlayService), so it works with the app closed too. */
    private fun sleepMenu() {
        val off = MusicQueue.sleepAt <= System.currentTimeMillis() && !MusicQueue.sleepEndOfSong
        val opts = mutableListOf(Opt("Off", 0, checked = off) { setSleep(0) })
        listOf(10, 15, 30, 45, 60).forEach { m -> opts += Opt("$m minutes", 0) { setSleep(m) } }
        opts += Opt("End of this song", 0, checked = MusicQueue.sleepEndOfSong) {
            setSleep(0, quiet = true)
            MusicQueue.sleepEndOfSong = true
            toast("Music stops after this song")
        }
        options("Sleep timer", opts)
    }

    private fun setSleep(minutes: Int, quiet: Boolean = false) {
        MusicQueue.sleepEndOfSong = false
        MusicQueue.sleepAt = 0
        if (minutes > 0) {
            MusicQueue.sleepAt = System.currentTimeMillis() + minutes * 60_000L
            toast("Music stops in $minutes minutes")
        } else if (!quiet) {
            toast("Sleep timer off")
        }
    }

    private class Opt(
        val text: String,
        val icon: Int,
        val value: String? = null,
        val checked: Boolean = false,
        val action: () -> Unit
    )

    private fun options(title: String, opts: List<Opt>) {
        RxOptions.sheet(act, title, opts.map { RxOption(it.text, it.icon, it.value, it.checked, it.action) })
    }

    private fun share(s: VideoItem) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, listOf(s.title, s.url).filter { it.isNotBlank() }.joinToString("\n"))
        runCatching { act.startActivity(Intent.createChooser(send, "Share song")) }
    }

    // ---------- Up next ----------

    private fun showQueue() {
        if (player() == null || !active) return
        if (queueSheet?.isShowing == true) return
        queueSheet = QueueSheet().also { it.show() }
    }

    /** The songs in the order they will play (shuffled order when shuffle is on). */
    private fun queueOrder(p: Player): List<Int> {
        val t = p.currentTimeline
        if (t.isEmpty) return emptyList()
        val out = ArrayList<Int>()
        var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return out
    }

    private inner class QueueSheet {
        private val ui = QueueUi()
        private val dialog = RxSheetDialog(act)
        val isShowing get() = dialog.isShowing

        fun show() {
            val view = androidx.compose.ui.platform.ComposeView(act)
            val actions = QueueActions(
                shuffle = { player()?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                repeat = { cycleRepeat() },
                play = { index ->
                    player()?.let { p -> if (index < p.mediaItemCount) { p.seekTo(index, 0); p.play() } }
                },
                remove = { index ->
                    player()?.let { p -> if (index < p.mediaItemCount && index != p.currentMediaItemIndex) p.removeMediaItem(index) }
                }
            )
            view.setContent { RxTheme(glass = true) { QueueScreen(ui, actions) } }
            dialog.setContentView(view)
            dialog.setOnDismissListener { if (queueSheet === this) queueSheet = null }
            dialog.behavior.peekHeight = (act.resources.displayMetrics.heightPixels * 0.6).toInt()
            refresh()
            dialog.show()
            val p = player() ?: return
            val at = ui.songs.indexOfFirst { it.index == p.currentMediaItemIndex }
            if (at > 2) ui.jump = at - 1
        }

        fun dismiss() = dialog.dismiss()

        fun refresh() {
            val p = player() ?: return
            ui.from = queueName.ifBlank { "Your music" }
            ui.shuffle = p.shuffleModeEnabled
            ui.repeat = p.repeatMode
            ui.now = p.currentMediaItemIndex
            ui.songs = queueOrder(p).filter { it < p.mediaItemCount }.map { index ->
                val item = p.getMediaItemAt(index)
                val s = songOf(item)
                QueueSong(index, s?.title.orEmpty(), s?.let { MusicHome.songLine(it) }.orEmpty(), MusicArt.small(item.mediaId, s?.thumb))
            }
        }
    }

    // ---------- helpers ----------

    private fun songOf(item: MediaItem?): VideoItem? = MusicQueue.songOf(item)

    /** Lyrics of the song playing, from YouTube Music (with its source line), in a dark sheet. */
    private fun showLyrics() {
        val song = current() ?: currentUrl()?.let { VideoItem(it, ui.title, ui.artist, null, 0, -1, null) }
        val id = song?.let { youtubeId(it.url) }
        if (song == null || id == null) { toast("Lyrics are only for songs from YouTube"); return }
        val ui = LyricsUi()
        ui.title = song.title
        ui.artist = song.uploader
        val dialog = RxSheetDialog(act)
        val view = androidx.compose.ui.platform.ComposeView(act)
        view.setContent { RxTheme { LyricsScreen(ui) } }
        dialog.setContentView(view)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
        val job = act.lifecycleScope.launch {
            val res: Result<YtLyrics.Lyrics?> = try {
                Result.success(withContext(Dispatchers.IO) { YtLyrics.get(id) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.loading = false
            val ly = res.getOrNull()
            when {
                ly != null -> {
                    ui.text = ly.text
                    ui.source = ly.source
                }
                res.isFailure -> ui.empty = "Couldn't load the lyrics. Check your internet and try again"
                else -> ui.empty = "YouTube Music has no lyrics for this song"
            }
        }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun current(): VideoItem? = player()?.currentMediaItem?.takeIf { isMusic(it) }?.let { songOf(it) }

    private fun currentUrl(): String? = player()?.currentMediaItem?.mediaId

    private fun toast(text: String) = Notify.show(act, text)

    companion object {
        /** Marks player entries that belong to the Music player. */
        const val EXTRA_MUSIC = "rainax_music"
        /** The entry plays the song's video (picture + sound) instead of only its sound. */
        const val EXTRA_PICTURE = "rainax_music_video"
        const val EXTRA_THUMB = "rainax_music_thumb"
        const val EXTRA_SECONDS = "rainax_music_seconds"

        fun isMusic(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_MUSIC) == true

        fun hasPicture(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_PICTURE) == true
    }
}
