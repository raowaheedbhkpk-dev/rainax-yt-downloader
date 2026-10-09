package com.rainax.ytdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rainax.ytdownloader.databinding.ActivityMainBinding
import com.rainax.ytdownloader.databinding.SheetDownloadBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val hm get() = b.homePage
    private val vp get() = b.videoPage
    private val pl get() = b.playPage
    private val st get() = b.settingsPage

    private var tab = 0            // bottom navigation: 0 Home, 1 Library, 2 Settings
    private lateinit var home: HomeScreen
    private lateinit var video: VideoScreen
    private var pendingVideo: String? = null     // reopen this video page once the player is connected

    private var latestTasks: List<DownloadTask> = emptyList()
    private var selecting = false
    private val selected = linkedSetOf<String>()
    private var sheet: BottomSheetDialog? = null

    private val activeAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openOrWatch(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )
    private val doneAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openOrWatch(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )

    private val signIn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            home.onAccountChanged()
            message("Signed in to YouTube")
            refreshAccount()
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                AppPrefs.setSaveTree(this, uri.toString())
                renderFolder()
                message("New downloads will be saved in this folder")
            } catch (e: Exception) {
                message("That folder can't be used. Pick another one.")
            }
        }


    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                video.fullscreen -> video.exitFullscreen()
                selecting && tab == 1 -> exitSelection()
                tab != 0 -> b.bottomNav.selectedItemId = R.id.nav_home
                video.isOpen && !video.minimized -> video.minimize()
                home.back() -> {}
                else -> {                    // nothing to go back to: leave the app normally
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    /** A modified copy signed by someone else: nothing runs, only the "not official" message shows. */
    private var blocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)   // pure black
        if (!Integrity.isOfficial(this)) {
            blocked = true
            super.onCreate(null)
            Integrity.showNotOfficial(this)
            return
        }
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Phone makers (MIUI, ColorOS, ...) must not re-colour our screens: the app owns its light and dark themes
        b.root.isForceDarkAllowed = false
        onBackPressedDispatcher.addCallback(this, backCallback)

        TaskRepository.init(applicationContext)
        if (AppPrefs.autoClear(this)) {
            TaskRepository.removeDoneOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
        }
        if (savedInstanceState == null) requestNotificationPermission()

        home = HomeScreen(this, hm, vm, { openItem(it) }, { showDownloadSheet(listOf(it.url), knownTitle = it.title.ifBlank { null }) }) { onAccountClick() }
        home.setup()
        video = VideoScreen(
            this, vp, vm, { controller },
            download = { u, t, audio -> showDownloadSheet(listOf(u), preferAudio = audio, knownTitle = t) },
            openChannel = { url, name, avatar ->
                video.minimize()                     // keeps playing in the mini player
                if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
                home.openChannel(url, name, avatar)
            },
            signIn = { onAccountClick() }
        ) { updateChrome() }
        video.setup()
        setupMiniPlayer()
        setupPlay()
        setupSettings()

        b.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> { showTab(0); true }
                R.id.nav_downloads -> { showTab(1); true }
                R.id.nav_settings -> { showTab(2); true }
                else -> false
            }
        }

        // Anything left in the queue (app or service was killed) continues automatically
        val pending = TaskRepository.tasks.value.any {
            it.status == Status.QUEUED || it.status == Status.RUNNING || it.status == Status.WAITING
        }
        if (pending) DownloadService.send(this, DownloadService.ACTION_PUMP)

        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            // Theme switch rebuilds the screen: reopen the video page (the player keeps playing)
            pendingVideo = savedInstanceState.getString("videoUrl")
            b.bottomNav.selectedItemId = when (savedInstanceState.getInt("tab", 0)) {
                1 -> R.id.nav_downloads
                2 -> R.id.nav_settings
                else -> R.id.nav_home
            }
        }

        if (savedInstanceState == null) refreshAccount()

        // Ads: consent form where the law needs it, then the banner above the bottom bar
        Ads.start(this) {
            if (isFinishing || isDestroyed) return@start
            Ads.showBanner(this, b.adBanner) {
                updateChrome()
                if (b.miniPlayer.root.isVisible) b.adBanner.post { placeMiniPlayer() }   // move off the banner
            }
            st.adPrivacyBtn.isVisible = Ads.privacyOptionsNeeded(this)
            // app open ad on start (not on the very first start, and never over a video or a sheet)
            if (savedInstanceState == null && launchCount() > 1 && canShowFullScreenAd()) Ads.showAppOpenSoon(this)
        }

        // New RAINAX version? (quiet check, a few seconds after start)
        if (savedInstanceState == null) b.root.postDelayed({ if (!isFinishing) AppUpdater.checkOnStart(this) }, 4000)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TaskRepository.tasks.collect { renderTasks(it) } }
                launch {
                    vm.events.collect {
                        message(it)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (blocked) return
        outState.putInt("tab", tab)
        video.url?.let { outState.putString("videoUrl", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (blocked) return
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (blocked) return
        // turning the phone sideways on the video page opens the video full screen
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && tab == 0) {
            video.enterFullscreen()
        }
    }

    override fun onStart() {
        super.onStart()
        if (blocked) return
        if (controller == null) connectPlayer() else backInApp()
    }

    override fun onStop() {
        if (!blocked && !isChangingConfigurations) leaveApp()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (blocked) return
        AppUpdater.resumeInstall(this)
        Ads.resumeBanner(b.adBanner)
        Ads.onScreenResumed(this) { canShowFullScreenAd() }
    }

    override fun onPause() {
        if (!blocked) {
            Ads.pauseBanner(b.adBanner)
            Ads.onScreenPaused(this)
        }
        super.onPause()
    }

    /** A full-screen ad now would not interrupt anything: no video playing, no full screen, no sheet open. */
    private fun canShowFullScreenAd(): Boolean =
        !blocked && controller?.isPlaying != true && !video.fullscreen && sheet?.isShowing != true

    /** How many times the app was started (counted once per start). */
    private var launches = -1
    private fun launchCount(): Int {
        if (launches < 0) {
            val p = getSharedPreferences("app", Context.MODE_PRIVATE)
            launches = p.getInt("launches", 0) + 1
            p.edit().putInt("launches", launches).apply()
        }
        return launches
    }

    override fun onDestroy() {
        if (!blocked) {
            disconnectPlayer()            // the player service keeps playing on its own
            sheet?.dismiss()
            Ads.destroyBanner(b.adBanner)
        }
        super.onDestroy()
    }

    // ----- YouTube account -----

    private fun onAccountClick() {
        if (!YtAccount.isSignedIn(this)) {
            signIn.launch(Intent(this, SignInActivity::class.java))
            return
        }
        val sb = com.rainax.ytdownloader.databinding.SheetAccountBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)
        val p = YtAccount.profile(this)
        sb.accName.text = p?.name ?: "Signed in"
        sb.accHandle.text = p?.handle.orEmpty()
        sb.accHandle.isVisible = !p?.handle.isNullOrBlank()
        Img.load(sb.accAvatar, p?.avatar, circle = true, widthPx = 200)
        sb.accSignOut.setOnClickListener {
            dialog.dismiss()
            YtAccount.signOut(this)
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            home.onAccountChanged()
            message("Signed out of YouTube")
        }
        dialog.show()
    }

    /** Name and picture of the signed-in account (top bar). */
    private fun refreshAccount() {
        if (!YtAccount.isSignedIn(this)) return
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { YtAccount.refreshProfile(this@MainActivity) }.getOrNull()
            }
            if (ok != null) home.updateAccountIcon()
        }
    }

    /** Finished: open the file. Still downloading: watch the part already downloaded while the rest comes in. */
    private fun openOrWatch(t: DownloadTask) {
        if (t.status == Status.DONE) {
            openFile(t)
            return
        }
        val dir = java.io.File(filesDir, "downloads/${t.id}")
        when {
            // plays from the pieces already downloaded; while downloading, the rest comes in as you watch.
            // Paused, failed or offline: plays what is already on the phone (no internet needed)
            PartialPlayback.ready(t.id) || PartialFiles.restore(t.id, dir) -> {
                controller?.pause()                     // one thing plays at a time
                PlayerActivity.openPartial(this, t.id, t.title.ifBlank { "Video" })
            }
            t.progress >= 99 -> message("Almost done… it opens from Downloaded in a moment")
            t.status == Status.RUNNING -> message("Getting the download ready… tap again in a moment")
            else -> message("Nothing downloaded yet. Start the download to watch it")
        }
    }

    /** A video from a list: open its page (playlists go straight to the download sheet). */
    private fun openItem(item: VideoItem) {
        if (item.isPlaylist) home.openPlaylist(item)
        else video.open(item.url, item.title, item.uploader, thumb = item.thumb)
    }

    // =====================================================================
    // Player (BgPlayService): the video page plays through it, so leaving the app
    // can keep the sound going (Settings > Background play)
    // =====================================================================

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    private var controller: androidx.media3.session.MediaController? = null

    private fun connectPlayer() {
        if (controllerFuture != null) return
        val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, BgPlayService::class.java))
        val future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            if (controllerFuture !== future) { c.release(); return@addListener }
            controller = c
            video.attach(c)
            syncVideoSurface()
            c.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    val onScreen = video.isOpen && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    // next track (button, notification or end of video): the page shows that video
                    if (reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && onScreen) {
                        video.follow(c)
                    } else if (onScreen && mediaItem?.mediaId == video.url &&
                        mediaItem?.mediaMetadata?.extras?.getBoolean(VideoScreen.EXTRA_VIDEO) != true
                    ) {
                        // the player renewed expired addresses as sound only: put the picture back
                        video.refreshStreams()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (!video.isOpen) return
                    // expired addresses (paused for hours): fetch fresh ones and continue where it was
                    if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                        c.currentMediaItem?.mediaId == video.url && video.refreshStreams()
                    ) return
                    video.showError("Couldn't play this video here. You can still download it.")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (b.miniPlayer.root.isVisible) updateMiniPlayer()
                }
            })
            val reopen = pendingVideo
            pendingVideo = null
            if (reopen != null) video.open(reopen, resume = true)
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) backInApp()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disconnectPlayer() {
        video.attach(null)
        b.miniPlayer.miniVideo.player = null
        controllerFuture?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** Leaving the app: keep only the sound (Background play on) or pause. */
    private fun leaveApp() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (!AppPrefs.backgroundPlay(this)) {
            c.pause()
            return
        }
        // no picture while away: saves battery and data, the sound continues
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, true).build()
    }

    /** Back in the app: picture on again, and the page shows what is playing (maybe a later track). */
    private fun backInApp() {
        val c = controller ?: return
        if (c.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
        }
        val id = c.currentMediaItem?.mediaId
        // the open video is playing as sound only (its addresses were renewed in the background): picture back
        if (video.isOpen && id == video.url &&
            c.currentMediaItem?.mediaMetadata?.extras?.getBoolean(VideoScreen.EXTRA_VIDEO) != true
        ) video.refreshStreams()
        if (c.mediaItemCount > 0 && c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
            id != null && FastExtractor.supports(id) && id != video.url
        ) {
            if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
            video.follow(c)
        }
    }

    private fun showTab(index: Int) {
        if (index != 1 && selecting) exitSelection()
        tab = index
        hm.root.isVisible = index == 0
        pl.root.isVisible = index == 1
        st.root.isVisible = index == 2
        updateChrome()
    }

    /** Video page over Home, bottom bar hidden in fullscreen, Back handling. */
    private fun updateChrome() {
        vp.root.isVisible = video.isOpen && !video.minimized && tab == 0
        val full = video.fullscreen
        b.navCard.isVisible = !full                    // the floating glass bar (hidden in full screen)
        // the banner hides in full screen and comes back after (only once an ad has loaded)
        b.adBanner.isVisible = !full && Ads.bannerLoaded
        val mini = video.isOpen && !full && (video.minimized || tab != 0)
        val wasMini = b.miniPlayer.root.isVisible
        b.miniPlayer.root.isVisible = mini
        if (mini) {
            updateMiniPlayer()
            if (!wasMini) placeMiniPlayer()
        }
        syncVideoSurface()
        updateBack()
    }

    // ----- floating mini player -----

    private var miniPlaced = false
    private var miniLeftSide = false
    private var miniDragging = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMiniPlayer() {
        val m = b.miniPlayer
        m.root.setOnClickListener {
            if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
            video.expand()
        }
        m.miniPlay.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else {
                if (c.playbackState == androidx.media3.common.Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }
        m.miniClose.setOnClickListener { video.close() }

        // drag it anywhere; when let go it moves to the nearest side, like YouTube's
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f
        m.root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y; miniDragging = false
                    v.animate().cancel()
                    false                                  // let a plain tap still open the video
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!miniDragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        miniDragging = true
                        // the card stops treating this as a tap
                        android.view.MotionEvent.obtain(e).let { c ->
                            c.action = android.view.MotionEvent.ACTION_CANCEL
                            v.onTouchEvent(c)
                            c.recycle()
                        }
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (miniDragging) {
                        val (minX, maxX, minY, maxY) = miniBounds()
                        v.x = (startX + dx).coerceIn(minX, maxX)
                        v.y = (startY + dy).coerceIn(minY, maxY)
                    }
                    miniDragging
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (miniDragging) {
                        val (minX, maxX, _, _) = miniBounds()
                        miniLeftSide = v.x + v.width / 2f < (minX + maxX + v.width) / 2f
                        v.animate().x(if (miniLeftSide) minX else maxX).setDuration(180).start()
                        miniDragging = false
                        true
                    } else false
                }
                else -> false
            }
        }
        // keep it on screen when the screen turns or the bottom bar hides
        b.root.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, oR, ob ->
            val resized = r - l != oR - ol || bt - t != ob - ot
            if (resized && m.root.isVisible && miniPlaced && !miniDragging) {
                m.root.animate().cancel()
                val (minX, maxX, minY, maxY) = miniBounds()
                m.root.x = if (miniLeftSide) minX else maxX
                m.root.y = m.root.y.coerceIn(minY, maxY)
            }
        }
    }

    /** Where the floating player may go: inside the screen, below the status bar, above the bottom bar. */
    private fun miniBounds(): List<Float> {
        val m = b.miniPlayer.root
        val margin = 12 * resources.displayMetrics.density
        val root = b.root
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val top = (insets?.top ?: 0) + margin
        // never over the ad banner (ad rules) or the bottom bar
        val bottomView = if (b.adBanner.isVisible && b.adBanner.height > 0) b.adBanner else b.navCard
        val navTop = if (bottomView.isVisible && bottomView.height > 0) {
            val loc = IntArray(2); val rootLoc = IntArray(2)
            bottomView.getLocationInWindow(loc); root.getLocationInWindow(rootLoc)
            (loc[1] - rootLoc[1]).toFloat()
        } else root.height - (insets?.bottom ?: 0).toFloat()
        // stay above the Library's selection buttons (Delete / Remove) while selecting
        val bottomLimit = if (tab == 1 && pl.selectionActions.isVisible && pl.selectionActions.height > 0) {
            val loc = IntArray(2); val rootLoc = IntArray(2)
            pl.selectionActions.getLocationInWindow(loc); root.getLocationInWindow(rootLoc)
            minOf(navTop, (loc[1] - rootLoc[1]).toFloat())
        } else navTop
        val maxX = (root.width - m.width - margin).coerceAtLeast(margin)
        val maxY = (bottomLimit - m.height - margin).coerceAtLeast(top)
        return listOf(margin, maxX, top, maxY)
    }

    private fun placeMiniPlayer() {
        val m = b.miniPlayer.root
        m.alpha = 0f                                   // hidden until it is in place (no flash at the corner)
        m.post {
            m.alpha = 1f
            if (m.width == 0) return@post
            val (minX, maxX, minY, maxY) = miniBounds()
            if (!miniPlaced) {                         // first time: bottom right, above the bottom bar
                miniPlaced = true
                miniLeftSide = false
                m.x = maxX
                m.y = maxY
            } else {
                m.x = if (miniLeftSide) minX else maxX
                m.y = m.y.coerceIn(minY, maxY)
            }
        }
    }

    private fun updateMiniPlayer() {
        b.miniPlayer.miniPlay.setImageResource(if (controller?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** The picture follows the player that is on screen: the floating window or the video page. */
    private fun syncVideoSurface() {
        val c = controller ?: return
        val mini = b.miniPlayer.miniVideo
        val page = video.playerView
        val (show, hide) = if (b.miniPlayer.root.isVisible) mini to page else page to mini
        if (show.player === c && hide.player == null) return
        // only one view may draw the picture: detach both, then attach the visible one
        hide.player = null
        show.player = null
        show.player = c
    }

    private fun updateBack() {
        backCallback.isEnabled = true          // Back is decided in handleOnBackPressed (video, search, tabs)
    }

    // =====================================================================
    // Links in / download sheet
    // =====================================================================

    private fun extractUrls(text: String): List<String> =
        Regex("https?://\\S+").findAll(text).map { it.value }.distinct().toList()

    /** Links shared from other apps (Share > RAINAX). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val urls = extractUrls(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        if (urls.isNotEmpty()) {
            showDownloadSheet(urls)
        }
    }

    private fun showDownloadSheet(urls: List<String>, preferAudio: Boolean = false, knownTitle: String? = null) {
        sheet?.dismiss()
        val sb = SheetDownloadBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet = dialog

        val single = urls.size == 1
        val firstUrl = urls[0]
        val cookies = urls.associateWith<String, String?> { null }
        val screenH = resources.displayMetrics.heightPixels

        var current: PreviewState? = null
        var mode = MODE_QUICK
        var selectedSpec: String? = if (preferAudio) "audio:m4a" else null
        var selectedSub: String? = null

        var choiceAction: (FormatChoice) -> Unit = {}
        var navAction: (String) -> Unit = {}
        val adapter = FormatAdapter({ choiceAction(it) }, { navAction(it) })
        sb.formatList.layoutManager = LinearLayoutManager(this)
        sb.formatList.adapter = adapter

        fun group(list: List<FormatChoice>, detailed: Boolean): List<FormatRow> {
            val out = mutableListOf<FormatRow>()
            val audio = list.filter { it.kind == KIND_AUDIO }
            val video = list.filter { it.kind == KIND_VIDEO }
            if (audio.isNotEmpty()) {
                out += FormatRow.Section("Music")
                audio.forEach { out += FormatRow.Choice(it, it.spec == selectedSpec, detailed) }
            }
            if (video.isNotEmpty()) {
                out += FormatRow.Section("Video")
                video.forEach { out += FormatRow.Choice(it, it.spec == selectedSpec, detailed) }
            }
            return out
        }

        fun render() {
            val p = current
            val loading = single && (p == null || p.loading)
            val error = if (single) p?.error else null
            val isPlaylist = p?.playlist?.isNotEmpty() == true
            // While the exact sizes load, the usual choices are already there: pick one and download at once
            val presets = !single || error != null || isPlaylist || (loading && p?.quick.isNullOrEmpty() && !isPlaylistUrl(firstUrl))
            val quick = when {
                // playlist: show the approximate total size of every choice
                isPlaylist -> {
                    val list = p!!.playlist
                    val known = list.filter { it.seconds > 0 }
                    val avg = if (known.isNotEmpty()) known.sumOf { it.seconds } / known.size else 240L
                    val total = list.sumOf { if (it.seconds > 0) it.seconds else avg }
                    PRESETS.map { it.copy(size = "≈ " + formatSize(estimatePlaylistBytes(it.spec, total))) }
                }
                presets -> PRESETS
                else -> p?.quick.orEmpty()
            }
            val all = if (presets) emptyList() else p?.all.orEmpty()
            val subs = if (presets) emptyList() else p?.subtitles.orEmpty()

            // picked while sizes were loading, but this video has no such row: select the closest real one,
            // so what downloads is always what is shown as selected
            val sel = selectedSpec
            if (!presets && sel != null && quick.isNotEmpty() && (quick + all).none { it.spec == sel }) {
                selectedSpec = if (sel.startsWith("audio")) {
                    quick.firstOrNull { it.kind == KIND_AUDIO }?.spec
                } else {
                    val want = sel.split(':').getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE
                    val videos = quick.filter { it.kind == KIND_VIDEO }
                    fun h(c: FormatChoice) = c.spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0
                    (videos.filter { h(it) <= want }.maxByOrNull { h(it) } ?: videos.firstOrNull())?.spec
                }
            }

            sb.sheetHeading.text = when (mode) {
                MODE_ALL -> "More formats"
                MODE_SUBS -> "Subtitles/CC"
                else -> if (single) "Download video as" else "Download ${urls.size} links as"
            }
            sb.backBtn.isVisible = mode != MODE_QUICK
            sb.sheetSub.text = when {
                !single -> "They will download one after another"
                loading -> (knownTitle ?: p?.title)?.let { "$it  •  getting sizes…" } ?: "Fetching info…"
                error != null -> Uri.parse(firstUrl).host.orEmpty()
                isPlaylist -> "${p!!.title}  •  ${p.subtitle}"
                else -> p?.title.orEmpty()
            }
            sb.previewLoading.isVisible = loading

            sb.errorBlock.isVisible = error != null
            if (error != null) {
                sb.errorText.text = error + "\n\nTap Retry, or pick a format below to try anyway."
            }
            sb.playlistBtn.isVisible = false

            val rows: List<FormatRow> = when (mode) {
                MODE_SUBS -> {
                    val list = mutableListOf<FormatRow>()
                    list += FormatRow.Choice(FormatChoice("", "None", kind = KIND_NONE), selectedSub == null, false)
                    subs.forEach {
                        list += FormatRow.Choice(FormatChoice(it.code, it.label, kind = KIND_NONE), selectedSub == it.code, false)
                    }
                    list
                }
                MODE_ALL -> {
                    val list = group(all, true).toMutableList()
                    if (subs.isNotEmpty()) {
                        val label = subs.firstOrNull { it.code == selectedSub }?.label ?: "Off"
                        list += FormatRow.Nav("subs", "Subtitles/CC", label, R.drawable.ic_cc)
                    }
                    list
                }
                else -> {
                    val list = group(quick, false).toMutableList()
                    if (all.isNotEmpty()) list += FormatRow.Nav("all", "More formats", "All", null)
                    list
                }
            }
            adapter.submitList(rows)
            sb.downloadBtn.isEnabled = selectedSpec != null

            val fraction = if (mode == MODE_QUICK) 0.66 else 0.9
            sb.root.minimumHeight = (screenH * fraction).toInt()
        }

        choiceAction = { c ->
            if (mode == MODE_SUBS) {
                selectedSub = c.spec.ifEmpty { null }
                mode = MODE_ALL
            } else {
                selectedSpec = c.spec
            }
            render()
        }
        navAction = { id ->
            mode = if (id == "all") MODE_ALL else MODE_SUBS
            render()
        }
        sb.backBtn.setOnClickListener {
            mode = if (mode == MODE_SUBS) MODE_ALL else MODE_QUICK
            render()
        }
        sb.retryBtn.setOnClickListener { vm.fetchInfo(firstUrl, cookies[firstUrl]) }
        sb.playlistBtn.setOnClickListener {
            val id = Uri.parse(firstUrl).getQueryParameter("list").orEmpty()
            vm.fetchInfo("https://www.youtube.com/playlist?list=$id", cookies[firstUrl], forcePlaylist = true)
        }

        sb.downloadBtn.setOnClickListener {
            val spec = selectedSpec ?: return@setOnClickListener
            val p = current
            val sub = if (spec.startsWith("video")) selectedSub else null
            val items = when {
                !single -> urls.map { EnqueueItem(it, "", cookies[it]) }
                p != null && p.playlist.isNotEmpty() ->
                    p.playlist.map { EnqueueItem(it.url, it.title, cookies[firstUrl], it.thumbUrl) }
                // never save a status text like "Connecting…" as the title (blank = looked up by the service)
                else -> listOf(
                    EnqueueItem(firstUrl, p?.takeIf { !it.loading }?.title ?: knownTitle.orEmpty(), cookies[firstUrl], youtubeThumb(firstUrl))
                )
            }
            vm.enqueue(items, spec, sub)
            dialog.dismiss()
            Ads.onDownloadAdded(this)                  // sometimes a full-screen ad (limited, see Ads)
            if (!askBatteryOptimization()) {
                val what = if (items.size == 1) "Added to downloads" else "Added ${items.size} items to downloads"
                Snackbar.make(b.root, what, Snackbar.LENGTH_LONG)
                    .setAnchorView(snackAnchor())
                    .setAction("View") { b.bottomNav.selectedItemId = R.id.nav_downloads }
                    .show()
            }
        }

        var collectJob: Job? = null
        if (single) {
            vm.fetchInfo(firstUrl, cookies[firstUrl])
            collectJob = lifecycleScope.launch {
                vm.preview.collect { current = it; render() }
            }
        }
        render()

        dialog.setOnDismissListener {
            collectJob?.cancel()
            // A newer sheet may already be open (dismiss runs later): only clean up our own
            if (sheet === dialog) {
                sheet = null
                vm.clearPreview()
            }
        }
        dialog.show()
    }

    // =====================================================================
    // Play tab (downloaded + downloading)
    // =====================================================================

    private fun setupPlay() {
        pl.activeList.layoutManager = LinearLayoutManager(this)
        pl.activeList.adapter = activeAdapter
        pl.doneList.layoutManager = LinearLayoutManager(this)
        pl.doneList.adapter = doneAdapter

        pl.emptySearchBtn.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_home }
        pl.selectActiveBtn.setOnClickListener { selectAll(done = false) }
        pl.selectDoneBtn.setOnClickListener { selectAll(done = true) }
        pl.removeAllBtn.setOnClickListener { removeAllDone() }
        pl.deleteAllBtn.setOnClickListener { deleteAllDone() }
        pl.clearFailedBtn.setOnClickListener {
            TaskRepository.removeFailed()
            message("Removed failed downloads")
        }

        // global actions
        pl.pauseAllBtn.setOnClickListener { DownloadService.send(this, DownloadService.ACTION_PAUSE_ALL) }
        pl.resumeAllBtn.setOnClickListener { DownloadService.send(this, DownloadService.ACTION_RESUME_ALL) }
        pl.cancelAllBtn.setOnClickListener {
            val n = latestTasks.count { it.status != Status.DONE }
            if (n > 0) {
                confirm("Cancel all $n downloads?", "Partial files will be deleted.") {
                    DownloadService.send(this, DownloadService.ACTION_CANCEL_ALL)
                }
            }
        }

        // selection bar
        pl.closeSelBtn.setOnClickListener { exitSelection() }
        pl.selAllBtn.setOnClickListener { toggleSelectAll() }
        pl.actPause.setOnClickListener { pauseSelected() }
        pl.actResume.setOnClickListener { resumeSelected() }
        pl.actCancel.setOnClickListener { cancelSelected() }
        pl.actRemove.setOnClickListener { removeSelected() }
        pl.actDelete.setOnClickListener { deleteSelected() }
    }

    private fun renderTasks(all: List<DownloadTask>) {
        latestTasks = all
        val active = all.filter { it.status != Status.DONE }
        val done = all.filter { it.status == Status.DONE }

        // forget selected items that no longer exist
        val before = selected.size
        selected.retainAll(all.map { it.id }.toSet())
        val selectionChanged = before != selected.size || (selecting && selected.isEmpty())
        if (selecting && selected.isEmpty()) selecting = false

        pl.emptyState.isVisible = all.isEmpty()
        pl.playScroll.isVisible = all.isNotEmpty()

        pl.activeSection.isVisible = active.isNotEmpty()
        pl.clearFailedBtn.isVisible = active.any { it.status == Status.FAILED }
        pl.activeTitle.text = "Downloading (${active.size})"
        // Downloading now always on top, then the queue in the order it will download
        // (oldest first, like the service), then paused and failed ones
        val order = HashMap<String, Int>(all.size).apply { all.forEachIndexed { i, t -> put(t.id, all.size - i) } }
        val newActive = active.sortedWith(
            compareBy<DownloadTask> {
                when (it.status) {
                    Status.RUNNING -> 0
                    Status.WAITING -> 1
                    Status.QUEUED -> 2
                    Status.PAUSED -> 3
                    else -> 4
                }
            }.thenBy { order[it.id] ?: 0 }
        )
        val topChanged = newActive.firstOrNull()?.id != activeAdapter.currentList.firstOrNull()?.id
        // show the new top download, unless you scrolled far down to look at something else
        val nearTop = pl.playScroll.scrollY < resources.displayMetrics.heightPixels / 2
        activeAdapter.submitList(newActive) { if (topChanged && nearTop) pl.playScroll.smoothScrollTo(0, 0) }

        pl.doneSection.isVisible = done.isNotEmpty()
        pl.doneTitle.text = "Downloaded (${done.size})"
        doneAdapter.submitList(done.sortedByDescending { it.createdAt })      // newest download first

        val badge = b.bottomNav.getOrCreateBadge(R.id.nav_downloads)
        badge.isVisible = active.isNotEmpty()
        badge.number = active.size

        if (selectionChanged) refreshSelectionUi() else updateSelectionBar()
    }

    // ----- multi-select -----

    private fun startSelect(t: DownloadTask) {
        selecting = true
        selected.clear()
        selected += t.id
        refreshSelectionUi()
    }

    private fun toggleSelect(t: DownloadTask) {
        if (!selected.add(t.id)) selected.remove(t.id)
        if (selected.isEmpty()) selecting = false
        refreshSelectionUi()
    }

    private fun toggleSelectAll() {
        val all = latestTasks.map { it.id }
        if (all.isEmpty() || selected.size == all.size) {
            exitSelection()
            return
        }
        selected.clear()
        selected += all
        selecting = true
        refreshSelectionUi()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        refreshSelectionUi()
    }

    private fun refreshSelectionUi() {
        activeAdapter.setSelection(selecting, selected)
        doneAdapter.setSelection(selecting, selected)
        updateSelectionBar()
        updateBack()
    }

    private fun updateSelectionBar() {
        val wasSelecting = pl.selectionActions.isVisible
        pl.selectionBar.isVisible = selecting
        pl.selectionActions.isVisible = selecting
        if (wasSelecting != selecting && b.miniPlayer.root.isVisible) pl.selectionActions.post { placeMiniPlayer() }    // move above / back
        pl.selectionCount.text = "${selected.size} selected"
        if (!selecting) return

        // only show the actions that apply to what is selected
        val sel = selectedTasks()
        pl.actPause.isVisible = sel.any {
            it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
        }
        pl.actResume.isVisible = sel.any { it.status == Status.PAUSED || it.status == Status.FAILED }
        pl.actCancel.isVisible = sel.any { it.status != Status.DONE }
        pl.actRemove.isVisible = sel.any { it.status == Status.DONE }
        pl.actDelete.isVisible = sel.any { it.status == Status.DONE }
    }

    private fun selectedTasks() = latestTasks.filter { it.id in selected }

    private fun pauseSelected() {
        val ids = selectedTasks().filter {
            it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
        }.map { it.id }
        if (ids.isEmpty()) message("Nothing to pause")
        else DownloadService.send(this, DownloadService.ACTION_PAUSE_SEL, ids = ids.toTypedArray())
        exitSelection()
    }

    private fun resumeSelected() {
        val ids = selectedTasks().filter {
            it.status == Status.PAUSED || it.status == Status.FAILED
        }.map { it.id }
        if (ids.isEmpty()) message("Nothing to resume")
        else DownloadService.send(this, DownloadService.ACTION_RESUME_SEL, ids = ids.toTypedArray())
        exitSelection()
    }

    /** Cancel: only for unfinished downloads (stops them and deletes partial files). */
    private fun cancelSelected() {
        val ids = selectedTasks().filter { it.status != Status.DONE }.map { it.id }
        if (ids.isEmpty()) return
        confirm("Cancel ${ids.size} download(s)?", "Partial files will be deleted.") {
            DownloadService.send(this, DownloadService.ACTION_CANCEL_SEL, ids = ids.toTypedArray())
            exitSelection()
        }
    }

    /** Remove: finished items leave the list, the files stay in Downloads. */
    private fun removeSelected() {
        val ids = selectedTasks().filter { it.status == Status.DONE }.map { it.id }
        if (ids.isEmpty()) return
        TaskRepository.removeMany(ids)
        exitSelection()
        message("Removed ${ids.size} from the list")
    }

    /** Delete: finished items are removed from the list and their files deleted. */
    private fun deleteSelected() {
        val done = selectedTasks().filter { it.status == Status.DONE }
        if (done.isEmpty()) return
        confirm("Delete ${done.size} file(s)?", "They will be removed from your phone.") {
            deleteFilesOf(done)
            exitSelection()
        }
    }

    private fun removeAllDone() {
        val n = latestTasks.count { it.status == Status.DONE }
        if (n == 0) return
        confirm("Remove $n items from the list?", "The files stay in your Downloads folder.") {
            TaskRepository.removeDone()
        }
    }

    private fun deleteAllDone() {
        val done = latestTasks.filter { it.status == Status.DONE }
        if (done.isEmpty()) return
        confirm("Delete all ${done.size} files?", "They will be removed from your phone.") {
            deleteFilesOf(done)
        }
    }

    private fun deleteFilesOf(tasks: List<DownloadTask>) {
        var deleted = 0
        for (t in tasks) {
            val uri = t.fileUri ?: continue
            if (FileStore.delete(this, uri)) deleted++
        }
        TaskRepository.removeMany(tasks.map { it.id })
        message("Deleted $deleted of ${tasks.size} files")
    }

    /** Selects every unfinished (done = false) or finished (done = true) item. */
    private fun selectAll(done: Boolean) {
        val ids = latestTasks.filter { (it.status == Status.DONE) == done }.map { it.id }
        if (ids.isEmpty()) return
        selected.clear()
        selected += ids
        selecting = true
        refreshSelectionUi()
    }

    private fun confirm(title: String, text: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun onTaskAction(t: DownloadTask) {
        when (t.status) {
            Status.RUNNING, Status.QUEUED, Status.WAITING ->
                DownloadService.send(this, DownloadService.ACTION_PAUSE, t.id)
            Status.PAUSED, Status.FAILED ->
                DownloadService.send(this, DownloadService.ACTION_RESUME, t.id)
            Status.DONE -> openFile(t)
        }
    }

    private fun onTaskClose(t: DownloadTask, anchor: View) {
        if (t.status != Status.DONE) {
            // with some progress, ask first: cancelling deletes what was downloaded
            if (t.progress > 0) {
                confirm("Cancel download?", "\"${t.title.ifBlank { "This download" }}\" stops and its downloaded part is deleted.") {
                    DownloadService.send(this, DownloadService.ACTION_CANCEL, t.id)
                }
            } else DownloadService.send(this, DownloadService.ACTION_CANCEL, t.id)
            return
        }
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 6, 1, "Open with another app")
        popup.menu.add(0, 2, 1, "Share")
        popup.menu.add(0, 3, 2, "Copy link")
        popup.menu.add(0, 4, 3, "Remove from list")
        popup.menu.add(0, 5, 4, "Delete file")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openFile(t)
                2 -> shareFile(t)
                6 -> openWithOtherApp(t)
                3 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                    message("Link copied")
                }
                4 -> TaskRepository.remove(t.id)
                5 -> confirm("Delete file?", "\"${t.title}\" is deleted from your phone.") {
                    t.fileUri?.let { FileStore.delete(this, it) }
                    TaskRepository.remove(t.id)
                    message("File deleted")
                }
            }
            true
        }
        popup.show()
    }

    /** YouTube thumbnail address from the video id (no lookup needed). */
    private fun youtubeThumb(url: String): String? =
        if (!FastExtractor.supports(url)) null else Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})").find(url)?.groupValues?.get(1)
            ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

    private fun isPlayable(t: DownloadTask): Boolean {
        val m = t.mime.orEmpty()
        if (m.startsWith("video/") || m.startsWith("audio/")) return true
        val name = (t.title + " " + t.fileUri.orEmpty()).lowercase()
        return t.isAudio || listOf(".mp4", ".mkv", ".webm", ".mov", ".m4a", ".mp3", ".opus", ".ogg").any { name.contains(it) }
    }

    /** Finished videos and music always open in the RAINAX player (with the other downloads as a playlist). */
    private fun openFile(t: DownloadTask) {
        val uri = t.fileUri ?: return
        if (isPlayable(t)) {
            controller?.pause()                         // one thing plays at a time
            val list = TaskRepository.tasks.value.filter { it.status == Status.DONE && it.fileUri != null && isPlayable(it) }
            val index = list.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
            try {
                if (list.isEmpty()) PlayerActivity.open(this, listOf(uri), listOf(t.title), 0)
                else PlayerActivity.open(this, list.map { it.fileUri!! }, list.map { it.title }, index)
                return
            } catch (e: Exception) { /* fall back to another app */ }
        }
        openWithOtherApp(t)
    }

    private fun openWithOtherApp(t: DownloadTask) {
        val uri = t.fileUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), t.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(t: DownloadTask) {
        val uri = t.fileUri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(t.mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // Settings tab
    // =====================================================================

    private fun setupSettings() {
        st.wifiSwitch.isChecked = AppPrefs.wifiOnly(this)
        st.wifiSwitch.setOnCheckedChangeListener { _, on ->
            AppPrefs.setWifiOnly(this, on)
            nudgeService()
        }
        st.themeGroup.check(
            when (AppPrefs.themeMode(this)) {
                1 -> R.id.themeLight
                2 -> R.id.themeDark
                3 -> R.id.themeAmoled
                else -> R.id.themeSystem
            }
        )
        st.themeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.themeLight -> 1
                R.id.themeDark -> 2
                R.id.themeAmoled -> 3
                else -> 0
            }
            val old = AppPrefs.themeMode(this)
            if (mode != old) {
                AppPrefs.setThemeMode(this, mode)
                RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
                // Dark <-> AMOLED keeps night mode, so rebuild the screen ourselves
                if (old == 3 || mode == 3) window.decorView.post { if (!isDestroyed) recreate() }
            }
        }

        st.autoRetrySwitch.isChecked = AppPrefs.autoRetry(this)
        st.autoRetrySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoRetry(this, on) }
        st.bgPlaySwitch.isChecked = AppPrefs.backgroundPlay(this)
        st.bgPlaySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setBackgroundPlay(this, on) }
        st.checkAppUpdateBtn.setOnClickListener { AppUpdater.check(this, manual = true) }
        st.adPrivacyBtn.setOnClickListener { Ads.showPrivacyOptions(this) }

        st.autoClearSwitch.isChecked = AppPrefs.autoClear(this)
        st.autoClearSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoClear(this, on) }

        st.parallelGroup.check(
            when (AppPrefs.maxParallel(this)) {
                1 -> R.id.par1
                3 -> R.id.par3
                4 -> R.id.par4
                else -> R.id.par2
            }
        )
        st.parallelGroup.setOnCheckedStateChangeListener { _, ids ->
            val n = when (ids.firstOrNull()) {
                R.id.par1 -> 1
                R.id.par3 -> 3
                R.id.par4 -> 4
                else -> 2
            }
            AppPrefs.setMaxParallel(this, n)
            nudgeService()
        }

        renderFolder()
        st.chooseFolderBtn.setOnClickListener {
            try { pickFolder.launch(null) } catch (e: Exception) { message("This phone has no folder picker") }
        }
        st.resetFolderBtn.setOnClickListener {
            AppPrefs.setSaveTree(this, "")
            renderFolder()
            message("Using Downloads/${FileStore.DEFAULT_FOLDER}")
        }
        st.openFolderBtn.setOnClickListener {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e: Exception) {
                message("Can't open the Downloads folder")
            }
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        st.versionText.text = "RAINAX YT DOWNLOADER  v$version"
    }

    private fun renderFolder() {
        st.folderPath.text = FileStore.describe(this)
    }

    private fun nudgeService() {
        val needs = TaskRepository.tasks.value.any {
            it.status == Status.QUEUED || it.status == Status.WAITING || it.status == Status.RUNNING
        }
        if (needs) DownloadService.send(this, DownloadService.ACTION_PUMP)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Messages sit above the ad banner (never over it) or above the bottom bar. */
    private fun snackAnchor(): View = if (b.adBanner.isVisible) b.adBanner else b.navCard

    private fun message(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(snackAnchor()).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** One-time prompt so the system doesn't kill background downloads. Returns true if shown. */
    private fun askBatteryOptimization(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("battery_asked", false)) {
            return false
        }
        prefs.edit().putBoolean("battery_asked", true).apply()
        // a dialog (not a snackbar), so no other message can hide it
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Added to downloads")
            .setMessage("Allow RAINAX to run in the background so downloads keep going when the screen is off.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) { /* not available on this device */ }
            }
            .setNegativeButton("Not now", null)
            .show()
        return true
    }

    companion object {
        private const val MODE_QUICK = 0
        private const val MODE_ALL = 1
        private const val MODE_SUBS = 2
    }
}
