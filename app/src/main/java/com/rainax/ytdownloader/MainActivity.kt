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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }


    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val hm get() = b.homePage
    private val vp get() = b.videoPage
    private val pl get() = b.playPage
    private val st get() = b.settingsPage
    private val so get() = b.socialPage
    private val sb get() = b.subsPage

    private var tab = 0            // pages: 0 Home, 1 You (library), 2 Settings, 3 Add link (+), 4 Subscriptions
    private lateinit var home: HomeScreen
    private lateinit var video: VideoScreen
    private lateinit var library: LibraryScreen
    private lateinit var music: MusicPlayer
    private lateinit var subs: SubsScreen
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
            subs.onAccountChanged()
            if (tab == 4) subs.onShown()
            refreshYou()
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
                exploreOpen -> closeExplore()
                music.back() -> {}
                video.fullscreen -> video.exitFullscreen()
                selecting && tab == 1 -> exitSelection()
                tab == 1 && library.back() -> {}
                tab == 2 -> b.bottomNav.selectedItemId = R.id.nav_downloads      // Settings -> back to You
                tab != 0 -> b.bottomNav.selectedItemId = R.id.nav_home
                video.isOpen && !video.minimized -> video.minimize()
                home.clearPicks() -> {}
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
        Picks.clearListeners()                      // (screens below register again)
        if (AppPrefs.autoClear(this)) {
            TaskRepository.removeDoneOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
        }
        if (savedInstanceState == null) {
            // first run: two short questions (storage, then app updates); later runs: notifications only
            if (!AppPrefs.firstRunAsked(this)) b.root.post { askStorage() } else requestNotificationPermission()
        }

        home = HomeScreen(this, hm, vm, { openItem(it) }, { showDownloadSheet(listOf(it.url), knownTitle = it.title.ifBlank { null }) }) { onAccountClick() }
        home.setup()
        fitWideScreen()
        home.downloadMany = { list -> showDownloadSheet(list.map { it.url }) }
        AppUpdater.onQueued = { b.bottomNav.selectedItemId = R.id.nav_downloads }
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
        video.downloadMany = { list -> showDownloadSheet(list.map { it.url }) }
        // Music tab player (YouTube Music style): songs play in the same background player as videos
        music = MusicPlayer(
            this, b.musicPage, b.musicMini, { controller },
            download = { u, t, audio -> showDownloadSheet(listOf(u), preferAudio = audio, knownTitle = t) },
            beforePlay = { if (video.isOpen) video.close() },
            signIn = { onAccountClick() }
        ) { updateChrome() }
        music.setup()
        music.onNowPlaying = { home.musicNowPlaying(it) }
        music.onTone = { home.musicTone(it) }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { MusicHistory.recent(applicationContext) }   // read early, off the main thread
        home.musicPlay = { songs, index, from, radio -> music.play(songs, index, from, radio) }
        home.musicSongMenu = { music.songMenu(it) }
        home.musicPlayPlaylist = { music.playPlaylist(it) }
        setupMiniPlayer()
        setupPlay()
        library = LibraryScreen(this, pl, { onLibraryMode() }, { shareFile(it) }) { t ->
            confirm("Delete this file?", "\"${t.title}\" will be removed from your phone.") { deleteFilesOf(listOf(t)) }
        }
        library.setup()
        setupSettings()
        setupSocial()
        setupYou()
        setupExplore()
        subs = SubsScreen(
            this, sb,
            openVideo = { goHome(); openItem(it) },
            download = { showDownloadSheet(listOf(it.url), knownTitle = it.title.ifBlank { null }) },
            openChannel = { goHome(); video.minimize(); home.openChannel(it.url, it.title, it.thumb) },
            openAllChannels = { openHomeList("All subscriptions", "acc:FEchannels") },
            signIn = { onAccountClick() }
        )
        subs.setup()
        home.onMenu = { openExplore() }
        home.onVoice = { startVoiceSearch() }
        home.onAllSubs = { b.bottomNav.selectedItemId = R.id.nav_subs }

        b.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> { showTab(0); true }
                R.id.nav_shorts -> {
                    // Shorts open full screen (the tab you were on stays selected), like YouTube
                    video.minimize()
                    music.collapse()
                    ShortsActivity.open(this, 0)
                    false
                }
                R.id.nav_social -> { showTab(3); true }
                R.id.nav_downloads -> { showTab(1); true }
                R.id.nav_subs -> { showTab(4); true }
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
            val saved = savedInstanceState.getInt("tab", 0)
            b.bottomNav.selectedItemId = when (saved) {
                1, 2 -> R.id.nav_downloads
                3 -> R.id.nav_social
                4 -> R.id.nav_subs
                else -> R.id.nav_home
            }
            if (saved == 2) showTab(2)                     // Settings (opened from You)
        }

        if (savedInstanceState == null) refreshAccount()

        // New RAINAX version? (quiet check, a few seconds after start)
        if (savedInstanceState == null) b.root.postDelayed({ if (!isFinishing && !isDestroyed) AppUpdater.checkOnStart(this) }, 4000)

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
        fitWideScreen()
        // turning the phone sideways on the video page opens the video full screen
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && tab == 0) {
            video.enterFullscreen()
        }
    }

    /** Tablets / wide screens: the bottom bar keeps a phone-like width, the Home feed uses columns. */
    private fun fitWideScreen() {
        // the bottom bar is full width (flat, like YouTube); on wide screens its buttons stay close together
        val wide = resources.configuration.screenWidthDp >= 600
        val lp = b.bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = if (wide) (560 * resources.displayMetrics.density).toInt() else android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        b.bottomNav.layoutParams = lp
        if (::home.isInitialized) home.applyColumns()
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
        if (tab == 0 && ::home.isInitialized) home.onShown()
        b.root.postDelayed({ FastExtractor.warmUp() }, 3000)   // sizes show faster on the first download
    }


    override fun onDestroy() {
        if (!blocked) {
            disconnectPlayer()            // the player service keeps playing on its own
            music.release()
            sheet?.dismiss()
            clipDialog?.dismiss()
            permDialog?.dismiss()
            AppUpdater.onQueued = null
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
            subs.onAccountChanged()
            if (tab == 4) subs.onShown()
            refreshYou()
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
            if (ok != null) {
                home.updateAccountIcon()
                refreshYou()
            }
        }
    }

    /** Finished: open the file. Still downloading: watch the part already downloaded while the rest comes in. */
    private fun openOrWatch(t: DownloadTask) {
        if (t.format.startsWith(AppUpdater.TASK_PREFIX)) {
            if (t.status == Status.DONE) installUpdate(t) else onTaskAction(t)
            return
        }
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
        when {
            item.isPlaylist -> home.openPlaylist(item)
            item.isShort -> {
                video.minimize()                        // a video playing keeps going in the mini player (it pauses for the Short)
                ShortsActivity.open(this, item)
            }
            else -> video.open(item.url, item.title, item.uploader, thumb = item.thumb)
        }
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
            music.attach(c)
            video.onPlayerReady()
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
                    video.showError(if (!Net.online(this@MainActivity)) NO_INTERNET else "Couldn't play this video here. You can still download it.")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (b.miniPlayer.root.isVisible) updateMiniPlayer()
                }
            })
            val reopen = pendingVideo
            pendingVideo = null
            enableVideo(c)                               // (the screen was rebuilt while away: picture back too)
            if (reopen != null) video.open(reopen, resume = true)
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) backInApp()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disconnectPlayer() {
        video.attach(null)
        music.attach(null)
        music.videoView.player = null
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
    /** Picture on again (it was turned off to save battery while the app was away). */
    private fun enableVideo(c: androidx.media3.session.MediaController) {
        if (c.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
        }
    }

    private fun backInApp() {
        val c = controller ?: return
        enableVideo(c)
        val id = c.currentMediaItem?.mediaId
        // the open video is playing as sound only (its addresses were renewed in the background): picture back
        if (video.isOpen && id == video.url &&
            c.currentMediaItem?.mediaMetadata?.extras?.getBoolean(VideoScreen.EXTRA_VIDEO) != true
        ) video.refreshStreams()
        // songs from the Music player stay in the Music player (mini bar), they don't open the video page
        if (MusicPlayer.isMusic(c.currentMediaItem)) {
            music.sync()
            return
        }
        if (c.mediaItemCount > 0 && c.playbackState != androidx.media3.common.Player.STATE_IDLE &&
            id != null && FastExtractor.supports(id) && id != video.url
        ) {
            if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
            video.follow(c)
        }
        music.sync()
    }

    private fun showTab(index: Int) {
        if (index != 1 && selecting) exitSelection()
        tab = index
        hm.root.isVisible = index == 0
        pl.root.isVisible = index == 1
        st.root.isVisible = index == 2
        so.root.isVisible = index == 3
        sb.root.isVisible = index == 4
        updateChrome()
        if (index == 0) home.onShown()
        if (index == 1) refreshYou()
        if (index == 4) subs.onShown()
    }

    /** Video page over Home, bottom bar hidden in fullscreen, Back handling. */
    private fun updateChrome() {
        if (!::music.isInitialized) return
        // a video opened while the big Music player was open: show the video
        if (video.isOpen && !video.minimized && music.expanded) music.collapse()
        val wasShown = vp.root.isVisible
        vp.root.isVisible = video.isOpen && !video.minimized && tab == 0
        if (wasShown && !vp.root.isVisible && tab == 0) home.onShown()      // back on Home: fresh watched bars
        val full = video.fullscreen
        b.navCard.isVisible = !full                    // the bottom bar (hidden in full screen)
        // mini music bar: songs loaded, big player closed, no video open
        b.musicMini.root.isVisible = music.active && !music.expanded && !video.isOpen && !full
        // the banner hides in full screen and comes back after (only once an ad has loaded)
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
        // never over the bottom bar
        val bottomView = b.navCard
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
        val song = music.videoView
        val show = when {
            music.wantsPicture -> song                 // a song's video in the big Music player
            b.miniPlayer.root.isVisible -> mini
            else -> page
        }
        val all = listOf(mini, page, song)
        if (show.player === c && all.all { it === show || it.player == null }) return
        // only one view may draw the picture: detach all, then attach the visible one
        all.forEach { it.player = null }
        show.player = c
    }

    private fun updateBack() {
        backCallback.isEnabled = true          // Back is decided in handleOnBackPressed (video, search, tabs)
    }

    // =====================================================================
    // Links in / download sheet
    // =====================================================================

    // =====================================================================
    // You (library header), side menu, voice search
    // =====================================================================

    /** Home tab with nothing on top (for lists opened from other places). */
    private fun goHome() {
        if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
        music.collapse()
    }

    /** A list on Home with its own title bar (topics, liked, history...). */
    private fun openHomeList(title: String, id: String) {
        goHome()
        video.minimize()
        home.openList(title, id)
    }

    private fun setupYou() {
        pl.youSettings.setOnClickListener { showTab(2) }
        pl.youHeader.setOnClickListener { onAccountClick() }
        // shortcuts after Downloads / Music / Videos / Playlists, like YouTube's Library
        listOf(
            "History" to { openHomeList("History", if (YtAccount.isSignedIn(this)) "acc:FEhistory" else "local:history") },
            "Liked videos" to { openHomeList("Liked videos", "acc:VLLL") },
            "Watch later" to { openHomeList("Watch later", "acc:VLWL") }
        ).forEach { (title, action) ->
            val chip = layoutInflater.inflate(R.layout.item_chip, pl.libChips, false) as com.google.android.material.chip.Chip
            chip.id = View.generateViewId()
            chip.text = title
            chip.isCheckable = false
            chip.setOnClickListener { action() }
            pl.libChips.addView(chip)
        }
        refreshYou()
    }

    /** You header: your YouTube picture and name, or Sign in. */
    private fun refreshYou() {
        val signedIn = YtAccount.isSignedIn(this)
        val p = if (signedIn) YtAccount.profile(this) else null
        if (p != null && !p.avatar.isNullOrBlank()) {
            pl.youName.text = p.name.ifBlank { "You" }
            pl.youHandle.text = p.handle?.takeIf { it.isNotBlank() } ?: "Signed in to YouTube"
            pl.youAvatar.imageTintList = null
            pl.youAvatar.setPadding(0, 0, 0, 0)
            Img.load(pl.youAvatar, p.avatar, circle = true, widthPx = 160)
        } else {
            pl.youName.text = p?.name?.takeIf { it.isNotBlank() } ?: "You"
            pl.youHandle.text = if (signedIn) (p?.handle?.takeIf { it.isNotBlank() } ?: "Signed in to YouTube")
            else "Sign in to YouTube for subscriptions, likes and history"
            Img.load(pl.youAvatar, null)
            pl.youAvatar.setImageResource(R.drawable.ic_person)
            val pad = (10 * resources.displayMetrics.density).toInt()
            pl.youAvatar.setPadding(pad, pad, pad, pad)
            pl.youAvatar.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rx_text2))
        }
    }

    private var exploreOpen = false

    private fun setupExplore() {
        val e = b.explore
        e.exploreScrim.setOnClickListener { closeExplore() }
        fun add(icon: Int, text: String, action: () -> Unit) {
            val row = com.rainax.ytdownloader.databinding.ItemOptionBinding.inflate(layoutInflater, e.exploreList, false)
            row.optText.text = text
            row.optValue.isVisible = false
            row.optIcon.setImageResource(icon)
            row.optIcon.isVisible = true
            row.root.setOnClickListener {
                closeExplore()
                action()
            }
            e.exploreList.addView(row.root)
        }
        fun divider() {
            val v = View(this)
            v.setBackgroundColor(ContextCompat.getColor(this, R.color.rx_divider))
            val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1))
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            lp.bottomMargin = lp.topMargin
            e.exploreList.addView(v, lp)
        }
        add(R.drawable.ic_music_note, "Music") { goHome(); video.minimize(); home.showMusic() }
        add(R.drawable.ic_trending, "Trending") { openHomeList("Trending", "q:trending") }
        add(R.drawable.ic_gaming, "Gaming") { openHomeList("Gaming", "q:gaming") }
        add(R.drawable.ic_news, "News") { openHomeList("News", "q:news today") }
        add(R.drawable.ic_sports, "Sports") { openHomeList("Sports", "q:sports highlights") }
        add(R.drawable.ic_movie, "Movies") { openHomeList("Movies", "q:full movie") }
        add(R.drawable.ic_podcast, "Podcasts") { openHomeList("Podcasts", "q:podcast") }
        add(R.drawable.ic_live, "Live") { openHomeList("Live", "q:live now") }
        divider()
        add(R.drawable.ic_shorts, "Shorts") { video.minimize(); ShortsActivity.open(this, 0) }
        add(R.drawable.ic_nav_subs, "Subscriptions") { b.bottomNav.selectedItemId = R.id.nav_subs }
        add(R.drawable.ic_history, "History") { openHomeList("History", if (YtAccount.isSignedIn(this)) "acc:FEhistory" else "local:history") }
        add(R.drawable.ic_download, "Downloads") { b.bottomNav.selectedItemId = R.id.nav_downloads }
        add(R.drawable.ic_link, "Download from a link") { b.bottomNav.selectedItemId = R.id.nav_social }
        divider()
        add(R.drawable.ic_gear, "Settings") {
            if (tab != 1) b.bottomNav.selectedItemId = R.id.nav_downloads
            showTab(2)
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        e.exploreVersion.text = "RAINAX Tube" + (version?.let { " v$it" } ?: "")
    }

    private fun openExplore() {
        val e = b.explore
        if (exploreOpen) return
        exploreOpen = true
        // start off screen (300dp wide panel), then slide in
        e.explorePanel.translationX = -(e.explorePanel.width.takeIf { it > 0 } ?: (300 * resources.displayMetrics.density).toInt()).toFloat()
        e.root.isVisible = true
        e.explorePanel.animate().translationX(0f).setDuration(220).start()
        e.exploreScrim.alpha = 0f
        e.exploreScrim.animate().alpha(1f).setDuration(220).start()
    }

    private fun closeExplore() {
        val e = b.explore
        if (!exploreOpen) return
        exploreOpen = false
        e.exploreScrim.animate().alpha(0f).setDuration(180).start()
        e.explorePanel.animate().translationX(-e.explorePanel.width.toFloat()).setDuration(180)
            .withEndAction { if (!exploreOpen) e.root.isVisible = false }.start()
    }

    private val voiceSearch =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) home.search(text)
        }

    /** Mic in the search box: speak, then the words are searched. */
    private fun startVoiceSearch() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search YouTube")
        try {
            voiceSearch.launch(i)
        } catch (e: Exception) {
            message("Voice search isn't available on this phone")
        }
    }

    // =====================================================================
    // Social tab and copied links
    // =====================================================================

    private fun setupSocial() {
        so.socialPaste.setOnClickListener {
            val text = clipText()
            val url = text?.let { extractUrls(it).firstOrNull() }
            if (url == null) message("Copy a video link first, then tap Paste") else so.socialInput.setText(url)
        }
        so.socialClear.setOnClickListener { so.socialInput.setText("") }
        so.socialInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { so.socialClear.isVisible = !s.isNullOrEmpty() }
        })
        val go = {
            val urls = extractUrls(so.socialInput.text.toString())
            if (urls.isEmpty()) message("Paste a video link first")
            else {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(so.socialInput.windowToken, 0)
                AppPrefs.setClipSeen(this, urls.first())
                showDownloadSheet(urls)
            }
        }
        so.socialDownload.setOnClickListener { go() }
        so.socialInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                event?.action == android.view.KeyEvent.ACTION_UP
            ) go()
            true
        }
    }

    private fun clipText(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }.getOrNull()

    /** Video sites whose copied links RAINAX offers to download. */
    private fun isVideoLink(url: String): Boolean {
        val u = url.lowercase()
        return FastExtractor.supports(u) || isPlaylistUrl(u) || listOf(
            "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "soundcloud.com", "bandcamp.com"
        ).any { u.contains(it) }
    }

    private var lastClipStamp = -1L
    private var clipDialog: AlertDialog? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets apps read what was copied only while they are on screen with focus
        if (hasFocus && !blocked) b.root.postDelayed({ checkClipboard() }, 400)
    }

    /** A video link was copied in another app: offer to download it (once per link). */
    private fun checkClipboard() {
        if (isFinishing || isDestroyed || !AppPrefs.clipDetect(this) || clipDialog?.isShowing == true || permDialog?.isShowing == true ||
            sheet?.isShowing == true || video.fullscreen
        ) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val desc = cm.primaryClipDescription ?: return
        // reading the text shows "pasted" on new Android versions: only when something new was copied
        if (desc.timestamp == lastClipStamp) return
        lastClipStamp = desc.timestamp
        if (!desc.hasMimeType("text/*")) return
        val url = clipText()?.let { t -> extractUrls(t).firstOrNull { isVideoLink(it) } } ?: return
        if (url == AppPrefs.clipSeen(this)) return
        AppPrefs.setClipSeen(this, url)
        val site = when {
            FastExtractor.supports(url) || isPlaylistUrl(url) -> "YouTube"
            url.contains("tiktok", true) -> "TikTok"
            url.contains("instagram", true) -> "Instagram"
            url.contains("facebook", true) || url.contains("fb.watch", true) -> "Facebook"
            else -> Uri.parse(url).host?.removePrefix("www.") ?: "Link"
        }
        clipDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_link)
            .setTitle("$site link copied")
            .setMessage(url)
            .setPositiveButton("Add to downloads") { _, _ -> showDownloadSheet(listOf(url)) }
            .setNegativeButton("Not now", null)
            .show()
    }

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
                else -> if (single) "Download video as" else "Download ${urls.size} videos as"
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
                !single -> urls.map { EnqueueItem(it, "", cookies[it], youtubeThumb(it)) }
                p != null && p.playlist.isNotEmpty() ->
                    p.playlist.map { EnqueueItem(it.url, it.title, cookies[firstUrl], it.thumbUrl) }
                // never save a status text like "Connecting…" as the title (blank = looked up by the service)
                else -> listOf(
                    EnqueueItem(firstUrl, p?.takeIf { !it.loading }?.title ?: knownTitle.orEmpty(), cookies[firstUrl], youtubeThumb(firstUrl))
                )
            }
            vm.enqueue(items, spec, sub)
            dialog.dismiss()
            // (no battery question: downloads run as a foreground service with a notification, which
            // Android keeps running; the optional setting is in Settings for phones that still stop them)
            val what = if (items.size == 1) "Added to downloads" else "Added ${items.size} items to downloads"
            Snackbar.make(b.root, what, Snackbar.LENGTH_LONG)
                .setAnchorView(snackAnchor())
                .setAction("View") { b.bottomNav.selectedItemId = R.id.nav_downloads }
                .show()
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

        // selection bar
        pl.closeSelBtn.setOnClickListener { exitSelection() }
        pl.selAllBtn.setOnClickListener { toggleSelectAll() }
        pl.actPause.setOnClickListener { pauseSelected() }
        pl.actResume.setOnClickListener { resumeSelected() }
        pl.actCancel.setOnClickListener { cancelSelected() }
        pl.actRemove.setOnClickListener { removeSelected() }
        pl.actDelete.setOnClickListener { deleteSelected() }
    }

    /** Library chips: Downloads shows the download manager, the others show your files. */
    private fun onLibraryMode() {
        if (selecting) exitSelection()
        pl.emptyState.isVisible = latestTasks.isEmpty() && library.showingDownloads
        pl.playScroll.isVisible = latestTasks.isNotEmpty() && library.showingDownloads
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

        pl.emptyState.isVisible = all.isEmpty() && library.showingDownloads
        pl.playScroll.isVisible = all.isNotEmpty() && library.showingDownloads
        library.onTasks(all)
        checkUpdateReady(all)

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

    /** Deletes the files (off the main thread); only rows whose file is really gone leave the list. */
    private fun deleteFilesOf(tasks: List<DownloadTask>) {
        val app = applicationContext
        lifecycleScope.launch {
            val gone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tasks.filter { t ->
                    val uri = t.fileUri ?: return@filter true
                    // the app's update is a file in the app's own folder, not in Downloads
                    if (t.format.startsWith(AppUpdater.TASK_PREFIX)) java.io.File(uri).let { !it.exists() || it.delete() }
                    else FileStore.delete(app, uri)
                }.map { it.id }
            }
            TaskRepository.removeMany(gone)
            val failed = tasks.size - gone.size
            message(
                if (failed == 0) (if (gone.size == 1) "File deleted" else "Deleted ${gone.size} files")
                else "$failed file(s) couldn't be deleted. Use \"Remove from list\", or delete them in your Downloads folder"
            )
        }
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
            Status.DONE -> if (t.format.startsWith(AppUpdater.TASK_PREFIX)) installUpdate(t) else openFile(t)
        }
    }

    private fun installUpdate(t: DownloadTask) {
        val path = t.fileUri ?: return
        AppUpdater.installDownloaded(this, java.io.File(path), t.format.removePrefix(AppUpdater.TASK_PREFIX))
    }

    /** The update finished downloading while the app is open: open the installer once. */
    private val updatePrompted = HashSet<String>()

    private fun checkUpdateReady(all: List<DownloadTask>) {
        val t = all.firstOrNull { it.format.startsWith(AppUpdater.TASK_PREFIX) && it.status == Status.DONE } ?: return
        val version = t.format.removePrefix(AppUpdater.TASK_PREFIX)
        if (!AppUpdater.isNewer(this, version) || version == AppPrefs.badRelease(this)) {
            // already installed: the finished update row and its file are not needed any more
            t.fileUri?.let { java.io.File(it).delete() }
            TaskRepository.remove(t.id)
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !updatePrompted.add(t.id)) return
        installUpdate(t)
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
        if (t.format.startsWith(AppUpdater.TASK_PREFIX)) {
            val menu = PopupMenu(this, anchor)
            menu.menu.add(0, 1, 0, "Install")
            menu.menu.add(0, 2, 1, "Delete")
            menu.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) installUpdate(t) else deleteFilesOf(listOf(t))
                true
            }
            menu.show()
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
                    AppPrefs.setClipSeen(this, t.url)
                    cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                    message("Link copied")
                }
                4 -> TaskRepository.remove(t.id)
                5 -> confirm("Delete file?", "\"${t.title}\" is deleted from your phone.") { deleteFilesOf(listOf(t)) }
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
        st.sponsorSwitch.isChecked = AppPrefs.sponsorBlock(this)
        st.sponsorSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setSponsorBlock(this, on) }
        st.resumeSwitch.isChecked = AppPrefs.resumeVideos(this)
        st.resumeSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setResumeVideos(this, on) }
        st.clipSwitch.isChecked = AppPrefs.clipDetect(this)
        st.clipSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setClipDetect(this, on) }
        st.batteryBtn.setOnClickListener { openBatterySettings() }
        st.checkAppUpdateBtn.setOnClickListener { AppUpdater.check(this, manual = true) }

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
        st.versionText.text = "RAINAX Tube  v$version"
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
    private fun snackAnchor(): View = b.navCard

    private fun message(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(snackAnchor()).show()
    }

    // ----- first run: storage access, then permission to install updates -----

    private var permDialog: AlertDialog? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askInstallUpdates() }

    /** Pop-up 1: storage (with notifications on Android 13+, for download progress). */
    private fun askStorage() {
        if (isFinishing || isDestroyed) return
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.READ_MEDIA_AUDIO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { askInstallUpdates(); return }
        permDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_download)
            .setTitle("Allow storage access")
            .setMessage("RAINAX Tube saves your videos and music on this phone, in your Download, Movies and Music folders. " +
                "Allow access so the app can save them and show them in your Library" +
                (if (Build.VERSION.SDK_INT >= 33) ", and show download progress in notifications." else "."))
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> storagePermission.launch(missing.toTypedArray()) }
            .setNegativeButton("Not now") { _, _ -> askInstallUpdates() }
            .show()
    }

    /** Pop-up 2: allow installing RAINAX updates (Android asks once per app). */
    private fun askInstallUpdates() {
        if (isFinishing || isDestroyed) return
        AppPrefs.setFirstRunAsked(this)
        if (packageManager.canRequestPackageInstalls()) return
        permDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_refresh)
            .setTitle("Allow app updates")
            .setMessage("New versions of RAINAX Tube download inside the app. To install them with one tap, " +
                "turn on \"Allow from this source\" on the next screen, then come back.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.onFailure { message("Open Settings > Apps > RAINAX Tube > Install unknown apps") }
            }
            .setNegativeButton("Not now", null)
            .show()
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
    /**
     * Settings > "Downloads stop with the screen off?": only for phones (some Xiaomi, Oppo, Vivo, Samsung)
     * whose battery saver closes apps even with a download notification. Never asked by itself.
     */
    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            message("Already allowed: downloads keep going with the screen off")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            message("Open Settings > Apps > RAINAX Tube > Battery and choose Unrestricted")
        }
    }

    companion object {
        private const val MODE_QUICK = 0
        private const val MODE_ALL = 1
        private const val MODE_SUBS = 2
    }
}
