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
        header.vLike.setOnClickListener { rate(if (likeStatus == "LIKE") "INDIFFERENT" else "LIKE") }
        header.vDislike.setOnClickListener { rate(if (likeStatus == "DISLIKE") "INDIFFERENT" else "DISLIKE") }
        header.vSave.setOnClickListener { saveWatchLater() }
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
        header.vLike.text = if (d.likes > 0) YtCatalog.count(d.likes) else "Like"
        listOf(header.vLike, header.vDislike, header.vSave).forEach {
            it.isVisible = signedIn
            Ui.toggleButton(it, false)
        }
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
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.rate(id, status) }.isSuccess }
            if (!ok) {
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
        thumbUrl = thumb ?: youtubeThumb(clean)
        titleText = title
        uploaderText = uploader
        details = null
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
        listOf(header.vSubscribe, header.vLike, header.vDislike, header.vSave).forEach { it.isVisible = false }
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
                if (!keep) play(d, startMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@VideoScreen.url != clean) return@launch
                header.vLoading.isVisible = false
                vb.playerLoading.isVisible = false
                showError((e.message ?: "Couldn't open this video") + "\nYou can still try Download.")
            }
        }
        loadComments(clean)
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

    /** The page is folded into the mini player above the bottom bar (the video keeps playing). */
    var minimized = false
        private set
    var thumbUrl: String? = null
        private set
    private var titleText: String? = null
    private var uploaderText: String? = null

    fun miniTitle(): String = details?.title ?: titleText.orEmpty()
    fun miniSub(): String = details?.uploader ?: uploaderText.orEmpty()

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
        // no picture while folded: saves battery and data, the sound goes on
        player()?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, on).build()
        }
        onChanged()
    }

    fun close() {
        if (fullscreen) exitFullscreen()
        if (minimized) setMinimized(false)
        job?.cancel()
        commentsJob?.cancel()
        url = null
        details = null
        player()?.run {
            stop()
            clearMediaItems()
        }
        onChanged()
    }

    fun showError(text: String) {
        header.vError.text = text
        header.vError.isVisible = true
    }

    // ---------- playback ----------

    private fun play(d: VideoDetails, startMs: Long) {
        val p = player() ?: run { showError("The player is starting. Tap the video again in a moment."); return }
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
        if (thumbUrl == null) thumbUrl = d.thumb
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
        onChanged()                            // the mini player shows the real title
    }

    private fun loadComments(clean: String) {
        commentsJob?.cancel()
        commentsJob = act.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { YtCatalog.comments(clean) }
            // your own comment first (YouTube shows it on top to you)
            val list = videoId(clean)?.let { MyComments.forVideo(act, it) }.orEmpty() + fetched
            if (url != clean || list.isEmpty()) return@launch
            val first = list.first()
            Img.load(header.vCommentAvatar, first.avatar, circle = true, widthPx = 80)
            header.vCommentText.text = plain(first)
            header.vComments.isVisible = true
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    private fun showComments() {
        val u = url ?: return
        CommentsSheet(act, u, signIn).show()
    }

    /** Full description with likes, views, upload date and clickable links. */
    private fun showDescription() {
        val d = details ?: return
        val sb = com.rainax.ytdownloader.databinding.SheetDescriptionBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        sb.descTitle.text = d.title
        sb.descLikes.text = if (d.likes > 0) YtCatalog.count(d.likes) else "–"
        sb.descViews.text = if (d.views >= 0) java.text.NumberFormat.getIntegerInstance().format(d.views) else "–"
        sb.descDate.text = d.uploaded ?: "–"
        val desc = d.description.trim()
        sb.descText.text = when {
            desc.isEmpty() -> "No description"
            desc.contains('<') -> HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT)
            else -> desc
        }
        sb.descText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        if (!desc.contains('<')) android.text.util.Linkify.addLinks(sb.descText, android.text.util.Linkify.WEB_URLS)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
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
            Opt("Playback speed", speedLabel(speed), R.drawable.ic_speed) { showSpeedMenu() }
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
