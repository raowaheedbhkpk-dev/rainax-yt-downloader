package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.rainax.ytdownloader.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RAINAX player: plays finished downloads (and any video/audio file opened with the app).
 * Swipe left side = brightness, right side = volume, sideways = seek, double tap = ±10 s, pinch = zoom,
 * speed, aspect ratio, rotation, lock, audio/subtitle tracks, external .srt, picture-in-picture, resume, playlist.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var uris = arrayListOf<String>()
    private var titles = arrayListOf<String>()
    private var watching: String? = null           // download being watched while it downloads
    private var partialRetries = 0

    private fun stopWatching() {
        watching?.let { PartialFiles.unwatch(it) }
        watching = null
    }

    /** The download being watched was saved: keep playing from the saved file. */
    private fun switchToSaved(id: String) {
        val t = TaskRepository.get(id)
        val uri = t?.fileUri
        val p = player
        if (uri == null || p == null) { info("The download finished. Open it from Downloads"); return }
        val pos = p.currentPosition
        stopWatching()
        uris = arrayListOf(uri)
        titles = arrayListOf(t?.title.orEmpty())
        p.setMediaItem(buildItem(0), pos)
        p.prepare()
        p.play()
    }
    private val extraSubs = mutableMapOf<Int, Uri>()   // item index -> external subtitle file

    private var locked = false
    private var internalNav = false        // our own picker/share screen is opening: don't jump into PiP
    private var wasInPip = false
    private var pipExitAt = 0L
    private var userSeeking = false
    private var resizeIndex = 0
    private var rotateMode = 0             // 0 auto (by video), 1 landscape, 2 portrait
    private var autoOriented = false
    private var videoW = 16
    private var videoH = 9

    // ---------- gestures ----------
    private enum class Mode { NONE, SEEK, VOLUME, BRIGHT, SCALE }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startVolume = 0
    private var startBright = 0.5f
    private var startPos = 0L
    private var seekTarget = 0L
    private var scaleAcc = 1f
    private val touchSlop by lazy { 24 * resources.displayMetrics.density }

    private val hideControls = Runnable { showControls(false) }
    private val hideInfo = Runnable { b.gestureInfo.isVisible = false }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addExternalSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        goImmersive()
        applyInsets()
        setupButtons()
        setupGestures()
        if (!readIntent(intent)) {
            finish()
            return
        }
        val restored = savedInstanceState?.takeIf { it.containsKey("index") }
        startPlayer(
            restored?.getInt("index") ?: intent.getIntExtra(EXTRA_INDEX, 0),
            restored?.getLong("pos") ?: -1L
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readIntent(intent)) {
            savePosition()
            extraSubs.clear()
            autoOriented = false
            player?.release()
            player = null
            startPlayer(intent.getIntExtra(EXTRA_INDEX, 0), -1L)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putInt("index", it.currentMediaItemIndex)
            outState.putLong("pos", it.currentPosition)
        }
    }

    /** Reads the list to play: from the app (several files) or from another app (one file). */
    private fun readIntent(i: Intent): Boolean {
        val partial = i.getStringExtra(EXTRA_PARTIAL)
        if (partial != null) {
            PartialFiles.watch(partial)                // its files stay until this player closes
            watching?.let { PartialFiles.unwatch(it) }
            watching = partial
            partialRetries = 0
            uris = arrayListOf(PartialPlayback.uri(partial))
            titles = arrayListOf(i.getStringExtra(EXTRA_PARTIAL_TITLE).orEmpty())
            return true
        }
        val list = i.getStringArrayListExtra(EXTRA_URIS)
        if (!list.isNullOrEmpty()) {
            stopWatching()
            uris = list
            titles = i.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
            return true
        }
        val data = i.data ?: return false
        stopWatching()
        uris = arrayListOf(data.toString())
        titles = arrayListOf(displayName(data))
        return true
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
    }

    // ---------- player ----------

    private fun buildItem(index: Int): MediaItem {
        val title = titles.getOrNull(index).orEmpty().ifBlank { "Video" }
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(uris[index]))
            .setMediaId(uris[index])
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        extraSubs[index]?.let { sub ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(sub)
                        .setMimeType(subtitleMime(sub))
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder.build()
    }

    private fun subtitleMime(uri: Uri): String {
        val name = displayName(uri).lowercase()
        return when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun startPlayer(index: Int, posMs: Long) {
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(PartialMediaSourceFactory(this))     // also plays downloads in progress
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = p
        b.playerView.player = p
        p.addListener(listener)

        val start = index.coerceIn(0, uris.size - 1)
        val items = uris.indices.map { buildItem(it) }
        val resumeAt = if (posMs >= 0) posMs else savedPosition(uris[start])
        p.setMediaItems(items, start, resumeAt.coerceAtLeast(0L))
        p.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        p.setPlaybackSpeed(prefs.getFloat("speed", 1f))
        p.prepare()
        p.playWhenReady = true
        if (resumeAt > 5000) info("Resumed at ${fmt(resumeAt)}")
        updateSpeedLabel()
        updateTitle()
        updateNavButtons()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        bumpControls()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) partialRetries = 0
            b.playerView.keepScreenOn = isPlaying          // screen may sleep while paused
            b.playBtn.setImageResource(if (isPlaying) R.drawable.ic_p_pause else R.drawable.ic_p_play)
            if (isPlaying) bumpControls() else {
                handler.removeCallbacks(hideControls)
                if (!locked && !isInPip()) showControls(true)
            }
            updatePipParams()
        }

        override fun onPlaybackStateChanged(state: Int) {
            b.buffering.isVisible = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) {
                player?.let { prefs.edit().remove(posKey(it.currentMediaItem?.mediaId.orEmpty())).apply() }
                showControls(true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            autoOriented = false
            updateTitle()
            updateNavButtons()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val saved = savedPosition(mediaItem?.mediaId.orEmpty())
                if (saved > 5000) player?.seekTo(saved)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // finished one item and moved on by itself: forget its resume point
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                oldPosition.mediaItem?.mediaId?.let { prefs.edit().remove(posKey(it)).apply() }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            videoW = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt().coerceAtLeast(1)
            videoH = videoSize.height
            if (rotateMode == 0 && !autoOriented) {
                autoOriented = true
                requestedOrientation = if (videoW > videoH) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            updatePipParams()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val hasVideo = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
            val hasAny = !tracks.isEmpty
            b.audioArt.isVisible = hasAny && !hasVideo
            b.audioTitle.text = currentTitle()
            b.aspectBtn.isVisible = hasVideo
            b.pipBtn.isVisible = hasVideo && pipSupported()
            b.subsBtn.alpha = if (tracks.isTypeSupported(C.TRACK_TYPE_TEXT)) 1f else 0.6f
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = watching
            if (id != null) {
                val msgs = generateSequence(error as Throwable) { it.cause }.mapNotNull { it.message }.toList()
                when {
                    // saved meanwhile: go on with the saved file from the same spot
                    PartialDataSource.FINISHED in msgs -> { switchToSaved(id); return }
                    // paused / stopped / removed: say so (play again after resuming)
                    msgs.any { it.startsWith("The download") || it.startsWith("This download") } -> {
                        info(msgs.first { it.startsWith("The download") || it.startsWith("This download") })
                        return
                    }
                    // the download restarted or is still coming in: try again by itself
                    error.errorCode in 2000..2999 && partialRetries < 10 -> {
                        partialRetries++
                        if (PartialDataSource.RESTARTED !in msgs) info("Waiting for more of the download…")
                        handler.postDelayed({ player?.takeIf { it.playbackState == Player.STATE_IDLE }?.prepare() }, 1500)
                        return
                    }
                }
            }
            showError(error.errorCodeName)
        }
    }

    private fun showError(code: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Can't play this file")
            .setMessage("This file could not be played here ($code).")
            .setPositiveButton("Open with another app") { _, _ -> openExternally() }
            .setNegativeButton("Close") { _, _ -> finish() }
            .show()
    }

    private fun openExternally() {
        val p = player ?: return
        val uri = p.currentMediaItem?.mediaId ?: return
        if (watching != null) { info("Available when the download finishes"); return }
        try {
            internalNav = true
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), contentResolver.getType(Uri.parse(uri)) ?: "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Open with"
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No other player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentTitle(): String =
        player?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty().ifBlank { "Video" }

    private fun updateTitle() {
        b.titleText.text = currentTitle()
        b.audioTitle.text = currentTitle()
    }

    private fun updateNavButtons() {
        val many = uris.size > 1
        b.prevBtn.isVisible = many
        b.nextBtn.isVisible = many
        player?.let {
            b.prevBtn.alpha = if (it.hasPreviousMediaItem()) 1f else 0.4f
            b.nextBtn.alpha = if (it.hasNextMediaItem()) 1f else 0.4f
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition
        if (!userSeeking) {
            b.posText.text = fmt(pos)
            b.seekBar.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
            b.seekBar.secondaryProgress = if (dur > 0) (p.bufferedPosition * 1000 / dur).toInt() else 0
        }
        b.durText.text = if (dur > 0) fmt(dur) else "--:--"
    }

    // ---------- buttons ----------

    private fun setupButtons() {
        b.backBtn.setOnClickListener { finish() }
        b.playBtn.setOnClickListener { togglePlay() }
        b.rewBtn.setOnClickListener { seekBy(-10_000) }
        b.fwdBtn.setOnClickListener { seekBy(10_000) }
        b.prevBtn.setOnClickListener {
            player?.let { if (it.hasPreviousMediaItem()) { savePosition(); it.seekToPreviousMediaItem() } }
            bumpControls()
        }
        b.nextBtn.setOnClickListener {
            player?.let { if (it.hasNextMediaItem()) { savePosition(); it.seekToNextMediaItem() } }
            bumpControls()
        }
        b.speedBtn.setOnClickListener { chooseSpeed() }
        b.audioBtn.setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO, "Audio track") }
        b.subsBtn.setOnClickListener { chooseSubtitles() }
        b.moreBtn.setOnClickListener { moreMenu() }
        b.lockBtn.setOnClickListener { setLocked(true) }
        b.unlockBtn.setOnClickListener { setLocked(false) }
        b.aspectBtn.setOnClickListener { cycleAspect() }
        b.rotateBtn.setOnClickListener { cycleRotation() }
        b.pipBtn.setOnClickListener { enterPip() }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = player?.duration?.takeIf { it > 0 } ?: return
                b.posText.text = fmt(dur * progress / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val p = player ?: return
                val dur = p.duration.takeIf { it > 0 } ?: return
                p.seekTo(dur * (sb?.progress ?: 0) / 1000)
                bumpControls()
            }
        })
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()      // after an error (e.g. a paused download resumed)
        if (p.isPlaying) p.pause() else p.play()
        bumpControls()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (p.currentPosition + ms).coerceIn(0, dur)
        p.seekTo(target)
        info((if (ms > 0) "+" else "−") + "${abs(ms) / 1000}s   ${fmt(target)}")
        bumpControls()
    }

    private fun chooseSpeed() {
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = speeds.map { speedLabel(it) }.toTypedArray()
        val cur = player?.playbackParameters?.speed ?: 1f
        val checked = speeds.indexOfFirst { abs(it - cur) < 0.01f }
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { d, which ->
                player?.setPlaybackSpeed(speeds[which])
                prefs.edit().putFloat("speed", speeds[which]).apply()
                updateSpeedLabel()
                d.dismiss()
            }
            .show()
    }

    private fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

    private fun updateSpeedLabel() {
        b.speedBtn.text = speedLabel(player?.playbackParameters?.speed ?: 1f)
    }

    private fun chooseTrack(type: Int, title: String) {
        val p = player ?: return
        val has = p.currentTracks.groups.any { it.type == type }
        if (!has) {
            Toast.makeText(this, if (type == C.TRACK_TYPE_AUDIO) "Only one audio track" else "No subtitles in this file", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            TrackSelectionDialogBuilder(this, title, p, type)
                .setShowDisableOption(type == C.TRACK_TYPE_TEXT)
                .setAllowAdaptiveSelections(false)
                .build()
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't change tracks here", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseSubtitles() {
        val p = player ?: return
        val has = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        val options = if (has) arrayOf("Choose subtitle track", "Load subtitle file (.srt / .vtt)")
        else arrayOf("Load subtitle file (.srt / .vtt)")
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(options) { _, which ->
                if (has && which == 0) chooseTrack(C.TRACK_TYPE_TEXT, "Subtitles") else loadSubtitleFile()
            }
            .show()
    }

    private fun loadSubtitleFile() {
        try {
            internalNav = true
            pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "This phone has no file picker", Toast.LENGTH_SHORT).show()
        }
    }

    /** Re-opens the current item with the chosen subtitle file, at the same second. */
    private fun addExternalSubtitle(uri: Uri) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val pos = p.currentPosition
        val playing = p.playWhenReady
        extraSubs[index] = uri
        p.replaceMediaItem(index, buildItem(index))
        p.seekTo(index, pos)
        p.playWhenReady = playing
        info("Subtitles loaded")
    }

    private fun moreMenu() {
        val p = player ?: return
        val repeatLabel = when (p.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat: this video"
            Player.REPEAT_MODE_ALL -> "Repeat: all"
            else -> "Repeat: off"
        }
        val items = arrayOf(repeatLabel, "Open with another app", "Share")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val next = when (p.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        p.repeatMode = next
                        prefs.edit().putInt("repeat", next).apply()
                        info(
                            when (next) {
                                Player.REPEAT_MODE_ONE -> "Repeat this video"
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                else -> "Repeat off"
                            }
                        )
                    }
                    1 -> openExternally()
                    2 -> share()
                }
            }
            .show()
    }

    private fun share() {
        val uri = player?.currentMediaItem?.mediaId ?: return
        if (watching != null) { info("You can share it when the download finishes"); return }
        try {
            internalNav = true
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(contentResolver.getType(Uri.parse(uri)) ?: "video/*")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Share"
                )
            )
        } catch (e: Exception) { }
    }

    private fun cycleAspect() {
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        val names = arrayOf("Fit to screen", "Crop (fill screen)", "Stretch")
        resizeIndex = (resizeIndex + 1) % modes.size
        b.playerView.resizeMode = modes[resizeIndex]
        info(names[resizeIndex])
        bumpControls()
    }

    private fun cycleRotation() {
        rotateMode = (rotateMode + 1) % 3
        when (rotateMode) {
            1 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; info("Landscape") }
            2 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; info("Portrait") }
            else -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; info("Auto rotate") }
        }
        bumpControls()
    }

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            showControls(false)
            b.unlockBtn.isVisible = true
            handler.postDelayed({ if (locked) b.unlockBtn.isVisible = false }, 2500)
            info("Screen locked")
        } else {
            b.unlockBtn.isVisible = false
            showControls(true)
            info("Unlocked")
        }
    }

    // ---------- controls visibility ----------

    private fun showControls(show: Boolean) {
        if (locked && show) return
        b.controls.animate().cancel()
        if (show) {
            b.controls.isVisible = true
            b.controls.animate().alpha(1f).setDuration(150).start()
        } else {
            b.controls.animate().alpha(0f).setDuration(200).withEndAction { b.controls.isVisible = false }.start()
        }
    }

    /** Shows the controls and hides them again after a moment while the video plays. */
    private fun bumpControls() {
        if (locked || isInPip()) return
        showControls(true)
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true) handler.postDelayed(hideControls, 3500)
    }

    private fun info(text: String) {
        b.gestureInfo.text = text
        b.gestureInfo.isVisible = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 900)
    }

    // ---------- gestures ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    b.unlockBtn.isVisible = !b.unlockBtn.isVisible
                    if (b.unlockBtn.isVisible) handler.postDelayed({ if (locked) b.unlockBtn.isVisible = false }, 2500)
                    return true
                }
                if (b.controls.isVisible && b.controls.alpha > 0.5f) showControls(false) else bumpControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = b.playerRoot.width
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2 / 3f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }
        })
        val scaler = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (locked) return false
                mode = Mode.SCALE
                scaleAcc = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleAcc *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scaleAcc > 1.1f) {
                    resizeIndex = 1
                    b.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    info("Zoomed to fill")
                } else if (scaleAcc < 0.9f) {
                    resizeIndex = 0
                    b.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    info("Fit to screen")
                }
            }
        })

        b.playerRoot.setOnTouchListener { _, e ->
            scaler.onTouchEvent(e)
            if (mode != Mode.SCALE) taps.onTouchEvent(e)
            if (!locked && e.pointerCount == 1) handleSwipe(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) {
                    player?.seekTo(seekTarget)
                    bumpControls()
                }
                if (mode != Mode.NONE) handler.postDelayed(hideInfo, 600)
                mode = Mode.NONE
            }
            true
        }
    }

    private fun handleSwipe(e: MotionEvent) {
        val w = b.playerRoot.width.toFloat().coerceAtLeast(1f)
        val h = b.playerRoot.height.toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                mode = Mode.NONE
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val cur = window.attributes.screenBrightness
                startBright = if (cur in 0f..1f) cur else systemBrightness()
                startPos = player?.currentPosition ?: 0L
                seekTarget = startPos
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (mode == Mode.NONE) {
                    // keep clear of the system gesture edges
                    if (downY < h * 0.07f || downY > h * 0.93f) return
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.3f) mode = Mode.SEEK
                    else if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.3f) mode = if (downX < w / 2) Mode.BRIGHT else Mode.VOLUME
                    if (mode != Mode.NONE) handler.removeCallbacks(hideInfo)
                }
                when (mode) {
                    Mode.SEEK -> {
                        val p = player ?: return
                        val dur = p.duration.takeIf { it > 0 } ?: return
                        val delta = (dx / w * minOf(dur, 180_000L)).toLong()
                        seekTarget = (startPos + delta).coerceIn(0, dur)
                        val sign = if (delta >= 0) "+" else "−"
                        b.gestureInfo.text = "$sign${fmt(abs(delta))}\n${fmt(seekTarget)} / ${fmt(dur)}"
                        b.gestureInfo.isVisible = true
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (startVolume - dy / h * max * 1.4f).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        b.gestureInfo.text = "Volume  ${v * 100 / maxOf(max, 1)}%"
                        b.gestureInfo.isVisible = true
                    }
                    Mode.BRIGHT -> {
                        val v = (startBright - dy / h * 1.4f).coerceIn(0.01f, 1f)
                        val lp = window.attributes
                        lp.screenBrightness = v
                        window.attributes = lp
                        b.gestureInfo.text = "Brightness  ${(v * 100).roundToInt()}%"
                        b.gestureInfo.isVisible = true
                    }
                    else -> {}
                }
            }
        }
    }

    private fun systemBrightness(): Float = try {
        android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (e: Exception) {
        0.5f
    }

    // ---------- picture-in-picture ----------

    private fun pipSupported() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isInPip() = isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams {
        val ratio = (videoW.toFloat() / videoH).coerceIn(0.42f, 2.38f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .build()
    }

    private fun updatePipParams() {
        if (!pipSupported()) return
        try { setPictureInPictureParams(pipParams()) } catch (e: Exception) { }
    }

    private fun enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, "Picture-in-picture is not available on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (internalNav) return
        // Home pressed while a video plays: keep watching in a small window
        val p = player ?: return
        if (p.isPlaying && p.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (wasInPip && !isInPictureInPictureMode) pipExitAt = System.currentTimeMillis()
        wasInPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            showControls(false)
            b.gestureInfo.isVisible = false
            b.unlockBtn.isVisible = false
        } else if (!locked) {
            bumpControls()
        }
    }

    // ---------- window ----------

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Keeps the buttons away from notches and rounded corners. */
    private fun applyInsets() {
        val top = b.topBar.paddingTop
        val bottom = b.bottomBar.paddingBottom
        val side = b.topBar.paddingLeft
        val bSide = b.bottomBar.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(b.controls) { _, insets ->
            val i = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.topBar.updatePadding(left = side + i.left, top = top + i.top, right = side + i.right)
            b.bottomBar.updatePadding(left = bSide + i.left, bottom = bottom + i.bottom, right = bSide + i.right)
            insets
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    // ---------- resume positions ----------

    private fun posKey(id: String) = "pos_" + id.hashCode()

    private fun savedPosition(id: String): Long = prefs.getLong(posKey(id), 0L)

    private fun savePosition() {
        val p = player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        val dur = p.duration
        val pos = p.currentPosition
        val e = prefs.edit()
        if (dur > 0 && (pos < 5000 || pos > dur - 5000)) e.remove(posKey(id)) else e.putLong(posKey(id), pos)
        e.apply()
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        internalNav = false
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        // Closed the small window or left the app: stop the sound
        player?.pause()
        // The picture-in-picture window was closed: end the player instead of leaving it hidden
        // (turning the screen off while in the small window keeps it)
        val screenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        if (screenOn && (wasInPip || System.currentTimeMillis() - pipExitAt < 1500)) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        player?.removeListener(listener)
        player?.release()
        player = null
        stopWatching()
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        const val EXTRA_PARTIAL = "partial_task"
        const val EXTRA_PARTIAL_TITLE = "partial_title"

        /** Watch a download while it is still downloading. */
        fun openPartial(context: Context, taskId: String, title: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_PARTIAL, taskId)
                    .putExtra(EXTRA_PARTIAL_TITLE, title)
            )
        }

        fun open(context: Context, uris: List<String>, titles: List<String>, index: Int) {
            val i = Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(i)
        }
    }
}
