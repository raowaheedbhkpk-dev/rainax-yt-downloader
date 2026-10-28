package com.rainax.ytdownloader

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * Home: search bar, tabs (Music, Gaming, Movies, Podcasts, Live) and a video list where every video has its own
 * Download button. Searching shows suggestions while typing, then results (Videos / Playlists).
 */
class HomeScreen(
    private val act: AppCompatActivity,
    /** Home's page: top bar, chips, the lists and the overlay, all Jetpack Compose in this one view. */
    private val hm: androidx.compose.ui.platform.ComposeView,
    private val vm: MainViewModel,
    private val openVideo: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit,
    private val onAccount: () -> Unit
) {
    /** Home chips: personal lists when signed in to YouTube. */
    private var tabs: List<Pair<String, String>> = YtCatalog.TABS
    /** List keys where For you is showing popular videos (more pages come from the popular list). */
    private val popularFallback: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** The video lists and the Music tab (Compose, FeedCompose.kt / MusicHome.kt). */
    private val feed = FeedHost(hm)
    /** The top bars, chips, suggestions, error and picks bar (Compose, HomeTop.kt). */
    private val chrome = HomeChrome()
    /** Autoplay in feed for Home's lists. */
    val feedPreview get() = feed.preview

    private fun computeTabs(): List<Pair<String, String>> =
        if (YtAccount.isSignedIn(act)) listOf(
            "For you" to FOR_YOU,
            "Subscriptions" to "acc:FEsubscriptions",
            "Music" to YtCatalog.MUSIC,
            "History" to "acc:FEhistory",
            "Liked" to "acc:VLLL",
            "Channels" to "acc:FEchannels",
            "Watch later" to "acc:VLWL"
        ) + YtCatalog.TOPICS else YtCatalog.TABS

    private var tabIndex = 0
    private var query: String? = null          // showing search results for this
    private var searchKind = 0                 // search chip: 0 videos, 1 channels, 2 playlists
    private var next: Page? = null
    private var loadJob: Job? = null
    private var suggestJob: Job? = null
    private var buildingTabs = false

    private var playlistUrl: String? = null    // showing this playlist's videos
    private var playlistItem: VideoItem? = null
    private var channelUrl: String? = null     // showing this channel
    private var channel: ChannelDetails? = null
    private var channelSubscribed: Boolean? = null
    /** The channel tab shown ("Videos", "Shorts"…); null until the channel's first page came. */
    private var channelTab: String? = null
    /** A channel's banner, name, Subscribe and tabs (Compose, ChannelHeaderUi.kt): the top of its list. */
    private val chUi = ChannelHeaderUi()
    private val channelHeaderView = androidx.compose.ui.platform.ComposeView(act).apply {
        setContent { RxTheme { RxModern { ChannelHeader(chUi, { toggleChannelSubscribe() }, { label -> pickChannelTab(label) }) } } }
    }
    private val channelAdapter = FeedModel(false, { open(it) }, { download(it) }).also { it.header = channelHeaderView }

    private val bigAdapter = FeedModel(true, { open(it) }, { download(it) }).also {
        it.hero = true
        it.hideShorts = true          // Home's tabs and magazine: videos only; Shorts live in the Shorts feed
    }
    private val smallAdapter = FeedModel(false, { open(it) }, { download(it) })
    /** Continue watching + Shorts, on top of the first Home tab. */
    private val shelf = HomeShelf(act, openVideo)
    private val playlistAdapter = FeedModel(false, { open(it) }, { download(it) })
    /** The Music tab (YouTube Music style). What its buttons do is set by the main screen (the Music player). */
    var musicPlay: (List<VideoItem>, Int, String, Boolean) -> Unit = { _, _, _, _ -> }
    var musicSongMenu: (VideoItem) -> Unit = {}
    var musicPlayPlaylist: (VideoItem) -> Unit = {}
    private val musicHome = MusicHome(
        play = { songs, index, from, radio -> musicPlay(songs, index, from, radio) },
        songMenu = { musicSongMenu(it) },
        openPlaylist = { openPlaylist(it) },
        playPlaylist = { musicPlayPlaylist(it) },
        downloadPlaylist = { download(it) },
        onMood = {
            loadMusic(pulled = false)
        }
    )
    private var musicSeenVersion = -1
    /** Soft glow at the top of the Music tab, in the colour of the song playing (like YouTube Music). */
    private var musicGlow = androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.accent(act), 0x30)
    /**
     * A list opened from the side menu, the bell or the You tab (title bar with Back):
     * "acc:<YouTube list>" (needs the account), "q:<topic search>" or "local:history".
     */
    private var special: String? = null
    private val specialAdapter = FeedModel(true, { open(it) }, { download(it) })

    init {
        // ⋮ on every video, like YouTube. Home's own lists also get Not interested / Don't recommend channel.
        val feed = listOf(bigAdapter, smallAdapter)
        feed.forEach { ad ->
            ad.filterHidden = true
            ad.onMore = { item, _ ->
                VideoMenu.show(act, item, { download(it) }, { onAccount() }) { hidden, channel ->
                    // (saved lists are filtered again when shown, so nothing has to reload)
                    feed.forEach { a -> a.removeWhere { v -> if (channel) v.uploader.equals(hidden.uploader, true) else v.url == hidden.url } }
                }
            }
        }
        listOf(channelAdapter, playlistAdapter, specialAdapter).forEach { ad ->
            ad.onMore = { item, _ -> VideoMenu.show(act, item, { download(it) }, { onAccount() }) }
        }
        setupMagazine()
    }

    // ---------- magazine Home (first tab): chapters with their own rows ----------

    private fun setupMagazine() {
        bigAdapter.chapters = MAG_CHAPTERS
        bigAdapter.moviesId = MAG_MOVIES
        bigAdapter.weekStats = {
            val w = WatchHistory.week(act)
            val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val dl = TaskRepository.tasks.value.count { it.status == Status.DONE && it.createdAt >= since && !it.format.startsWith(AppUpdater.TASK_PREFIX) }
            WeekStats(w.watchedMs, w.videos, dl, w.topChannel)
        }
        bigAdapter.onNeedChapter = { id -> loadChapter(id) }
        bigAdapter.onChapterMore = { id -> openTab(id) }
    }

    /** A chapter's posters: from the saved tab list when there is one, otherwise its first page. */
    private fun loadChapter(id: String) {
        fun pick(list: List<VideoItem>): List<VideoItem> {
            val videos = list.filter { !it.isChannel && !it.isPlaylist && !it.isShort && !it.url.contains("/shorts/") }
            if (id != MAG_MOVIES) return videos.take(12)
            // the cinema: real films only (an hour or longer) and not ones almost nobody watched
            val films = videos.filter { it.seconds >= 3600 && (it.views < 0 || it.views >= 10_000) }
            return (if (films.size >= 4) films else videos.filter { it.seconds >= 2400 || it.seconds == 0L }).take(20)
        }
        val cached = vm.feedCache["tab:$id"]
        if (cached != null) {
            hm.post { bigAdapter.setChapter(id, pick(cached.first)) }      // never while the list lays out
            return
        }
        act.lifecycleScope.launch {
            val res = try {
                withContext(Dispatchers.IO) { YtCatalog.kiosk(id, null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (res != null && res.items.isNotEmpty()) vm.feedCache["tab:$id"] = res.items to res.next
            hm.post { bigAdapter.setChapter(id, pick(res?.items.orEmpty())) }
        }
    }

    /** MORE on a chapter: that chip opens (Music, Gaming...). */
    private fun openTab(id: String) {
        if (query != null) return
        val i = tabs.indexOfFirst { it.second == id }
        if (i < 0) return
        onChip(i)
    }

    private val adapter get() = when {
        special != null -> specialAdapter
        channelUrl != null -> channelAdapter
        playlistUrl != null -> playlistAdapter
        query != null -> smallAdapter
        else -> bigAdapter
    }

    /** Music tab (rows of playlists) is showing. */
    private val isMusic get() = query == null && playlistUrl == null && channelUrl == null && special == null &&
        tabs[tabIndex].second == YtCatalog.MUSIC

    private fun applyListAdapter() {
        val shelfHere = tabIndex == 0 && query == null && playlistUrl == null && channelUrl == null && special == null
        val wantHeader = if (shelfHere) shelf.root else null
        if (bigAdapter.header !== wantHeader) bigAdapter.header = wantHeader
        bigAdapter.magazine = shelfHere            // the first tab reads like a magazine
        if (shelfHere) shelf.refresh()
        if (shelfHere) bigAdapter.headerEarly = shelf.hasContinue
        // the Music tab's rows or a video list, with the Music tab's soft glow behind it
        if (isMusic) feed.showMusic(musicHome) else feed.show(adapter)
        feed.glow = if (isMusic) musicGlow else null
        FeedAutoplay.refresh()
    }
    private fun fillSearch(text: String) {
        // ↖ : put the suggestion in the box to keep typing
        chrome.search = text
        suggest(text)
    }

    private fun removeRecent(q: String) {
        AppPrefs.removeSearch(act, q)
        Haptics.tick(hm)
        suggest(chrome.search)
        Notify.show(act, "Removed from recent searches")
    }

    /** "Clear search history" under the recent searches: asks once, then removes them all. */
    private fun confirmClearHistory() {
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Clear search history?")
            .setMessage("Your recent searches will be removed from this phone.")
            .setPositiveButton("Clear") { _, _ ->
                AppPrefs.clearSearchHistory(act)
                suggest(chrome.search)
                Notify.show(act, "Search history cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The ≡ button (side menu) and the mic (voice search): handled by the main screen. */
    var onMenu: () -> Unit = {}
    /** The sun / moon button (the theme popup, set by the main screen). */
    var onTheme: (View) -> Unit = {}
    var onVoice: () -> Unit = {}

    /** All on the channels row: the Subscriptions tab (set by the main screen). */
    var onAllSubs: () -> Unit = {}

    fun setup() {
        shelf.openChannel = { openChannel(it.url, it.title, it.thumb) }
        shelf.openAllSubs = { onAllSubs() }
        tabs = computeTabs()
        updateAccountIcon()
        hm.isFocusableInTouchMode = true
        applyColumns()
        feed.onNearEnd = { load(reset = false) }
        FeedAutoplay.attach(feed.preview)
        chrome.dark = Ui.isDark(act)
        val actions = HomeChromeActions(
            menu = { onMenu() },
            theme = { onTheme(it) },
            openSearch = { startTyping() },
            voice = { onVoice() },
            back = { back() },
            titleDownload = { playlistItem?.let { download(it) } },
            chip = { onChip(it) },
            typed = { if (chrome.suggestOpen) suggest(it) },
            focused = { if (!chrome.suggestOpen) startTyping() },
            submit = { submit(it) },
            clearText = { chrome.search = ""; startTyping() },
            fill = { fillSearch(it) },
            clearHistory = { confirmClearHistory() },
            removeRecent = { removeRecent(it) },
            retry = { load(reset = true) },
            netSettings = { Net.openSettings(act) },
            picksView = { PicksSheet.show(act) { downloadMany(it) } },
            picksClear = { Picks.clear() },
            picksDownload = {
                val list = Picks.all()
                if (list.isNotEmpty()) {
                    Picks.clear()
                    downloadMany(list)
                }
            }
        )
        hm.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        hm.setContent { RxTheme { HomePage(chrome, actions, feed) { pulled() } } }

        // + on every video: pick several, then download them together
        listOf(bigAdapter, smallAdapter, playlistAdapter, channelAdapter, specialAdapter).forEach { a ->
            a.onPick = { Picks.toggle(it); if (Picks.has(it.url) && Picks.size <= Picks.SAMPLE) vm.warm(it.url) }
            a.isPicked = { Picks.has(it) }
        }
        Picks.listen { refreshPicks() }

        buildTabs()
        applyListAdapter()
        load(reset = true)
    }

    /** Pull down: a fresh list (not the saved one); a film countdown shows while it loads. */
    private fun pulled() {
        run {
            feed.refreshing = true
            if (isMusic) vm.musicCache.remove(musicHome.mood.orEmpty()) else vm.feedCache.remove(cacheKey())
            if (!isMusic && bigAdapter.magazine) {                    // the magazine's chapters come fresh too
                (MAG_CHAPTERS.map { it.second } + MAG_MOVIES).forEach { vm.feedCache.remove("tab:$it") }
                bigAdapter.clearChapters()
            }
            if (!isMusic && bigAdapter.header === shelf.root) shelf.refreshShorts()      // fresh Shorts strip too
            load(reset = true, pulled = true)
        }
    }

    /** A chip was chosen: that tab (Home) or kind of result (search). */
    private fun onChip(index: Int) {
        if (buildingTabs) return
        chrome.chipSel = index
        if (query != null) searchKind = index else tabIndex = index
        applyListAdapter()
        load(reset = true)
    }

    /** Back: stop typing, or leave the search results. Returns false when there is nothing to undo. */
    fun back(): Boolean {
        if (playlistUrl != null || channelUrl != null || special != null) {
            special = null
            playlistUrl = null
            playlistItem = null
            channelUrl = null
            channel = null
            chrome.bar = if (query != null) 1 else 0
            chrome.chipsShown = true
            if (query == null) buildTabs()
            applyListAdapter()
            load(reset = true)                     // the list before is still saved: shows at once
            return true
        }
        if (chrome.suggestOpen) {
            stopTyping()
            if (query == null) showSearchBox(false) else chrome.search = query.orEmpty()
            return true
        }
        if (query != null) {
            query = null
            searchKind = 0
            showSearchBox(false)
            buildTabs()
            applyListAdapter()
            load(reset = true)
            return true
        }
        // on another Home tab (Music, Gaming...): Back returns to the first tab before the app closes
        if (tabIndex != 0) {
            onChip(0)
            feed.scrollToTop()
            return true
        }
        return false
    }

    // ---------- picking several videos ----------

    /** Called with the picked videos when Download is tapped on the bar. */
    var downloadMany: (List<VideoItem>) -> Unit = {}

    private fun refreshPicks() {
        val n = Picks.size
        chrome.picks = n
        val d = act.resources.displayMetrics.density
        feed.bottomPad = (if (n > 0) 88 else 16) + (Ui.pillExtra / d).toInt()
        feed.redraw()
    }

    /** Back with videos picked: clear the picks first. */
    fun clearPicks(): Boolean {
        if (Picks.size == 0) return false
        Picks.clear()
        return true
    }

    /**
     * Phones: one video per row. Tablets and wide screens: 2 or 3 columns, so pictures and buttons keep a
     * normal size instead of stretching across the whole screen. Called again when the screen turns.
     */
    fun applyColumns() {
        // the Compose feed lays out its own columns (FeedScreen)
    }

    /** Home is on screen again: newest Continue watching row and watched bars. */
    fun onShown() {
        if (bigAdapter.header != null) shelf.refresh()
        if (isMusic) refreshListenAgain()
        if (!isMusic) feed.redraw()
    }

    /** Open the search box (the You page's search button). */
    fun openSearch() = startTyping()

    /** Watch history was cleared: Continue watching and progress bars go. */
    fun onHistoryCleared() {
        vm.feedCache.remove("sp:local:history")
        onShown()
    }

    /** Search for [text] (used by the search box and for links that are not videos). */
    fun search(text: String) = submit(text)

    // ---------- search box ----------

    /** Brand bar <-> search box. */
    private fun showSearchBox(on: Boolean) {
        chrome.bar = if (on) 1 else if (playlistUrl != null || channelUrl != null || special != null) 2 else 0
        if (!on) chrome.search = ""
    }

    private fun startTyping() {
        showSearchBox(true)
        chrome.suggestOpen = true
        suggest(chrome.search)
        chrome.focusReq++                       // the cursor in the box, and the keyboard
    }

    private fun stopTyping() {
        chrome.suggestOpen = false
        suggestJob?.cancel()
        chrome.unfocusReq++                     // take focus away from the box (it would reopen the suggestions)
    }

    private fun suggest(text: String) {
        suggestJob?.cancel()
        val history = AppPrefs.searchHistory(act)
        if (text.isBlank()) {
            // like YouTube: your recent searches, then "You may like" (channels you watch, popular videos) with pictures
            val recent = history.take(10).map { Sug(it, icon = R.drawable.ic_history) }
            val local = mayLike(history)
            chrome.setSuggestions(emptyList(), local, showClear = history.isNotEmpty(), stubs = recent)
            // then, like YouTube: searches related to what you watch and search, each with a matching picture
            suggestJob = act.lifecycleScope.launch {
                val personal = try {
                    withContext(Dispatchers.IO) { relatedSearches(history, local) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (personal.isEmpty() || chrome.search.isNotBlank()) return@launch
                val channels = local.filter { it.sub != null }
                val trending = local.filter { it.sub == null && personal.none { p -> p.text == it.text } }
                chrome.setSuggestions(emptyList(), (channels + personal + trending).take(8), showClear = history.isNotEmpty(), stubs = recent)
            }
            return
        }
        val fromHistory = history.filter { it.contains(text, true) }.take(3).map { Sug(it, icon = R.drawable.ic_history) }
        chrome.setSuggestions(fromHistory)
        suggestJob = act.lifecycleScope.launch {
            delay(220)                           // wait for a pause in typing
            val list = withContext(Dispatchers.IO) { YtCatalog.suggestions(text) }
            chrome.setSuggestions(fromHistory + list.filter { s -> fromHistory.none { it.text.equals(s, true) } }.map { Sug(it) })
        }
    }

    /**
     * "You may like" under an empty search box, shown at once: two channels you watched lately ("Channel that you
     * watch", picked at random from your recent ones) and short searches made from videos in your feeds, in a new
     * order every time (so the list never looks frozen).
     */
    private fun mayLike(history: List<String>): List<Sug> {
        val out = mutableListOf<Sug>()
        val used = HashSet<String>()
        history.forEach { used += it.lowercase() }
        WatchHistory.recentItems(act).take(25)
            .filter { it.uploader.isNotBlank() }
            .distinctBy { it.uploader.lowercase() }
            .shuffled()
            .filter { used.add(it.uploader.lowercase()) }
            .take(2)
            .forEach { out += Sug(it.uploader.lowercase(), "Channel that you watch", it.thumb, R.drawable.ic_search) }
        val feed = (bigAdapter.all() + vm.feedCache.values.flatMap { it.first })
            .filter { !it.isChannel && !it.isPlaylist && !it.isShort && it.title.isNotBlank() }
            .distinctBy { it.url }
            .shuffled()
        for (v in feed) {
            if (out.size >= 8) break
            val q = shortQuery(v.title) ?: continue
            if (!used.add(q)) continue
            out += Sug(q, null, v.thumb, R.drawable.ic_trending)
        }
        return out
    }

    /** Pictures for searches made before (a search costs a moment, so each phrase is looked up once). */
    private val suggestThumbs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Blocking. Searches related to you: YouTube's own suggestions for a few things you watched or searched lately
     * (picked at random each time), each with the picture of its first video.
     */
    private suspend fun relatedSearches(history: List<String>, shown: List<Sug>): List<Sug> = coroutineScope {
        val seeds = (history.take(8) + WatchHistory.recentItems(act).take(12).mapNotNull { shortQuery(it.title) })
            .map { it.split(' ').take(3).joinToString(" ") }
            .filter { it.length >= 3 }
            .distinct()
            .shuffled()
            .take(3)
        if (seeds.isEmpty()) return@coroutineScope emptyList()
        val skip = HashSet<String>()
        history.forEach { skip += it.lowercase() }
        shown.forEach { skip += it.text.lowercase() }
        val phrases = seeds.map { seed -> async { YtCatalog.suggestions(seed).drop(1).shuffled().take(2) } }
            .flatMap { it.await() }
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() && skip.add(it) }
            .take(4)
        phrases.map { q ->
            async {
                val thumb = suggestThumbs[q] ?: runCatching {
                    YtCatalog.search(q, 0, null).items.firstOrNull { !it.isChannel && !it.isPlaylist }?.thumb
                }.getOrNull()?.also { suggestThumbs[q] = it }
                Sug(q, null, thumb, R.drawable.ic_trending)
            }
        }.map { it.await() }
    }

    /** "Tere Darwaze Peh Chilman | Nusrat Fateh Ali Khan | OSA" -> "tere darwaze peh chilman". */
    private fun shortQuery(title: String): String? {
        val first = title.split('|', '(', '[', '#', '•', '｜').first()
            .replace(Regex("\\s+[-–—:]\\s+.*$"), "")
            .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
            .replace(Regex("\\s+"), " ").trim().lowercase()
        if (first.length < 4) return null
        val words = first.split(' ')
        return words.take(6).joinToString(" ")
    }

    private fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        // a pasted link (YouTube, TikTok, Facebook, Instagram...): straight to the download sheet
        val link = LinkText.find(q).firstOrNull()
        if (link != null) {
            stopTyping()
            chrome.search = ""
            if (query == null) showSearchBox(false)
            download(VideoItem(link, "", "", null, 0, -1, null))
            return
        }
        AppPrefs.addSearch(act, q)
        rememberScroll()
        stopTyping()
        showSearchBox(true)
        chrome.search = q
        val wasSearching = query != null
        query = q
        if (!wasSearching) searchKind = 0
        buildTabs()
        applyListAdapter()
        load(reset = true)
    }

    /**
     * A list with its own title bar: a topic from the side menu ("q:music"), a YouTube list of the account
     * ("acc:VLLL" liked, "acc:VLWL" Watch later, "acc:FEhistory"), or "local:history" (watched in RAINAX).
     */
    fun openList(title: String, id: String) {
        if (chrome.suggestOpen) stopTyping()
        if (id.startsWith("acc:") && !YtAccount.isSignedIn(act)) {
            onAccount()                                  // sign in first
            return
        }
        rememberScroll()
        playlistUrl = null
        playlistItem = null
        channelUrl = null
        channel = null
        special = id
        chrome.title = title
        chrome.titleDownload = false
        chrome.bar = 2
        chrome.chipsShown = false
        if (id == "local:history" || id.startsWith("acc:")) vm.feedCache.remove("sp:$id")    // always fresh
        applyListAdapter()
        load(reset = true)
    }

    /** The Music chip (YouTube Music style tab), from the side menu. */
    fun showMusic() {
        var guard = 0
        while (back() && guard++ < 6) { /* leave search results, lists and typing */ }
        val i = tabs.indexOfFirst { it.second == YtCatalog.MUSIC }
        if (i < 0) return
        onChip(i)
    }

    /** The bell: YouTube's own notifications, on their own page laid out like the YouTube app. */
    fun openNotifications() {
        if (!YtAccount.isSignedIn(act)) {
            Notify.show(act, "Sign in to YouTube to see your notifications", long = true)
            onAccount()
            return
        }
        if (chrome.suggestOpen) stopTyping()
        NotificationsPage(act, { open(it) }, { download(it) }).show()
    }

    /** A playlist: its videos (each with Download) and "Download all" at the top. */
    fun openPlaylist(item: VideoItem) {
        if (chrome.suggestOpen) stopTyping()
        rememberScroll()
        special = null
        channelUrl = null
        channel = null
        playlistUrl = item.url
        playlistItem = item
        chrome.title = item.title
        chrome.titleDownload = true
        chrome.bar = 2
        chrome.chipsShown = false
        applyListAdapter()
        load(reset = true)
    }

    /** A channel: picture, name, Subscribe, and its videos (each with Download). */
    fun openChannel(url: String, name: String? = null, avatar: String? = null) {
        if (chrome.suggestOpen) stopTyping()
        rememberScroll()
        special = null
        playlistUrl = null
        playlistItem = null
        channelUrl = url
        channel = null
        channelSubscribed = null
        channelTab = null
        chUi.tabs = emptyList()
        chUi.tab = null
        chUi.subscribeShown = true
        chrome.title = name.orEmpty()
        chrome.titleDownload = false
        chrome.bar = 2
        chrome.chipsShown = false
        chUi.name = name.orEmpty()
        chUi.meta = ""
        chUi.desc = ""
        chUi.avatar = avatar
        chUi.banner = null
        chUi.subscribed = false
        vm.feedCache.keys.removeAll { it.startsWith("ch:$url:") }      // the header comes with the first page
        applyListAdapter()
        load(reset = true)
    }

    private fun fillChannel(d: ChannelDetails) {
        channel = d
        chrome.title = d.name
        chUi.name = d.name
        chUi.meta = if (d.subscribers >= 0) YtCatalog.count(d.subscribers) + " subscribers" else ""
        chUi.desc = d.description
        // (keep the picture already shown when YouTube's answer has none)
        if (d.avatar != null) chUi.avatar = d.avatar
        chUi.banner = d.banner
        // your own channel: no Subscribe button (like YouTube)
        val me = if (YtAccount.isSignedIn(act)) YtAccount.profile(act) else null
        val handle = me?.handle?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        val own = me != null && ((handle != null && d.url.contains("@$handle", true)) || me.name.equals(d.name, true))
        chUi.subscribeShown = !own
        // Videos / Shorts / Live / Playlists
        channelTab = d.tab
        chUi.tabs = d.tabs
        chUi.tab = d.tab
        if (own) return
        val id = d.id
        if (id != null && YtAccount.isSignedIn(act)) {
            act.lifecycleScope.launch {
                val sub = withContext(Dispatchers.IO) { runCatching { YtAccount.channelSubscribed(id) }.getOrNull() }
                if (channel?.id != id) return@launch
                channelSubscribed = sub
                chUi.subscribed = sub == true
            }
        }
    }

    /** Videos / Shorts / Live / Playlists on a channel's page. */
    private fun pickChannelTab(label: String) {
        val d = channel ?: return
        if (label == channelTab || channelUrl != d.url) return
        channelTab = label
        chUi.tab = label
        load(reset = true)
    }

    private fun toggleChannelSubscribe() {
        if (!YtAccount.isSignedIn(act)) {
            onAccount()                                 // sign in first
            return
        }
        val id = channel?.id ?: return
        val now = channelSubscribed == true
        channelSubscribed = !now
        chUi.subscribed = !now
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.subscribe(id, !now) }.isSuccess }
            if (!ok) {
                channelSubscribed = now
                chUi.subscribed = now
                Notify.show(act, "Couldn't change the subscription. Try again")
            }
            vm.feedCache.keys.removeAll { it.startsWith("tab:acc:") }
        }
    }

    private fun imm() = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ---------- tabs and list ----------

    /** Filter chips: YouTube / Music on Home, Videos / Playlists for search results. */
    private fun buildTabs() {
        buildingTabs = true
        chrome.chips = if (query != null) YtCatalog.SEARCH_KINDS.map { it.first } else tabs.map { it.first }
        chrome.chipSel = if (query != null) searchKind else tabIndex
        buildingTabs = false
    }

    /** Where each list was scrolled to, so Back returns to the same place. */
    private val scrollMemory = HashMap<String, Pair<Int, Int>?>()

    private fun rememberScroll() {
        if (isMusic) return
        scrollMemory[cacheKey()] = feed.saveScroll()
    }

    private fun cacheKey(): String = special?.let { "sp:$it" } ?: channelUrl?.let { "ch:$it:${channelTab.orEmpty()}" } ?: playlistUrl?.let { "pl:$it" } ?: query?.let { "q:$it:$searchKind" }
        ?: "tab:${tabs[tabIndex].second}"

    private fun open(item: VideoItem) = when {
        item.isChannel -> openChannel(item.url, item.title, item.thumb)
        item.isPlaylist -> openPlaylist(item)
        else -> openVideo(item)
    }

    // ---------- Music tab ----------

    /** The playing song's cover colour: the Music tab's glow takes it (bright and soft, for light and dark). */
    fun musicTone(c: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        val glow = if (hsv[1] < 0.1f) Ui.accent(act)
        else android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtMost(0.8f), 0.9f))
        musicGlow = androidx.core.graphics.ColorUtils.setAlphaComponent(glow, 0x38)
        if (isMusic) feed.glow = musicGlow
    }

    /** The song playing in the Music player (its cover in the rows shows the equalizer). */
    fun musicNowPlaying(url: String?) = musicHome.setNowPlaying(url)

    /** A row to load: its title, kind (songs, covers, playlists) and how to get it (blocking). */
    private class MusicRow(val title: String, val kind: Int, val load: () -> List<VideoItem>)

    /** The rows of the Music home, or of a mood chip. */
    private fun musicPlan(mood: String?, recent: List<VideoItem>): List<MusicRow> {
        if (mood != null) {
            val rows = YtCatalog.MUSIC_MOODS.firstOrNull { it.first == mood }?.second.orEmpty()
            return rows.map { (title, q) -> MusicRow(title, MusicSection.SONGS) { YtCatalog.musicSongs(q) } } +
                MusicRow("$mood playlists", MusicSection.PLAYLISTS) { YtCatalog.musicSection("$mood songs playlist") }
        }
        val seed = recent.firstOrNull()
        val top = YtCatalog.topSongsQuery()
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val first = listOf(
            // songs like the last one you played (YouTube Music's Quick picks), else the country's top songs
            MusicRow(MusicHome.QUICK_PICKS, MusicSection.SONGS) {
                val radio = if (seed != null) runCatching { YtCatalog.musicRadio(seed.url) }.getOrDefault(emptyList()) else emptyList()
                radio.ifEmpty { YtCatalog.musicSongs(top) }.take(20)
            },
            MusicRow(if (seed != null) "Trending songs" else "New releases", MusicSection.SONGS) {
                YtCatalog.musicSongs(if (seed != null) top else "new songs $year")
            }
        )
        return first + YtCatalog.MUSIC_SHELVES.map { (title, q) -> MusicRow(title, MusicSection.SONGS) { YtCatalog.musicSongs(q) } } +
            MusicRow("Featured playlists", MusicSection.PLAYLISTS) { YtCatalog.musicSection("top hits playlist") }
    }

    /** Listen again: songs you played (needs a few). */
    private fun listenAgain(recent: List<VideoItem>): MusicSection? =
        if (recent.size >= 3) MusicSection(MusicHome.LISTEN_AGAIN, recent.take(20), MusicSection.COVERS) else null

    /** Music tab: all rows load at the same time and appear as soon as each is ready (Listen again at once). */
    private fun loadMusic(pulled: Boolean) {
        loadJob?.cancel()
        chrome.error = null
        chrome.loading = false
        val mood = musicHome.mood
        val key = mood.orEmpty()
        val recent = MusicHistory.recent(act)
        musicSeenVersion = MusicHistory.version
        val local = if (mood == null) listenAgain(recent) else null
        vm.musicCache[key]?.let {
            musicHome.submit(listOfNotNull(local) + it, false)
            feed.refreshing = false
            applyListAdapter()
            return
        }
        if (!pulled) musicHome.submit(listOfNotNull(local), true)
        applyListAdapter()
        val plan = musicPlan(mood, recent)
        loadJob = act.lifecycleScope.launch {
            val results = arrayOfNulls<MusicSection>(plan.size)
            var failed: Exception? = null
            coroutineScope {
                plan.forEachIndexed { i, row ->
                    launch {
                        try {
                            val items = withContext(Dispatchers.IO) { row.load() }
                            if (items.isNotEmpty()) {
                                results[i] = MusicSection(row.title, items, row.kind)
                                musicHome.submit(listOfNotNull(local) + results.filterNotNull(), true)
                                feed.refreshing = false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = e
                        }
                    }
                }
            }
            feed.refreshing = false
            val list = results.filterNotNull()
            musicHome.submit(listOfNotNull(local) + list, false)
            if (list.isEmpty()) showError(failed?.message ?: "Couldn't load music. Check your internet")
            else vm.musicCache[key] = list
        }
    }

    /** Back on the Music tab after playing songs: a fresh Listen again row. */
    private fun refreshListenAgain() {
        if (MusicHistory.version == musicSeenVersion || loadJob?.isActive == true) return
        musicSeenVersion = MusicHistory.version
        val mood = musicHome.mood
        val rows = vm.musicCache[mood.orEmpty()] ?: return
        val local = if (mood == null) listenAgain(MusicHistory.recent(act)) else null
        musicHome.submit(listOfNotNull(local) + rows, false)
    }

    /** Loads the first page ([reset]) or the next page when the list is scrolled to the end. */
    private fun load(reset: Boolean, pulled: Boolean = false) {
        if (isMusic) {
            if (reset) loadMusic(pulled)
            return
        }
        val a = adapter
        if (reset) {
            loadJob?.cancel()
            next = null
            chrome.error = null
            val cached = vm.feedCache[cacheKey()]
            if (cached != null) {
                feed.refreshing = false
                // the saved list (after a theme / accent change the screen is rebuilt and shows it at once):
                // set the magazine, shelf and adapter first, or Home fell back to the old "Top pick" layout
                applyListAdapter()
                a.submit(cached.first)
                next = cached.second
                a.loadingMore = next != null
                chrome.loading = false
                // back to a list seen before: the same place in it, not the top
                val saved = scrollMemory.remove(cacheKey())
                if (saved != null) feed.restoreScroll(saved)
                else feed.scrollToTop()
                return
            }
            if (!pulled) {
                a.submit(emptyList())
                showSkeleton()
            }
            a.loadingMore = false
        } else if (loadJob?.isActive == true || next == null) {
            return
        }
        val q = query
        val plUrl = playlistUrl
        val chUrl = channelUrl
        val chTab = channelTab
        val pl = searchKind
        val tabId = tabs[tabIndex].second
        val sp = special
        val page = next
        val key = cacheKey()
        val history = AppPrefs.searchHistory(act)
        loadJob = act.lifecycleScope.launch {
            try {
                var header: ChannelDetails? = null
                val got = withContext(Dispatchers.IO) {
                    val watched by lazy { WatchHistory.recentItems(act) }     // to leave out what you already saw
                    when {
                        sp != null && sp.startsWith("q:") -> YtCatalog.kiosk(sp, page)
                        sp == "local:history" -> FeedPage(WatchHistory.recentItems(act), null)
                        sp == NOTIFICATIONS -> YtAccount.notifications(page)
                        sp != null -> YtAccount.browse(sp.removePrefix("acc:"), page)
                        chUrl != null && page == null && chTab != null && channel?.url == chUrl -> YtCatalog.channelTab(chUrl, chTab)
                        chUrl != null && page == null -> YtCatalog.channel(chUrl).let { header = it.first; it.second }
                        chUrl != null -> YtCatalog.channelMore(chUrl, page!!)
                        plUrl != null -> YtCatalog.playlist(plUrl, page)
                        q != null -> YtCatalog.search(q, pl, page)
                        // For you on a new account (no history yet) is empty on YouTube too: show popular videos instead
                        tabId == FOR_YOU && (page == null || key in popularFallback) -> {
                            if (page != null) YtCatalog.kiosk(YtCatalog.HOME, page, history, watched)
                            else {
                                val mine = runCatching { YtAccount.browse(tabId.removePrefix("acc:"), null) }.getOrNull()
                                if (mine != null && mine.items.size >= 6) { popularFallback.remove(key); mine }
                                else {
                                    popularFallback.add(key)
                                    val popular = YtCatalog.kiosk(YtCatalog.HOME, null, history, watched)
                                    FeedPage((mine?.items.orEmpty() + popular.items).distinctBy { it.url }, popular.next)
                                }
                            }
                        }
                        tabId.startsWith("acc:") -> YtAccount.browse(tabId.removePrefix("acc:"), page)
                        else -> YtCatalog.kiosk(tabId, page, history, watched)
                    }
                }
                header?.let { if (channelUrl == chUrl) fillChannel(it) }
                // Home's first page: never the same top videos every time (For you and the popular mix)
                val fresh = reset && page == null && q == null && plUrl == null && chUrl == null && sp == null &&
                    (tabId == FOR_YOU || tabId == YtCatalog.HOME)
                val res = if (fresh) FeedPage(FeedFreshen.apply(act, got.items), got.next) else got
                // a fresh list: its first videos rise in one after another
                if (reset && res.items.isNotEmpty()) feed.rise()
                val added = if (reset) { a.submit(res.items); res.items.size } else a.append(res.items)
                next = if (added == 0 && !reset) null else res.next      // a page with nothing new: stop (mixes repeat)
                a.loadingMore = next != null
                vm.feedCache[key] = a.all() to next
                chrome.loading = false
                applyListAdapter()
                feed.refreshing = false
                if (reset) feed.scrollToTop()
                if (reset && res.items.isEmpty()) showError(if (q != null) "No results for \"$q\"" else emptyText(sp ?: tabId))
                // a short page that doesn't fill the screen can't be scrolled: load the next one by itself
                if (added > 0 && next != null) hm.postDelayed({
                    if (loadJob?.isActive != true && !isMusic && !feed.canScrollDown()) load(reset = false)
                }, 300)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chrome.loading = false
                feed.refreshing = false
                applyListAdapter()
                a.loadingMore = false
                if (reset || a.count == 0) showError(e.message ?: "Couldn't load. Check your internet")
            }
        }
    }

    /** Grey placeholder rows while the first page loads (feels faster than a spinner). */
    private fun showSkeleton() {
        chrome.loading = false
        feed.showSkeleton(adapter === bigAdapter && tabIndex == 0 && query == null && playlistUrl == null &&
            channelUrl == null && special == null)
    }

    /** Signed in or out: new chips and lists. */
    fun onAccountChanged() {
        tabs = computeTabs()
        tabIndex = 0
        vm.feedCache.keys.removeAll { it.startsWith("tab:acc:") }
        updateAccountIcon()
        if (query == null) buildTabs()            // new chips now, even if a channel is open (Back shows them)
        if (query == null && playlistUrl == null && channelUrl == null && special == null) {
            applyListAdapter()
            load(reset = true)
        }
    }

    /** Top bar: the account's picture, or an empty profile icon. */
    fun updateAccountIcon() {
        // (the top bar has no account picture now; the You tab and the bottom bar show it)
    }

    /** Friendly words for an empty personal list (new accounts). */
    private fun emptyText(tabId: String): String = when (tabId) {
        "acc:FEsubscriptions" -> "No videos from subscriptions yet.\nSubscribe to channels and their new videos show here."
        "acc:FEchannels" -> "You haven't subscribed to any channels yet.\nOpen a channel and tap Subscribe."
        "acc:FEhistory" -> "No watch history yet.\nVideos you watch on YouTube show here."
        "acc:VLLL" -> "No liked videos yet.\nTap Like on a video to save it here."
        "acc:VLWL" -> "Watch later is empty.\nTap Save on a video to add it."
        "local:history" -> "No watch history yet.\nVideos you watch in RAINAX Tube show here."
        NOTIFICATIONS -> "No new notifications.\nNew videos from your channels in the last days show here."
        else -> "Nothing showing here right now.\nPull down to try again."
    }

    private fun showError(text: String) {
        // no connection: say so plainly, with a button to the phone's internet settings
        val offline = !Net.online(act)
        chrome.error = if (offline) "No internet connection\nTurn on Wi-Fi or mobile data, then tap Try again." else friendlyError(text)
        chrome.errorOffline = offline
    }

    private companion object {
        const val FOR_YOU = "acc:FEwhat_to_watch"

        /** Magazine Home chapters (title to list id): the same lists as their chips. */
        val MAG_CHAPTERS = listOf(
            "Music" to YtCatalog.MUSIC,
            "Gaming" to "q:gaming",
            "News" to "q:news today",
            "Cricket" to "q:cricket highlights",
            "Comedy" to "q:comedy"
        )

        /** The magazine's Movies section: the same list as the Movies chip. */
        const val MAG_MOVIES = "q:full movie"
        /** The bell's list (YouTube's notifications, recent ones only). */
        const val NOTIFICATIONS = "acc:notifications"
    }
}
