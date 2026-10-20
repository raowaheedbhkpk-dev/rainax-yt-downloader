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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

    private lateinit var statusSaver: StatusSaver
    /** Android's folder screen, for the WhatsApp status folder. */
    private val statusFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (::statusSaver.isInitialized) statusSaver.onFolderPicked(uri)
    }

    private val activeHeader by lazy { SectionHeader(pl.activeSection) }
    private val doneHeader by lazy { SectionHeader(pl.doneSection) }

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
                else -> {                    // nothing to go back to: the app closes, and what plays pauses
                    controller?.takeIf { it.playWhenReady }?.pause()   // (resume from the notification)
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
            // (a brand-new install sees the welcome guide first)
            when {
                !AppPrefs.firstRunAsked(this) && !AppPrefs.guideSeen(this) ->
                    welcomeGuide.launch(Intent(this, WelcomeActivity::class.java))
                !AppPrefs.firstRunAsked(this) -> b.root.post { askStorage() }
                else -> requestNotificationPermission()
            }
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
        library.onMoveToPrivate = { moveToPrivate(it) }
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
        // Autoplay in feed (like YouTube): the video you stop on plays silently in the list
        FeedAutoplay.attach(hm.feedList)
        FeedAutoplay.attach(sb.subsList)
        FeedAutoplay.blocked = { video.isOpen || (::music.isInitialized && music.expanded) }
        home.onMenu = { openExplore() }
        home.onVoice = { startVoiceSearch() }
        home.onAllSubs = { b.bottomNav.selectedItemId = R.id.nav_subs }

        b.bottomNav.setOnItemSelectedListener {
            // a little bounce on the tapped tab (the middle one is the red "+")
            if (it.itemId == R.id.nav_social) b.navCard.fab?.let { f -> Motion.bounce(f) }
            else b.bottomNav.findViewById<View>(it.itemId)?.let { item -> Motion.bounce(item) }
            val chosen = when (it.itemId) {
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
            if (chosen) b.navCard.select(it.itemId)      // the red bar slides over to it
            chosen
        }
        // the red square "+" in the middle of the bar: Add (download from a link)
        b.navCard.fab?.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_social }

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
                launch {
                    // StateFlow keeps only the newest list; the pause limits redraws (1000 downloads stay smooth)
                    TaskRepository.tasks.collect {
                        renderTasks(it)
                        kotlinx.coroutines.delay(250)
                    }
                }
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
        b.root.postDelayed({ FastExtractor.warmUp() }, 800)    // sizes show faster on the first download
        FeedAutoplay.onResume()
        if (tab == 3 && ::statusSaver.isInitialized) statusSaver.refresh()     // new statuses (or access just given)
        renderFolder()                        // All files access may have just been given
        if (installAskAfterSettings) {
            installAskAfterSettings = false
            b.root.post { askInstallUpdates() }
        }
    }

    override fun onPause() {
        FeedAutoplay.onPause()
        super.onPause()
    }


    override fun onDestroy() {
        if (!blocked) {
            disconnectPlayer()            // the player service keeps playing on its own
            music.release()
            sheet?.dismiss()
            clipDialog?.dismiss()
            permDialog?.dismiss()
            AppUpdater.onQueued = null
            FeedAutoplay.release()
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
            signOut()
        }
        dialog.show()
    }

    private fun askSignOut() {
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Sign out of YouTube?")
            .setMessage("Your subscriptions, likes and history won't show in RAINAX until you sign in again. Your downloads stay.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ -> signOut() }
            .show()
    }

    private fun signOut() {
        YtAccount.signOut(this)
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        home.onAccountChanged()
        subs.onAccountChanged()
        if (tab == 4) subs.onShown()
        refreshYou()
        message("Signed out of YouTube")
    }

    /** Settings > Clear watch history: RAINAX's own history, and (signed in, if ticked) YouTube's too. */
    private fun askClearWatchHistory() {
        val signedIn = YtAccount.isSignedIn(this)
        val also = booleanArrayOf(signedIn)
        val builder = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Clear watch history?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                WatchHistory.clear(this)
                home.onHistoryCleared()
                if (!(signedIn && also[0])) { message("Watch history cleared"); return@setPositiveButton }
                lifecycleScope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YtAccount.clearYouTubeHistory() } }
                    message(when {
                        r.getOrNull() == true -> "Watch history cleared, here and on your YouTube account"
                        r.isSuccess -> "Cleared here. YouTube didn't allow clearing your account history from the app"
                        else -> "Cleared here. Couldn't reach YouTube to clear your account history"
                    })
                }
            }
        if (signedIn) builder.setMultiChoiceItems(arrayOf("Also clear my YouTube account history"), also) { _, _, on -> also[0] = on }
        else builder.setMessage("Continue watching and the places where you stopped will be forgotten.")
        builder.show()
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
            else -> {
                // it was playing in the list: carry on from there
                val start = FeedAutoplay.startFor(item.url)
                FeedAutoplay.stop()
                video.open(item.url, item.title, item.uploader, startMs = start, thumb = item.thumb)
            }
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
        val old = tab
        tab = index
        val pages = listOf(hm.root, pl.root, st.root, so.root, sb.root)
        pages.forEachIndexed { i, page ->
            page.animate().cancel()
            page.alpha = 1f
            page.translationX = 0f
            page.isVisible = i == index
        }
        // the new page slides in from the side of the tab that was tapped, and fades in
        if (old != index) pages.getOrNull(index)?.let { Motion.slideIn(it, fromRight = tabOrder(index) > tabOrder(old)) }
        updateChrome()
        if (index == 0) home.onShown()
        if (index == 1) refreshYou()
        if (index == 4) subs.onShown()
        if (index == 3 && ::statusSaver.isInitialized) statusSaver.refresh()
        if (index == 3) refreshClipStrip()
    }

    /** Where each page's tab sits in the bottom bar (Settings counts as after You), for the slide direction. */
    private fun tabOrder(index: Int) = when (index) { 0 -> 0; 3 -> 2; 4 -> 3; 1 -> 4; else -> 5 }

    /** The tab the chrome was last laid out for (the video page only animates when it opens on the same tab). */
    private var chromeTab = 0

    /** Video page over Home, bottom bar hidden in fullscreen, Back handling. */
    private fun updateChrome() {
        if (!::music.isInitialized) return
        // a video opened while the big Music player was open: show the video
        if (video.isOpen && !video.minimized && music.expanded) music.collapse()
        val wasShown = vp.root.isVisible
        vp.root.isVisible = video.isOpen && !video.minimized && tab == 0
        // a video opened (or expanded from the mini player): the page rises up
        if (vp.root.isVisible && !wasShown && chromeTab == tab) Motion.riseUp(vp.root)
        chromeTab = tab
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
            if (!wasMini) { placeMiniPlayer(); Motion.popIn(b.miniPlayer.root) }
        }
        syncVideoSurface()
        updateBack()
        FeedAutoplay.refresh()          // a video or tab change: the list preview stops or starts
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
        pl.youAccounts.setOnClickListener { onAccountClick() }
        pl.youProfile.setOnClickListener { onAccountClick() }
        pl.youBell.setOnClickListener { home.openNotifications() }
        pl.youSearch.setOnClickListener {
            goHome()
            b.root.post { home.openSearch() }
        }
        pl.youSecondBtn.setOnClickListener { askSignOut() }
        pl.youSignInBtn.setOnClickListener { signIn.launch(Intent(this, SignInActivity::class.java)) }
        // shortcuts after Downloads / Music / Videos / Playlists, like YouTube's Library
        listOf(
            "Private folder" to { PrivateActivity.open(this) },
            "History" to { openHomeList("History", if (YtAccount.isSignedIn(this)) "acc:FEhistory" else "local:history") },
            "Liked videos" to { openHomeList("Liked videos", "acc:VLLL") },
            "Watch later" to { openHomeList("Watch later", "acc:VLWL") }
        ).forEach { (title, action) ->
            val chip = layoutInflater.inflate(R.layout.item_chip, pl.libChips, false) as com.google.android.material.chip.Chip
            chip.id = View.generateViewId()
            chip.text = title
            chip.isCheckable = false
            if (title == "Private folder") {
                chip.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_lock)
                chip.isChipIconVisible = true
                chip.chipIconTint = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rx_text))
            }
            chip.setOnClickListener { action() }
            pl.libChips.addView(chip)
        }
        // the row starts at Downloads (it used to open scrolled to the end, hiding Downloads / Music / Videos)
        pl.libChipsScroll.post { pl.libChipsScroll.scrollTo(0, 0) }
        refreshYou()
    }

    /** You header: your YouTube picture and name, or Sign in. */
    private fun refreshYou() {
        val signedIn = YtAccount.isSignedIn(this)
        updateNavAvatar()
        // signed in: Sign out; signed out: one red Sign in button
        pl.youSecondBtn.isVisible = signedIn
        pl.youSignInBtn.isVisible = !signedIn
        pl.youAccounts.isVisible = signedIn
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
            else "Not signed in"
            Img.load(pl.youAvatar, null)
            pl.youAvatar.setImageResource(R.drawable.ic_person)
            val pad = (10 * resources.displayMetrics.density).toInt()
            pl.youAvatar.setPadding(pad, pad, pad, pad)
            pl.youAvatar.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rx_text2))
        }
    }

    private var navAvatarUrl: String? = null

    /** The bottom bar's You icon: your profile picture when signed in (like YouTube), the person icon otherwise. */
    private fun updateNavAvatar() {
        val item = b.bottomNav.menu.findItem(R.id.nav_downloads) ?: return
        val url = if (YtAccount.isSignedIn(this)) YtAccount.profile(this)?.avatar?.takeIf { it.isNotBlank() } else null
        if (url == null) {
            if (navAvatarUrl != null) item.setIcon(R.drawable.ic_nav_you)
            navAvatarUrl = null
            return
        }
        if (url == navAvatarUrl) return
        lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Img.bitmap(url, 160) }.getOrNull() }
                ?: return@launch
            if (isDestroyed) return@launch
            val size = (26 * resources.displayMetrics.density).toInt()
            item.icon = NavAvatarDrawable(bmp, size, ContextCompat.getColor(this@MainActivity, R.color.rx_text))
            navAvatarUrl = url
        }
    }

    private var exploreOpen = false

    /** One place in the side menu. */
    private class MenuRow(val label: String, val row: android.widget.LinearLayout, val iconBox: android.widget.FrameLayout,
                          val icon: ImageView, val text: TextView, val badge: TextView) {
        /** social sites: their coloured round badge stays as the icon's background */
        var socialBadge: android.graphics.drawable.Drawable? = null
    }

    private val menuRows = mutableListOf<MenuRow>()
    private val menuLabels = mutableListOf<TextView>()
    private var menuActive = "Home"
    private var menuText = 0
    private var menuIcon = 0

    /**
     * The side menu (≡): a floating card in the app's colours (light, dark, AMOLED): header with bell and ✕, your
     * account, search, then MENU / DISCOVER / GENERAL with a red bar on the open place and count badges.
     * Closes with ✕, Back, a tap outside, or a swipe to the left.
     */
    private fun setupExplore() {
        val e = b.explore
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        e.exploreScrim.setOnClickListener { closeExplore() }
        e.menuBell.setOnClickListener { closeExplore(); home.openNotifications() }
        e.menuProfile.setOnClickListener { closeExplore(); onAccountClick() }
        e.menuSearch.setOnClickListener {
            closeExplore()
            goHome()
            b.root.postDelayed({ home.openSearch() }, 200)
        }
        e.menuClose.setOnClickListener { closeExplore() }
        menuText = ContextCompat.getColor(this, R.color.rx_text)
        menuIcon = menuText
        // the card's colours come from the theme (white, dark grey, or pure black for AMOLED)
        fun attrColor(a: Int): Int {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(a, tv, true)
            return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
        }
        e.explorePanel.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(attrColor(R.attr.rxBg), attrColor(R.attr.rxBg))
        ).apply {
            cornerRadius = 2 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), ContextCompat.getColor(this@MainActivity, R.color.menu_stroke))
        }
        e.menuBrand.text = android.text.SpannableStringBuilder("RAINAX ").apply {
            val start = length
            append("Tube")
            setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.rx_primary)), start, length, 0)
        }
        // width: 300dp, or less on small phones (always leaves room to tap outside)
        e.explorePanel.layoutParams = e.explorePanel.layoutParams.apply {
            width = minOf((300 * d).toInt(), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        }
        // clear of the status bar and the gesture bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(e.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            (e.explorePanel.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = bars.top + dp(10)
                bottomMargin = bars.bottom + dp(12)
                marginStart = bars.left + dp(10)
            }
            e.explorePanel.requestLayout()
            insets
        }
        e.root.requestApplyInsets()
        // swipe left to close: the dark layer fades with the panel
        e.explorePanel.onDrag = { shown -> e.exploreScrim.alpha = shown.coerceIn(0f, 1f) }
        e.explorePanel.onSwipedClosed = { closeExplore() }

        var chapter = 0
        fun section(title: String) {
            chapter++
            val tv = TextView(this).apply {
                text = String.format(java.util.Locale.US, "%02d — %s", chapter, title)      // Cinema chapters
                textSize = 10.5f
                letterSpacing = 0.25f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.rx_gold))
                setPadding(dp(20), dp(14), dp(12), dp(6))
            }
            menuLabels += tv
            e.exploreList.addView(tv)
        }
        fun add(icon: Int, label: String, action: () -> Unit) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                isFocusable = true
                contentDescription = label
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this@MainActivity, R.animator.rx_press)
            }
            val box = android.widget.FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_menu_icon) }
            val iv = ImageView(this).apply {
                if (icon != 0) setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(menuIcon)
            }
            box.addView(iv, android.widget.FrameLayout.LayoutParams(dp(17), dp(17), android.view.Gravity.CENTER))
            row.addView(box, android.widget.LinearLayout.LayoutParams(dp(32), dp(32)))
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                maxLines = 1
                setTextColor(menuText)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@MainActivity, R.font.rx_body)
            }
            row.addView(tv, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            val badge = TextView(this).apply {
                setBackgroundResource(R.drawable.bg_menu_badge)
                gravity = android.view.Gravity.CENTER
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                isVisible = false
            }
            row.addView(badge, android.widget.LinearLayout.LayoutParams(dp(20), dp(20)))
            val r = MenuRow(label, row, box, iv, tv, badge)
            menuRows += r
            row.setOnClickListener {
                setMenuActive(label)
                // a moment to see the red bar move, then go
                b.root.postDelayed({ closeExplore(); action() }, 140)
            }
            e.exploreList.addView(row, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        }

        section("MENU")
        add(R.drawable.ic_nav_home, "Home") { goHome() }
        add(R.drawable.ic_shorts, "Shorts") { video.minimize(); ShortsActivity.open(this, 0) }
        add(R.drawable.ic_nav_subs, "Subscriptions") { b.bottomNav.selectedItemId = R.id.nav_subs }
        add(R.drawable.ic_download, "Downloads") { b.bottomNav.selectedItemId = R.id.nav_downloads }
        add(R.drawable.ic_history, "History") { openHomeList("History", if (YtAccount.isSignedIn(this)) "acc:FEhistory" else "local:history") }
        add(R.drawable.ic_watch_later, "Watch later") { openHomeList("Watch later", "acc:VLWL") }
        add(R.drawable.ic_thumb_up, "Liked videos") { openHomeList("Liked videos", "acc:VLLL") }
        section("DISCOVER")
        add(R.drawable.ic_music_note, "Music") { goHome(); video.minimize(); home.showMusic() }
        add(R.drawable.ic_trending, "Trending") { openHomeList("Trending", "q:trending") }
        add(R.drawable.ic_gaming, "Gaming") { openHomeList("Gaming", "q:gaming") }
        add(R.drawable.ic_news, "News") { openHomeList("News", "q:news today") }
        add(R.drawable.ic_sports, "Sports") { openHomeList("Sports", "q:sports highlights") }
        add(R.drawable.ic_movie, "Movies") { openHomeList("Movies", "q:full movie") }
        add(R.drawable.ic_podcast, "Podcasts") { openHomeList("Podcasts", "q:podcast") }
        add(R.drawable.ic_live, "Live") { openHomeList("Live", "q:live now") }
        section("GENERAL")
        add(R.drawable.ic_link, "Download from a link") { b.bottomNav.selectedItemId = R.id.nav_social }
        add(R.drawable.ic_gear, "Settings") {
            if (tab != 1) b.bottomNav.selectedItemId = R.id.nav_downloads
            showTab(2)
        }
        add(R.drawable.ic_close, "Sign out") { askSignOut() }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        e.exploreVersion.text = "RAINAX Tube" + (version?.let { " v$it" } ?: "")
        setMenuActive("Home")
    }

    /** The red bar moves to [label] (the open place). */
    private fun setMenuActive(label: String) {
        menuActive = label
        menuRows.forEach { r ->
            val on = r.label == label
            if (on) {
                r.row.background = ContextCompat.getDrawable(this, R.drawable.bg_menu_active)
                r.row.background.alpha = 0
                android.animation.ObjectAnimator.ofInt(r.row.background, "alpha", 0, 255).setDuration(180).start()
                if (r.socialBadge == null) r.iconBox.background = null
            } else {
                r.row.background = null
                if (r.socialBadge != null) r.iconBox.background = r.socialBadge else r.iconBox.setBackgroundResource(R.drawable.bg_menu_icon)
            }
            r.text.setTypeface(r.text.typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            r.text.setTextColor(if (on) android.graphics.Color.WHITE else menuText)
            r.icon.imageTintList = android.content.res.ColorStateList.valueOf(if (on) android.graphics.Color.WHITE else menuIcon)
        }
    }

    /** Account, open place and badges, fresh each time the menu opens. */
    private fun refreshMenu() {
        val e = b.explore
        val signedIn = YtAccount.isSignedIn(this)
        val p = if (signedIn) YtAccount.profile(this) else null
        e.menuName.text = p?.name?.takeIf { it.isNotBlank() } ?: if (signedIn) "Signed in" else "Not signed in"
        e.menuAccountHint.text = if (signedIn) "⇄  Switch account" else "Tap to sign in to YouTube"
        if (p?.avatar != null) {
            e.menuAvatar.imageTintList = null
            e.menuAvatar.setPadding(0, 0, 0, 0)
            Img.load(e.menuAvatar, p.avatar, circle = true, widthPx = 120)
        } else {
            Img.load(e.menuAvatar, null)
            e.menuAvatar.setImageResource(R.drawable.ic_person)
            val pad = (8 * resources.displayMetrics.density).toInt()
            e.menuAvatar.setPadding(pad, pad, pad, pad)
            e.menuAvatar.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.rx_text2))
        }
        menuRows.firstOrNull { it.label == "Sign out" }?.row?.isVisible = signedIn
        listOf("Watch later", "Liked videos").forEach { l -> menuRows.firstOrNull { it.label == l }?.row?.isVisible = signedIn }
        // downloads in progress: a red count, like the reference's "My List" badge
        val active = latestTasks.count { it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING }
        menuRows.firstOrNull { it.label == "Downloads" }?.badge?.let { bd ->
            bd.isVisible = active > 0
            bd.text = if (active > 9) "9+" else active.toString()
        }
        setMenuActive(when (tab) {
            1 -> "Downloads"
            2 -> "Settings"
            3 -> "Download from a link"
            4 -> "Subscriptions"
            else -> if (menuActive in listOf("Music", "Trending", "Gaming", "News", "Sports", "Movies", "Podcasts", "Live",
                    "History", "Watch later", "Liked videos")) menuActive else "Home"
        })
    }

    private fun openExplore() {
        val e = b.explore
        if (exploreOpen) return
        exploreOpen = true
        refreshMenu()
        val d = resources.displayMetrics.density
        // the card slides in from the side and fades up; its places rise in one after another
        e.explorePanel.animate().cancel()
        e.explorePanel.translationX = -(e.explorePanel.layoutParams.width + 40 * d)
        e.explorePanel.alpha = 0.6f
        e.root.isVisible = true
        e.explorePanel.animate().translationX(0f).alpha(1f).setDuration(300)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        e.exploreScrim.alpha = 0f
        e.exploreScrim.animate().alpha(1f).setDuration(240).start()
        menuRows.filter { it.row.isVisible }.forEachIndexed { i, r ->
            r.row.alpha = 0f
            r.row.translationX = -24 * d
            r.row.animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 18L).setDuration(220).start()
        }
        blurBehindMenu(true)
    }

    private fun closeExplore() {
        val e = b.explore
        if (!exploreOpen) return
        exploreOpen = false
        e.exploreScrim.animate().alpha(0f).setDuration(180).start()
        e.explorePanel.animate().translationX(-(e.explorePanel.width + 40 * resources.displayMetrics.density)).alpha(0.6f).setDuration(200)
            .withEndAction { if (!exploreOpen) e.root.isVisible = false }.start()
        blurBehindMenu(false)
    }

    /** The app behind the menu goes soft (frosted glass), on Android 12 and newer. */
    private fun blurBehindMenu(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val content = (b.root as ViewGroup).getChildAt(0) ?: return
        android.animation.ValueAnimator.ofFloat(if (on) 0f else 18f, if (on) 18f else 0f).apply {
            duration = if (on) 260 else 180
            addUpdateListener {
                val r = it.animatedValue as Float
                content.setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
            }
            start()
        }
    }

    /** The welcome guide closed: the first-run questions come next. */
    private val welcomeGuide =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppPrefs.setGuideSeen(this)
            if (!AppPrefs.firstRunAsked(this)) b.root.post { askStorage() }
        }

    private val voiceSearch =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) home.search(text)
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) VoicePage(this) { home.search(it) }.show() else startSystemVoiceSearch()
        }

    /**
     * Mic in the search box: RAINAX's own listening screen (glowing orb, your words as you speak). Without the
     * microphone permission or a speech recognizer, Android's voice search window is used instead.
     */
    private fun startVoiceSearch() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { startSystemVoiceSearch(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoicePage(this) { home.search(it) }.show()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSystemVoiceSearch() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say what you want to watch")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            voiceSearch.launch(i)
        } catch (e: Exception) {
            message("Voice search needs the Google app. Install or update it from the Play Store, then try again")
        }
    }

    // =====================================================================
    // Social tab and copied links
    // =====================================================================

    private fun setupSocial() {
        statusSaver = StatusSaver(
            this, so,
            pickFolder = { initial ->
                try { statusFolderPicker.launch(initial) } catch (e: Exception) { message("This phone has no folder picker") }
            },
            askFileAccess = { requestFileAccess(thenInstallAsk = false) }
        )
        statusSaver.setup()

        so.socialPaste.setOnClickListener {
            val text = clipText()
            val url = text?.let { extractUrls(it).firstOrNull() }
            if (url == null) message("Copy a video link first, then tap Paste") else so.socialInput.setText(url)
        }
        so.socialClear.setOnClickListener { so.socialInput.setText("") }
        so.socialInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                so.socialClear.isVisible = !s.isNullOrEmpty()
                showDetectedApp(s?.toString().orEmpty())
                if (so.socialClipStrip.isVisible) refreshClipStrip()      // pasted the copied link: the strip goes
            }
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

    /** The link the copied-link strip was used for (it hides for that link afterwards). */
    private var clipStripDone: String? = null

    /** Social page: a video link is on the clipboard → a strip "TikTok link copied · Download". */
    private fun refreshClipStrip() {
        if (isFinishing || isDestroyed) return
        val url = clipText()?.let { extractUrls(it).firstOrNull() }
        val typed = extractUrls(so.socialInput.text.toString()).firstOrNull()
        val show = url != null && url != clipStripDone && url != typed
        if (!show) { so.socialClipStrip.isVisible = false; return }
        val app = SocialApps.of(url!!)
        if (app != null) {
            so.socialClipBadge.background = SocialApps.badge(app, this)
            so.socialClipBadge.text = app.short
        } else {
            so.socialClipBadge.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x33FFFFFF)
            }
            so.socialClipBadge.text = "\u2193"
        }
        so.socialClipText.text = "${app?.name ?: "Video"} link copied"
        so.socialClipGo.setOnClickListener {
            clipStripDone = url
            AppPrefs.setClipSeen(this, url)
            so.socialClipStrip.animate().alpha(0f).setDuration(180).withEndAction {
                so.socialClipStrip.isVisible = false; so.socialClipStrip.alpha = 1f
            }.start()
            showDownloadSheet(listOf(url))
        }
        if (!so.socialClipStrip.isVisible) {
            so.socialClipStrip.isVisible = true
            Motion.popIn(so.socialClipStrip)
        }
    }

    /** Names the app a typed or pasted link is from, with its badge in the link box. */
    private fun showDetectedApp(text: String) {
        val link = extractUrls(text).firstOrNull()
        val app = link?.let { SocialApps.of(it) }
        so.socialLinkBadge.isVisible = app != null
        so.socialLinkIcon.isVisible = app == null
        so.socialDetected.isVisible = link != null
        if (app != null) {
            so.socialLinkBadge.background = SocialApps.badge(app, this)
            so.socialLinkBadge.text = app.short
            so.socialDetected.text = "${app.name} link  •  ${app.what}"
        } else if (link != null) {
            so.socialDetected.text = "Link from ${Uri.parse(link).host?.removePrefix("www.").orEmpty()}  •  RAINAX will look for the video"
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
            "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "soundcloud.com", "bandcamp.com",
            "x.com/", "twitter.com/"
        ).any { u.contains(it) }
    }

    private var lastClipStamp = -1L
    private var clipDialog: AlertDialog? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets apps read what was copied only while they are on screen with focus
        if (hasFocus && !blocked) b.root.postDelayed({ checkClipboard() }, 400)
        if (hasFocus && tab == 3) b.root.postDelayed({ refreshClipStrip() }, 250)
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
        clipDialog = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_link)
            .setTitle("$site link copied")
            .setMessage(url)
            .setPositiveButton("Add to downloads") { _, _ -> showDownloadSheet(listOf(url)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun extractUrls(text: String): List<String> =
        Regex("https?://\\S+").findAll(text).map { it.value }.distinct().take(MAX_SHARED_LINKS).toList()

    /** Links shared from other apps (Share > RAINAX). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val urls = extractUrls(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        if (urls.isNotEmpty()) {
            showDownloadSheet(urls)
        }
    }

    private fun showDownloadSheet(
        urls: List<String>, preferAudio: Boolean = false, knownTitle: String? = null,
        /** shown by a page over the app (the browser) instead of the message bar, which would sit behind it */
        onAdded: ((String) -> Unit)? = null
    ) {
        sheet?.dismiss()
        val sb = SheetDownloadBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)
        dialog.behavior.skipCollapsed = true
        sheet = dialog

        val single = urls.size == 1
        val firstUrl = urls[0]
        val cookies = urls.associateWith<String, String?> { null }
        val screenH = resources.displayMetrics.heightPixels

        var current: PreviewState? = null
        var mode = MODE_QUICK
        // Video or Audio tab, each with its own selected format (the bar shows the one of the open tab)
        var tabKind = if (preferAudio) KIND_AUDIO else KIND_VIDEO
        var selVideo: String? = null
        var selAudio: String? = if (preferAudio) "audio:m4a" else null
        var selectedSub: String? = null
        fun selectedSpecNow(): String? = if (tabKind == KIND_AUDIO) selAudio else selVideo
        fun select(spec: String?) { if (tabKind == KIND_AUDIO) selAudio = spec else selVideo = spec }

        var choiceAction: (FormatChoice) -> Unit = {}
        var navAction: (String) -> Unit = {}
        val adapter = FormatAdapter({ choiceAction(it) }, { navAction(it) })
        val cards = DlCardAdapter({ choiceAction(it) }, { navAction("all") })
        val grid = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        val linear = LinearLayoutManager(this)
        sb.formatList.layoutManager = grid
        sb.formatList.adapter = cards
        sb.formatList.itemAnimator = null
        sb.formatList.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.rx_list_rise)
        var cardsShown = false
        sb.closeBtn.setOnClickListener { dialog.dismiss() }
        sb.dlThumbBox.clipToOutline = true          // rounded picture corners
        // our own rounded, softly tinted background (not the default white sheet)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        fun group(list: List<FormatChoice>, detailed: Boolean): List<FormatRow> {
            val out = mutableListOf<FormatRow>()
            val mine = list.filter { it.kind == tabKind }
            if (mine.isNotEmpty()) {
                out += FormatRow.Section(if (tabKind == KIND_AUDIO) "Audio" else "Video")
                mine.forEach { out += FormatRow.Choice(it, it.spec == selectedSpecNow(), detailed) }
            }
            return out
        }

        fun specHeight(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

        /** The card's words: "High Quality" / "720p" for video, "Original quality" / "M4A" for sound. */
        fun cardOf(c: FormatChoice, recommended: Boolean): DlCard.Format {
            val selected = c.spec == selectedSpecNow()
            val size = c.size.ifBlank { "Size unknown" }
            if (c.kind == KIND_AUDIO) {
                val parts = c.spec.split(':')
                val ext = parts.getOrNull(1).orEmpty()
                val (tag, q) = when (ext) {
                    "m4a" -> "Original · Instant" to "M4A"
                    "mp3" -> {
                        val kbps = parts.getOrNull(2)?.toIntOrNull() ?: 192
                        (when {
                            kbps <= 128 -> "MP3 · Small"
                            kbps >= 320 -> "MP3 · Best"
                            else -> "MP3 · Standard"
                        }) to "${kbps}k"
                    }
                    "" -> "Sound only" to "Audio"
                    else -> "Sound only" to ext.uppercase()
                }
                return DlCard.Format(c, tag, q, size, recommended, selected)
            }
            val h = specHeight(c.spec)
            val q = when {
                h >= 4320 -> "8K"
                h >= 2160 -> "4K"
                h >= 1440 -> "2K"
                h > 0 -> "${h}p"
                else -> "Best"
            }
            val tag = when {
                h == 0 -> "MP4"
                h < 720 -> "Fast"
                h < 1440 -> "High Quality"
                else -> "Ultra HD"
            }
            return DlCard.Format(c, tag, q, size, recommended, selected)
        }

        /** The suggested card of a list: 720p (or nearest) for video, M4A for sound. */
        fun recommendedOf(list: List<FormatChoice>): FormatChoice? {
            if (list.size < 2) return list.firstOrNull()
            return if (tabKind == KIND_AUDIO) list.firstOrNull { it.spec == "audio:m4a" } ?: list.first()
            else list.filter { specHeight(it.spec) > 0 }.minByOrNull { kotlin.math.abs(specHeight(it.spec) - 720) } ?: list.first()
        }

        /** "720p (280.0 MB)" for the bar. */
        fun shortLabel(c: FormatChoice): String {
            val card = cardOf(c, false)
            return if (c.size.isBlank()) card.quality else "${card.quality} (${c.size})"
        }

        // Several videos (picked with +) and playlists: each video's own formats are looked up (4 at a time),
        // and only the formats those videos really have are shown, with their sizes added up.
        // A playlist uses its first videos and scales their sizes to the whole list (shown with ≈).
        val found = java.util.concurrent.ConcurrentHashMap<String, PreviewState>()
        var lookups: Job? = null
        var sampled: List<PlaylistEntry> = emptyList()
        var renderNow: () -> Unit = {}
        // many picked videos: sizes come from the first few and are scaled up (≈), so YouTube isn't asked
        // about hundreds of videos at once (that can get the connection blocked for a while)
        val sampleUrls = if (single) urls else urls.take(Picks.SAMPLE)
        val sampledMany = sampleUrls.size < urls.size
        // many lookups finish close together: draw once per frame, not once per video
        var renderQueued = false
        val renderSoon = {
            if (!renderQueued) {
                renderQueued = true
                b.root.post { renderQueued = false; if (sheet === dialog) renderNow() }
            }
        }

        fun lookUp(list: List<String>) {
            lookups?.cancel()
            lookups = lifecycleScope.launch {
                list.forEach { u ->
                    launch {
                        found[u] = vm.infoFor(u)
                        renderSoon()
                    }
                }
            }
        }

        fun heightOf(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

        /** What [spec] downloads for one video: that format, else its nearest lower video quality. */
        fun bytesFor(st: PreviewState, spec: String): Long {
            st.all.firstOrNull { it.spec == spec }?.let { return it.bytes }
            if (!spec.startsWith("video")) return 0
            val want = heightOf(spec).takeIf { it > 0 } ?: Int.MAX_VALUE
            val videos = st.all.filter { it.kind == KIND_VIDEO }
            return (videos.filter { heightOf(it.spec) <= want }.maxByOrNull { heightOf(it.spec) }
                ?: videos.minByOrNull { heightOf(it.spec) })?.bytes ?: 0
        }

        /** The formats these videos have, each with the total size ([scale] > 1: a playlist estimate). */
        fun combine(states: List<PreviewState>, scale: Double): Pair<List<FormatChoice>, List<FormatChoice>> {
            val ok = states.filter { it.error == null && it.all.isNotEmpty() }
            if (ok.isEmpty()) return emptyList<FormatChoice>() to emptyList()
            val bySpec = LinkedHashMap<String, FormatChoice>()
            ok.forEach { st -> st.all.forEach { c -> bySpec.putIfAbsent(c.spec, c) } }
            val all = bySpec.values.map { c ->
                val sum = (ok.sumOf { bytesFor(it, c.spec) } * scale).toLong()
                c.copy(size = if (sum > 0) (if (scale > 1.01) "≈ " else "") + formatSize(sum) else "", bytes = sum)
            }.sortedWith(compareBy<FormatChoice>({ if (it.kind == KIND_AUDIO) 0 else 1 }, { heightOf(it.spec) }))
            val videos = all.filter { it.kind == KIND_VIDEO }
            val quickVideo = listOfNotNull(
                videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 360) },
                videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 720) },
                videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 1080) }
            ).distinctBy { it.spec }.sortedBy { heightOf(it.spec) }
            return (all.filter { it.kind == KIND_AUDIO } + quickVideo) to all
        }

        fun render() {
            val p = current
            val isPlaylist = single && p?.playlist?.isNotEmpty() == true
            if (isPlaylist && sampled.isEmpty()) {
                sampled = p!!.playlist.take(6)
                lookUp(sampled.map { it.url })
            }
            val waiting = when {
                !single -> sampleUrls.count { found[it] == null }
                isPlaylist -> sampled.count { found[it.url] == null }
                else -> 0
            }
            val (quick, all) = when {
                !single -> {
                    val read = sampleUrls.mapNotNull { found[it] }
                    val good = read.count { it.error == null && it.all.isNotEmpty() }
                    combine(read, if (sampledMany && good > 0) urls.size.toDouble() / good else 1.0)
                }
                isPlaylist -> {
                    val list = p!!.playlist
                    val known = list.filter { it.seconds > 0 }
                    val avg = if (known.isNotEmpty()) known.sumOf { it.seconds } / known.size else 240L
                    fun len(e: PlaylistEntry) = if (e.seconds > 0) e.seconds else avg
                    val done = sampled.filter { found[it.url]?.let { st -> st.error == null } == true }
                    val scale = if (done.isEmpty()) 1.0
                    else list.sumOf { len(it) }.toDouble() / done.sumOf { len(it) }.coerceAtLeast(1)
                    combine(done.mapNotNull { found[it.url] }, scale.coerceAtLeast(1.0))
                }
                p == null || p.loading || p.error != null -> emptyList<FormatChoice>() to emptyList()
                else -> p.quick to p.all
            }
            val loading = (single && !isPlaylist && (p == null || p.loading)) || (waiting > 0 && quick.isEmpty()) ||
                (single && p == null)
            val error = when {
                single && !isPlaylist -> p?.error
                !single && waiting == 0 && quick.isEmpty() -> "Couldn't read these videos. Check your internet, then tap Retry."
                isPlaylist && waiting == 0 && quick.isEmpty() -> "Couldn't read this playlist's videos. Tap Retry."
                else -> null
            }
            val subs = if (single && !isPlaylist) p?.subtitles.orEmpty() else emptyList()

            // a link with only sound (or only video): that tab, and no tab switch
            val hasVideo = (quick + all).any { it.kind == KIND_VIDEO }
            val hasAudio = (quick + all).any { it.kind == KIND_AUDIO }
            if (!hasVideo && hasAudio) tabKind = KIND_AUDIO
            if (!hasAudio && hasVideo) tabKind = KIND_VIDEO
            val tabQuick = quick.filter { it.kind == tabKind }
            val tabAll = all.filter { it.kind == tabKind }

            // picked while sizes were loading, but this video has no such format: select the closest real one,
            // and nothing picked yet: the recommended one (so DOWNLOAD works at once)
            val sel = selectedSpecNow()
            if (tabQuick.isNotEmpty() && (sel == null || (tabQuick + tabAll).none { it.spec == sel })) {
                select(if (sel == null || tabKind == KIND_AUDIO) recommendedOf(tabQuick)?.spec else {
                    val want = specHeight(sel).takeIf { it > 0 } ?: Int.MAX_VALUE
                    (tabQuick.filter { specHeight(it.spec) <= want }.maxByOrNull { specHeight(it.spec) } ?: recommendedOf(tabQuick))?.spec
                })
            }
            val selectedSpec = selectedSpecNow()

            // header: picture, title, a short line under it
            val title = when {
                !single -> "Download ${urls.size} videos"
                else -> (p?.takeIf { !it.loading }?.title ?: knownTitle)?.takeIf { it.isNotBlank() } ?: "Download"
            }
            sb.sheetHeading.text = when (mode) {
                MODE_ALL -> "More formats"
                MODE_SUBS -> "Subtitles/CC"
                else -> title
            }
            sb.backBtn.isVisible = mode != MODE_QUICK
            sb.dlThumbBox.isVisible = mode == MODE_QUICK
            val thumb = p?.thumbUrl ?: youtubeThumb(firstUrl) ?: found[firstUrl]?.thumbUrl
            if (sb.dlThumb.getTag(R.id.img_url) != thumb) Img.load(sb.dlThumb, thumb, widthPx = 320)
            val duration = if (single && !isPlaylist) p?.subtitle?.let { Regex("(\\d+:)?\\d{1,2}:\\d{2}$").find(it.trim())?.value } else null
            sb.dlDuration.isVisible = duration != null
            sb.dlDuration.text = duration
            sb.sheetSub.text = when {
                mode != MODE_QUICK -> title
                !single && waiting > 0 -> "Getting sizes…  ${sampleUrls.size - waiting} of ${sampleUrls.size}"
                !single && sampledMany -> "They download one after another  •  sizes estimated from ${sampleUrls.size} videos"
                !single -> sampleUrls.count { found[it]?.error != null }.let { bad ->
                    if (bad > 0) "They download one after another  •  $bad couldn't be read" else "They download one after another"
                }
                loading -> if (p?.title != null || knownTitle != null) "Getting sizes…" else "Fetching info…"
                error != null -> Uri.parse(firstUrl).host.orEmpty()
                isPlaylist -> "${p!!.subtitle.orEmpty()}  •  whole playlist".trimStart(' ', '•') + if (waiting > 0) "  •  getting sizes…" else ""
                else -> "Choose a format to download"
            }
            sb.previewLoading.isVisible = loading || waiting > 0

            // Video / Audio tabs
            sb.dlTabs.isVisible = mode != MODE_SUBS && ((hasVideo && hasAudio) || (single && !isPlaylist && loading))
            // icon and word side by side in the middle of each tab
            fun styleTab(box: View, icon: android.widget.ImageView, text: android.widget.TextView, on: Boolean) {
                val color = if (on) android.graphics.Color.WHITE else androidx.core.content.ContextCompat.getColor(this, R.color.dl_text2)
                box.setBackgroundResource(if (on) R.drawable.bg_dl_tab_on else 0)
                box.elevation = if (on) 4 * resources.displayMetrics.density else 0f
                box.isSelected = on
                text.setTextColor(color)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            }
            styleTab(sb.tabVideo, sb.tabVideoIcon, sb.tabVideoText, tabKind == KIND_VIDEO)
            styleTab(sb.tabAudio, sb.tabAudioIcon, sb.tabAudioText, tabKind == KIND_AUDIO)

            sb.errorBlock.isVisible = error != null
            if (error != null) {
                sb.errorText.text = error
            }
            sb.playlistBtn.isVisible = false

            if (mode == MODE_QUICK) {
                // the cards: this tab's main formats, then "More Formats"
                val rec = recommendedOf(tabQuick)?.spec
                val list = tabQuick.map { cardOf(it, it.spec == rec && tabQuick.size > 1) }.toMutableList<DlCard>()
                val moreCount = tabAll.count { a -> tabQuick.none { it.spec == a.spec } }
                if (tabAll.isNotEmpty() && (moreCount > 0 || (tabKind == KIND_VIDEO && subs.isNotEmpty()))) {
                    list += DlCard.More(if (moreCount > 0) "View all ${tabAll.size}" else "Subtitles")
                }
                if (sb.formatList.adapter !== cards) { sb.formatList.layoutManager = grid; sb.formatList.adapter = cards }
                val showing = if (loading || error != null) emptyList() else list
                // the cards rise in when they first appear
                if (showing.isNotEmpty() && !cardsShown) { cardsShown = true; sb.formatList.scheduleLayoutAnimation() }
                cards.submit(showing)
            } else if (sb.formatList.adapter !== adapter) {
                sb.formatList.layoutManager = linear
                sb.formatList.adapter = adapter
            }
            val selChoice = (quick + all).firstOrNull { it.spec == selectedSpec }
            sb.selectedLabel.text = when {
                selChoice != null -> shortLabel(selChoice) + if (tabKind == KIND_VIDEO && selectedSub != null) "  •  CC" else ""
                loading || waiting > 0 -> "Getting sizes…"
                else -> "Nothing selected"
            }

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
                    if (subs.isNotEmpty() && tabKind == KIND_VIDEO) {
                        val label = subs.firstOrNull { it.code == selectedSub }?.label ?: "Off"
                        list += FormatRow.Nav("subs", "Subtitles/CC", label, R.drawable.ic_cc)
                    }
                    list
                }
                else -> emptyList()
            }
            if (mode != MODE_QUICK) adapter.submitList(rows)
            // only formats that are really shown can be downloaded
            sb.downloadBtn.isEnabled = selectedSpec != null && (quick + all).any { it.spec == selectedSpec }
            sb.downloadBtn.alpha = if (sb.downloadBtn.isEnabled) 1f else 0.5f

            val fraction = if (mode == MODE_QUICK) 0.72 else 0.9
            sb.root.minimumHeight = (screenH * fraction).toInt()
        }

        renderNow = { render() }
        choiceAction = { c ->
            if (mode == MODE_SUBS) {
                selectedSub = c.spec.ifEmpty { null }
                mode = MODE_ALL
            } else {
                select(c.spec)
            }
            render()
        }
        // switching Video / Audio: the other cards rise in
        sb.tabVideo.setOnClickListener { if (tabKind != KIND_VIDEO) { tabKind = KIND_VIDEO; sb.formatList.scheduleLayoutAnimation(); render() } }
        sb.tabAudio.setOnClickListener { if (tabKind != KIND_AUDIO) { tabKind = KIND_AUDIO; sb.formatList.scheduleLayoutAnimation(); render() } }
        navAction = { id ->
            mode = if (id == "all") MODE_ALL else MODE_SUBS
            render()
        }
        sb.backBtn.setOnClickListener {
            mode = if (mode == MODE_SUBS) MODE_ALL else MODE_QUICK
            render()
        }
        sb.retryBtn.setOnClickListener {
            when {
                !single -> { found.clear(); lookUp(sampleUrls) }
                current?.playlist?.isNotEmpty() == true -> { found.clear(); sampled = emptyList() }
                else -> vm.fetchInfo(firstUrl, cookies[firstUrl])
            }
            render()
        }
        sb.playlistBtn.setOnClickListener {
            val id = Uri.parse(firstUrl).getQueryParameter("list").orEmpty()
            vm.fetchInfo("https://www.youtube.com/playlist?list=$id", cookies[firstUrl], forcePlaylist = true)
        }

        sb.downloadBtn.setOnClickListener {
            val spec = selectedSpecNow() ?: return@setOnClickListener
            val p = current
            val sub = if (spec.startsWith("video")) selectedSub else null
            val items = when {
                !single -> urls.map { EnqueueItem(it, found[it]?.title.orEmpty(), cookies[it], found[it]?.thumbUrl ?: youtubeThumb(it)) }
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
            if (onAdded != null) { onAdded(what); return@setOnClickListener }
            Snackbar.make(b.root, what, Snackbar.LENGTH_LONG)
                .setAnchorView(snackAnchor())
                .setAction("View") { b.bottomNav.selectedItemId = R.id.nav_downloads }
                .show()
        }

        var collectJob: Job? = null
        if (!single) lookUp(sampleUrls)
        if (single) {
            vm.fetchInfo(firstUrl, cookies[firstUrl])
            collectJob = lifecycleScope.launch {
                vm.preview.collect { current = it; render() }
            }
        }
        render()

        dialog.setOnDismissListener {
            collectJob?.cancel()
            lookups?.cancel()
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
        // both sections in one recycling list: only the rows on screen are made, however many downloads there are
        pl.playList.layoutManager = LinearLayoutManager(this)
        (pl.playList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        pl.playList.adapter = androidx.recyclerview.widget.ConcatAdapter(activeHeader, activeAdapter, doneHeader, doneAdapter)

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
        countForRating(done)

        activeHeader.visible = active.isNotEmpty()
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
        val nearTop = pl.playList.computeVerticalScrollOffset() < resources.displayMetrics.heightPixels / 2
        activeAdapter.submitList(newActive) { if (topChanged && nearTop) pl.playList.scrollToPosition(0) }

        doneHeader.visible = done.isNotEmpty()
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
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
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

    /** Downloads that finished while the app is open (the ones already done at start don't count). */
    private var doneSeen: HashSet<String>? = null

    private fun countForRating(done: List<DownloadTask>) {
        val ids = done.filter { !it.format.startsWith(AppUpdater.TASK_PREFIX) }.map { it.id }
        val seen = doneSeen ?: run { doneSeen = HashSet(ids); return }
        val fresh = ids.count { seen.add(it) }
        if (fresh > 0) AppShare.onDownloadsDone(this, fresh, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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

    /** Hides a finished download in the Private folder (set it up first if needed). */
    private fun moveToPrivate(t: DownloadTask) {
        if (!PrivateVault.hasLock(this)) {
            RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
                .setTitle("Set up Private folder")
                .setMessage("Choose a PIN, pattern or password first. Then tap “Move to Private folder” again.")
                .setPositiveButton("Set up") { _, _ -> PrivateActivity.open(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        message("Moving to Private folder…")
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PrivateVault.moveIn(this@MainActivity, t) }.isSuccess }
            message(if (ok) "Moved to Private folder" else "Couldn't move this file")
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
        popup.menu.add(0, 7, 3, "Move to Private folder")
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
                7 -> moveToPrivate(t)
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
            Notify.show(this, "Can't open this file")
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
            Notify.show(this, "Can't share this file")
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
        // the whole row is tappable (ripple + a slight press), not only the small switch
        listOf(st.wifiSwitch, st.autoClearSwitch, st.bgPlaySwitch, st.sponsorSwitch, st.resumeSwitch, st.clipSwitch, st.autoRetrySwitch)
            .forEach { sw ->
                val row = sw.parent as? View ?: return@forEach
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                row.setBackgroundResource(tv.resourceId)
                row.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.rx_press)
                row.setOnClickListener { sw.toggle() }
            }
        st.batteryBtn.setOnClickListener { openBatterySettings() }
        st.checkAppUpdateBtn.setOnClickListener { AppUpdater.check(this, manual = true) }
        st.allFilesBtn.setOnClickListener { openAllFilesSettings(thenInstallAsk = false) }
        st.privateFolderBtn.setOnClickListener { PrivateActivity.open(this) }
        st.clearSearchBtn.setOnClickListener {
            RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
                .setTitle("Clear search history?")
                .setMessage("Your recent searches will be removed from this phone.")
                .setPositiveButton("Clear") { _, _ ->
                    AppPrefs.clearSearchHistory(this)
                    Notify.show(this, "Search history cleared")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        st.clearWatchBtn.setOnClickListener { askClearWatchHistory() }
        st.historyPauseSwitch.isChecked = AppPrefs.historyPaused(this)
        st.historyPauseSwitch.setOnCheckedChangeListener { _, on ->
            AppPrefs.setHistoryPaused(this, on)
            message(if (on) "Watch history paused" else "Watch history is on")
        }
        (st.historyPauseSwitch.parent as? View)?.setOnClickListener { st.historyPauseSwitch.toggle() }
        st.unhideBtn.setOnClickListener {
            val n = HiddenFeed.count()
            HiddenFeed.clear(this)
            message(if (n > 0) "Hidden videos and channels can show again" else "Nothing is hidden")
        }
        st.rateAppBtn.setOnClickListener { AppShare.rate(this) }
        st.shareAppBtn.setOnClickListener { AppShare.share(this) }

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
        st.autoplayGroup.check(
            when (AppPrefs.feedAutoplay(this)) {
                0 -> R.id.autoplayOff
                1 -> R.id.autoplayWifi
                else -> R.id.autoplayAlways
            }
        )
        st.autoplayGroup.setOnCheckedStateChangeListener { _, ids ->
            AppPrefs.setFeedAutoplay(this, when (ids.firstOrNull()) { R.id.autoplayOff -> 0; R.id.autoplayWifi -> 1; else -> 2 })
            FeedAutoplay.refresh()
        }
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
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        st.versionText.text = "RAINAX Tube  v$version"
    }

    private fun renderFolder() {
        st.folderPath.text = FileStore.describe(this)
        st.allFilesBtn.isVisible = needsAllFiles()
        val mp3 = AppPrefs.lastMp3Report(this)
        st.mp3Report.isVisible = mp3.isNotBlank()
        st.mp3Report.text = "Last MP3: $mp3"
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
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Android 11+: then All files access (WhatsApp statuses), then the updates question
            if (needsAllFiles()) openAllFilesSettings(thenInstallAsk = true) else askInstallUpdates()
        }
    /** Android 10: storage asked from the status saver. */
    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (tab == 3 && ::statusSaver.isInitialized) statusSaver.refresh() }
    /** Came back from the All files access screen: ask about updates next (first start only). */
    private var installAskAfterSettings = false

    private fun needsAllFiles() = Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

    private fun openAllFilesSettings(thenInstallAsk: Boolean) {
        installAskAfterSettings = thenInstallAsk
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.isSuccess
        if (!opened) {
            installAskAfterSettings = false
            message("Open Settings > Apps > RAINAX Tube > Files and media > Allow management of all files")
            if (thenInstallAsk) askInstallUpdates()
        }
    }

    /** File access for the status saver (and your files): All files access on Android 11+, storage on Android 10. */
    private fun requestFileAccess(thenInstallAsk: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) openAllFilesSettings(thenInstallAsk)
        else legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)     // (includes reading)
    }

    /** Pop-up 1: storage (with notifications on Android 13+, for download progress). */
    private fun askStorage() {
        if (isFinishing || isDestroyed) return
        // Android 11+: "All files access" covers saving and reading everything (no "Photos and videos" question);
        // Android 13+ also asks for notifications (download progress). Android 10: the storage permission.
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT < 30) {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE        // Android 10: Rainax Tube folder
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty() && !needsAllFiles()) { askInstallUpdates(); return }
        permDialog = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_download)
            .setTitle("Allow storage access")
            .setMessage("RAINAX Tube saves your videos, music and WhatsApp statuses in Internal storage/Rainax Tube. " +
                "Allow access so the app can save them and show them in your Library" +
                (if (Build.VERSION.SDK_INT >= 33) ", and show download progress in notifications." else ".") +
                (if (Build.VERSION.SDK_INT >= 30) "\n\nOn the next screen, turn on “Allow access to manage all files”." else ""))
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                if (missing.isNotEmpty()) storagePermission.launch(missing.toTypedArray())
                else openAllFilesSettings(thenInstallAsk = true)
            }
            .setNegativeButton("Not now") { _, _ -> askInstallUpdates() }
            .show()
    }

    /** Pop-up 2: allow installing RAINAX updates (Android asks once per app). */
    private fun askInstallUpdates() {
        if (isFinishing || isDestroyed) return
        AppPrefs.setFirstRunAsked(this)
        if (packageManager.canRequestPackageInstalls()) return
        permDialog = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
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
        /** Most links taken from one shared or pasted text. */
        private const val MAX_SHARED_LINKS = 1000
    }
}
