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
import android.widget.LinearLayout
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

    internal lateinit var b: ActivityMainBinding
    internal val vm: MainViewModel by viewModels()

    internal val hm get() = b.homePage
    internal val vp get() = b.videoPage
    internal val pl get() = b.playPage
    internal val st get() = b.settingsPage
    internal val so get() = b.socialPage
    internal val sb get() = b.subsPage

    internal var tab = 0            // pages: 0 Home, 1 You (library), 2 Settings, 3 Add link (+), 4 Subscriptions
    internal lateinit var home: HomeScreen
    internal lateinit var video: VideoScreen
    internal lateinit var library: LibraryScreen
    internal lateinit var music: MusicPlayer
    internal lateinit var subs: SubsScreen
    /** Bumped when the Settings page should read the folder / file access / MP3 note again. */
    internal val settingsTick = androidx.compose.runtime.mutableIntStateOf(0)
    internal var pendingVideo: String? = null     // reopen this video page once the player is connected

    internal var latestTasks: List<DownloadTask> = emptyList()
    internal var selecting = false
    internal val selected = linkedSetOf<String>()
    internal var sheet: BottomSheetDialog? = null

    internal val activeAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openOrWatch(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )
    internal val doneAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openOrWatch(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )

    internal lateinit var statusSaver: StatusSaver
    /** Android's folder screen, for the WhatsApp status folder. */
    internal val statusFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (::statusSaver.isInitialized) statusSaver.onFolderPicked(uri)
    }

    internal val collectionHeader by lazy { SectionHeader(pl.collectionSection) }
    internal val shelf = ShelfAdapter { openFile(it) }
    internal var meterKey = ""
    internal val activeHeader by lazy { SectionHeader(pl.activeSection) }
    internal val doneHeader by lazy { SectionHeader(pl.doneSection) }

    internal val signIn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            home.onAccountChanged()
            subs.onAccountChanged()
            if (tab == 4) subs.onShown()
            refreshYou()
            message("Signed in to YouTube")
            refreshAccount()
        }

    internal val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    internal val backCallback = object : OnBackPressedCallback(true) {
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
    internal var blocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)   // pure black
        Ui.applyAccent(this)
        if (!Integrity.isOfficial(this)) {
            blocked = true
            super.onCreate(null)
            Integrity.showNotOfficial(this)
            return
        }
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        Ui.pillExtra = 0                      // (a rebuilt screen starts without the update pill's extra room)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (savedInstanceState == null && AppPrefs.openingTitle(this)) OpeningCredits.show(b.root)
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
                // later runs: notifications first; when those are fine, maybe the background-run popup
                // (after the short opening title)
                !requestNotificationPermission() -> b.root.postDelayed({ maybeAskBackgroundRun() }, 1600)
            }
        }

        home = HomeScreen(this, hm, vm, { openItem(it) }, { showDownloadSheet(listOf(it.url), knownTitle = it.title.ifBlank { null }) }) { onAccountClick() }
        home.setup()
        fitWideScreen()
        home.downloadMany = { list -> showDownloadSheet(list.map { it.url }) }
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
        home.onTheme = { showThemePopup(it) }
        home.onVoice = { startVoiceSearch() }
        home.onAllSubs = { b.bottomNav.selectedItemId = R.id.nav_subs }

        b.bottomNav.setOnItemSelectedListener {
            Haptics.tick(b.bottomNav)
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

        Ui.noRestore(b.root)        // every page: chips and switches come from the app, not from a replay
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
    internal fun fitWideScreen() {
        // the bottom bar is full width (flat, like YouTube); on wide screens its buttons stay close together
        val wide = resources.configuration.screenWidthDp >= 600
        val lp = b.bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = if (wide) (560 * resources.displayMetrics.density).toInt() else android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        b.bottomNav.layoutParams = lp
        if (::home.isInitialized) home.applyColumns()
        if (pl.playList.adapter != null) applyPlayColumns()
    }

    /**
     * Tablets and wide screens: finished downloads sit in 2 columns (3 on very wide screens); the
     * collection, the section titles and the rows still downloading keep the full width.
     */
    internal fun applyPlayColumns() {
        val w = resources.configuration.screenWidthDp
        val cols = when {
            w >= 1000 -> 3
            w >= 600 -> 2
            else -> 1
        }
        val list = pl.playList
        if (cols == 1) {
            if (list.layoutManager is androidx.recyclerview.widget.GridLayoutManager || list.layoutManager == null) {
                list.layoutManager = LinearLayoutManager(this)
            }
            return
        }
        val grid = (list.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
            ?: androidx.recyclerview.widget.GridLayoutManager(this, cols).also { list.layoutManager = it }
        grid.spanCount = cols
        grid.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val before = collectionHeader.itemCount + activeHeader.itemCount + activeAdapter.itemCount + doneHeader.itemCount
                return if (position >= before) 1 else cols
            }
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
        if (tab == 0 && ::home.isInitialized) home.onShown()
        b.root.postDelayed({ FastExtractor.warmUp() }, 800)    // sizes show faster on the first download
        FeedAutoplay.onResume()
        if (tab == 3 && ::statusSaver.isInitialized) statusSaver.refresh()     // new statuses (or access just given)
        renderFolder()                        // All files access may have just been given
        if (bgAskPending) {
            bgAskPending = false
            message(if (canRunInBackground()) "Done: RAINAX keeps running with the screen off"
                else "Not allowed. You can turn it on later in Settings › Downloads")
        }
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
            FeedAutoplay.release()
        }
        super.onDestroy()
    }

    // ----- YouTube account -----

    internal fun onAccountClick() {
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

    internal fun askSignOut() {
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Sign out of YouTube?")
            .setMessage("Your subscriptions, likes and history won't show in RAINAX until you sign in again. Your downloads stay.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ -> signOut() }
            .show()
    }

    internal fun signOut() {
        YtAccount.signOut(this)
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        home.onAccountChanged()
        subs.onAccountChanged()
        if (tab == 4) subs.onShown()
        refreshYou()
        message("Signed out of YouTube")
    }

    /** Settings > Clear watch history: RAINAX's own history, and (signed in, if ticked) YouTube's too. */
    internal fun askClearWatchHistory() {
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
    internal fun refreshAccount() {
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
    internal fun openOrWatch(t: DownloadTask) {
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
    internal fun openItem(item: VideoItem) {
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

    internal var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    internal var controller: androidx.media3.session.MediaController? = null

    internal fun connectPlayer() {
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

    internal fun disconnectPlayer() {
        video.attach(null)
        music.attach(null)
        music.videoView.player = null
        b.miniPlayer.miniVideo.player = null
        controllerFuture?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** Leaving the app: keep only the sound (Background play on) or pause. */
    internal fun leaveApp() {
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
    internal fun enableVideo(c: androidx.media3.session.MediaController) {
        if (c.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, false).build()
        }
    }

    internal fun backInApp() {
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

    internal fun showTab(index: Int) {
        if (index != 1 && selecting) exitSelection()
        val old = tab
        tab = index
        val pages = listOf(hm.root, pl.root, st, so.root, sb.root)
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
        if (index == 2) renderFolder()          // Settings: every value read fresh
    }

    /** Where each page's tab sits in the bottom bar (Settings counts as after You), for the slide direction. */
    internal fun tabOrder(index: Int) = when (index) { 0 -> 0; 3 -> 2; 4 -> 3; 1 -> 4; else -> 5 }

    /** The tab the chrome was last laid out for (the video page only animates when it opens on the same tab). */
    internal var chromeTab = 0

    /** Video page over Home, bottom bar hidden in fullscreen, Back handling. */
    internal fun updateChrome() {
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
        pillHidden = full
        renderUpdatePill(latestTasks)
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

    internal var miniPlaced = false
    internal var miniLeftSide = false
    internal var miniDragging = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    internal fun setupMiniPlayer() {
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
    internal fun miniBounds(): List<Float> {
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

    internal fun placeMiniPlayer() {
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

    internal fun updateMiniPlayer() {
        b.miniPlayer.miniPlay.setImageResource(if (controller?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    /** The picture follows the player that is on screen: the floating window or the video page. */
    internal fun syncVideoSurface() {
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

    internal fun updateBack() {
        backCallback.isEnabled = true          // Back is decided in handleOnBackPressed (video, search, tabs)
    }

    // =====================================================================
    // Links in / download sheet
    // =====================================================================

    // =====================================================================
    // You (library header), side menu, voice search
    // =====================================================================

    /** Home tab with nothing on top (for lists opened from other places). */
    internal fun goHome() {
        if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
        music.collapse()
    }

    /** A list on Home with its own title bar (topics, liked, history...). */
    internal fun openHomeList(title: String, id: String) {
        goHome()
        video.minimize()
        home.openList(title, id)
    }

    internal fun setupYou() {
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
    internal fun refreshYou() {
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

    internal var navAvatarUrl: String? = null

    /** The bottom bar's You icon: your profile picture when signed in (like YouTube), the person icon otherwise. */
    internal fun updateNavAvatar() {
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

    internal var exploreOpen = false

    /** One place in the side menu. */
    internal class MenuRow(val label: String, val row: android.widget.LinearLayout, val iconBox: android.widget.FrameLayout,
                          val icon: ImageView, val text: TextView, val badge: TextView) {
        /** social sites: their coloured round badge stays as the icon's background */
        var socialBadge: android.graphics.drawable.Drawable? = null
    }

    internal val menuRows = mutableListOf<MenuRow>()
    internal val menuLabels = mutableListOf<TextView>()
    private var menuActive = "Home"     // (private: an internal var would clash with setMenuActive())
    internal var menuText = 0
    internal var menuIcon = 0

    /**
     * The side menu (≡): a floating card in the app's colours (light, dark, AMOLED): header with bell and ✕, your
     * account, search, then MENU / DISCOVER / GENERAL with a red bar on the open place and count badges.
     * Closes with ✕, Back, a tap outside, or a swipe to the left.
     */
    internal fun setupExplore() {
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
            setSpan(android.text.style.ForegroundColorSpan(Ui.accent(this@MainActivity)), start, length, 0)
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
                textSize = 10f
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
    internal fun setMenuActive(label: String) {
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
    internal fun refreshMenu() {
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

    internal fun openExplore() {
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

    internal fun closeExplore() {
        val e = b.explore
        if (!exploreOpen) return
        exploreOpen = false
        e.exploreScrim.animate().alpha(0f).setDuration(180).start()
        e.explorePanel.animate().translationX(-(e.explorePanel.width + 40 * resources.displayMetrics.density)).alpha(0.6f).setDuration(200)
            .withEndAction { if (!exploreOpen) e.root.isVisible = false }.start()
        blurBehindMenu(false)
    }

    /** The app behind the menu goes soft (frosted glass), on Android 12 and newer. */
    internal fun blurBehindMenu(on: Boolean) {
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
    internal val welcomeGuide =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppPrefs.setGuideSeen(this)
            if (!AppPrefs.firstRunAsked(this)) b.root.post { askStorage() }
        }

    internal val voiceSearch =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) home.search(text)
        }

    internal val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) VoicePage(this) { home.search(it) }.show() else startSystemVoiceSearch()
        }

    /**
     * Mic in the search box: RAINAX's own listening screen (glowing orb, your words as you speak). Without the
     * microphone permission or a speech recognizer, Android's voice search window is used instead.
     */
    internal fun startVoiceSearch() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { startSystemVoiceSearch(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoicePage(this) { home.search(it) }.show()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    internal fun startSystemVoiceSearch() {
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
    // Social, You (downloads) and Settings live in MainSocial.kt, MainLibrary.kt and MainSettings.kt;
    // their state stays here.
    // =====================================================================

    internal var clipStripDone: String? = null

    internal var lastClipStamp = -1L

    internal var clipDialog: AlertDialog? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets apps read what was copied only while they are on screen with focus
        if (hasFocus && !blocked) b.root.postDelayed({ checkClipboard() }, 400)
        if (hasFocus && tab == 3) b.root.postDelayed({ refreshClipStrip() }, 250)
    }

    /** Downloads that finished while the app is open (the ones already done at start don't count). */
    internal var doneSeen: HashSet<String>? = null

    /** True in full screen (video, music): the update pill waits. */
    internal var pillHidden = false

    internal var pillPadded = false

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Messages sit above the ad banner (never over it) or above the bottom bar. */
    internal fun snackAnchor(): View = b.navCard

    internal fun message(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(snackAnchor()).show()
    }

    // ----- first run: storage access, then permission to install updates -----

    internal var permDialog: AlertDialog? = null

    internal val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Android 11+: then All files access (WhatsApp statuses), then the updates question
            if (needsAllFiles()) openAllFilesSettings(thenInstallAsk = true) else askInstallUpdates()
        }
    /** Android 10: storage asked from the status saver. */
    internal val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (tab == 3 && ::statusSaver.isInitialized) statusSaver.refresh() }
    /** Came back from the All files access screen: ask about updates next (first start only). */
    internal var installAskAfterSettings = false

    internal fun needsAllFiles() = Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

    internal fun openAllFilesSettings(thenInstallAsk: Boolean) {
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
    internal fun requestFileAccess(thenInstallAsk: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) openAllFilesSettings(thenInstallAsk)
        else legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)     // (includes reading)
    }

    /** Pop-up 1: storage (with notifications on Android 13+, for download progress). */
    internal fun askStorage() {
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
    internal fun askInstallUpdates() {
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

    /** Asks for notifications (Android 13+) when they are off. True when it asked. */
    internal fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return true
        }
        return false
    }

    /** RAINAX may already run in the background (battery optimisation off). */
    internal fun canRunInBackground(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /**
     * "Keep RAINAX running": a short popup (with Don't show again), then Android's own
     * "Let app always run in background? Deny / Allow". Shown at most every 3 days until it is allowed.
     */
    internal fun maybeAskBackgroundRun() {
        if (isFinishing || isDestroyed || blocked) return
        if (canRunInBackground() || AppPrefs.bgAskNever(this)) return
        val now = System.currentTimeMillis()
        if (now - AppPrefs.bgAskedAt(this) < 3L * 24 * 3600 * 1000) return
        if (permDialog?.isShowing == true) return
        AppPrefs.setBgAskedAt(this, now)
        val d = resources.displayMetrics.density
        val never = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Don't show again"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.rx_text))
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), 0)
            addView(never)
        }
        permDialog = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setIcon(R.drawable.ic_set_battery)
            .setTitle("Keep RAINAX running")
            .setMessage("Downloads, background play and WhatsApp status auto-save need RAINAX to keep running " +
                "when the screen is off. Tap Continue, then Allow on the next screen.")
            .setView(wrap)
            .setPositiveButton("Continue") { _, _ ->
                if (never.isChecked) AppPrefs.setBgAskNever(this, true)
                requestBackgroundRun()
            }
            .setNegativeButton("Not now") { _, _ -> if (never.isChecked) AppPrefs.setBgAskNever(this, true) }
            .show()
    }

    /** Android's own "Let app always run in background?" (falls back to the battery settings list). */
    internal fun requestBackgroundRun() {
        bgAskPending = true
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                bgAskPending = false
                message("Open Settings > Apps > RAINAX Tube > Battery and choose Unrestricted")
            }
        }
    }

    /** Coming back from Android's question: say how it went. */
    internal var bgAskPending = false

    /** One-time prompt so the system doesn't kill background downloads. Returns true if shown. */
    /**
     * Settings > "Downloads stop with the screen off?": only for phones (some Xiaomi, Oppo, Vivo, Samsung)
     * whose battery saver closes apps even with a download notification. Never asked by itself.
     */
    internal fun openBatterySettings() {
        if (canRunInBackground()) {
            message("Already allowed: downloads keep going with the screen off")
            return
        }
        requestBackgroundRun()
    }

    companion object {
        internal const val MODE_QUICK = 0
        internal const val MODE_ALL = 1
        internal const val MODE_SUBS = 2
        /** Most links taken from one shared or pasted text. */
        internal const val MAX_SHARED_LINKS = 1000
    }
}
