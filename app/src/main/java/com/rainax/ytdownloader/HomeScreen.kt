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
import com.google.android.material.chip.Chip
import com.rainax.ytdownloader.databinding.PageHomeBinding
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
    private val hm: PageHomeBinding,
    private val vm: MainViewModel,
    private val openVideo: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit,
    private val onAccount: () -> Unit
) {
    /** Home chips: personal lists when signed in to YouTube. */
    private var tabs: List<Pair<String, String>> = YtCatalog.TABS
    /** List keys where For you is showing popular videos (more pages come from the popular list). */
    private val popularFallback: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val skeleton = SkeletonAdapter()

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
    private val channelHeader = com.rainax.ytdownloader.databinding.ItemChannelHeaderBinding.inflate(act.layoutInflater)
    private val channelAdapter = VideoAdapter(false, { open(it) }, { download(it) }).also { it.header = channelHeader.root }

    private val bigAdapter = VideoAdapter(true, { open(it) }, { download(it) }).also { it.hero = true }
    private val smallAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    /** Continue watching + Shorts, on top of the first Home tab. */
    private val shelf = HomeShelf(act, openVideo)
    private val playlistAdapter = VideoAdapter(false, { open(it) }, { download(it) })
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
            hm.feedList.scrollToPosition(0)
            loadMusic(pulled = false)
        }
    )
    private var musicSeenVersion = -1
    /** Soft glow at the top of the Music tab, in the colour of the song playing (like YouTube Music). */
    private val musicGlow = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.accent(act), 0x30), android.graphics.Color.TRANSPARENT)
    )
    /**
     * A list opened from the side menu, the bell or the You tab (title bar with Back):
     * "acc:<YouTube list>" (needs the account), "q:<topic search>" or "local:history".
     */
    private var special: String? = null
    private val specialAdapter = VideoAdapter(true, { open(it) }, { download(it) })

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
            VideoAdapter.WeekStats(w.watchedMs, w.videos, dl, w.topChannel)
        }
        bigAdapter.onNeedChapter = { id -> loadChapter(id) }
        bigAdapter.onChapterMore = { id -> openTab(id) }
    }

    /** A chapter's posters: from the saved tab list when there is one, otherwise its first page. */
    private fun loadChapter(id: String) {
        fun pick(list: List<VideoItem>) = list.filter { !it.isChannel && !it.isPlaylist }.take(if (id == MAG_MOVIES) 20 else 12)
        val cached = vm.feedCache["tab:$id"]
        if (cached != null) {
            hm.feedList.post { bigAdapter.setChapter(id, pick(cached.first)) }      // never while the list lays out
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
            hm.feedList.post { bigAdapter.setChapter(id, pick(res?.items.orEmpty())) }
        }
    }

    /** MORE on a chapter: that chip opens (Music, Gaming...). */
    private fun openTab(id: String) {
        if (query != null) return
        val i = tabs.indexOfFirst { it.second == id }
        val chip = hm.homeChips.getChildAt(i) as? Chip ?: return
        chip.isChecked = true
        hm.chipsScroll.post { hm.chipsScroll.smoothScrollTo((chip.left - 24 * act.resources.displayMetrics.density).toInt().coerceAtLeast(0), 0) }
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
        val want: RecyclerView.Adapter<*> = if (isMusic) musicHome else adapter
        if (hm.feedList.adapter !== want) hm.feedList.adapter = want
        val bg = if (isMusic) musicGlow else null
        if (hm.feedList.background !== bg) hm.feedList.background = bg
    }
    private val suggestAdapter = SuggestAdapter(
        onPick = { submit(it) },
        onFill = { text ->
            // ↖ : put the suggestion in the box to keep typing
            hm.searchInput.setText(text)
            hm.searchInput.setSelection(hm.searchInput.text.length)
        },
        onClear = { confirmClearHistory() }
    )

    /** "Clear search history" under the recent searches: asks once, then removes them all. */
    private fun confirmClearHistory() {
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Clear search history?")
            .setMessage("Your recent searches will be removed from this phone.")
            .setPositiveButton("Clear") { _, _ ->
                AppPrefs.clearSearchHistory(act)
                suggest(hm.searchInput.text.toString())
                Notify.show(act, "Search history cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The ≡ button (side menu) and the mic (voice search): handled by the main screen. */
    var onMenu: () -> Unit = {}
    var onVoice: () -> Unit = {}

    /** All on the channels row: the Subscriptions tab (set by the main screen). */
    var onAllSubs: () -> Unit = {}

    fun setup() {
        shelf.openChannel = { openChannel(it.url, it.title, it.thumb) }
        shelf.openAllSubs = { onAllSubs() }
        tabs = computeTabs()
        hm.accountBtn.setOnClickListener { onAccount() }
        updateAccountIcon()
        hm.root.isFocusableInTouchMode = true
        applyColumns()
        hm.feedList.adapter = bigAdapter
        hm.feedList.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(act, R.anim.rx_list_rise)
        hm.feedList.setHasFixedSize(true)
        hm.feedList.setItemViewCacheSize(8)
        hm.feedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - 5) load(reset = false)
            }
        })
        hm.suggestList.layoutManager = LinearLayoutManager(act)
        hm.suggestList.adapter = suggestAdapter

        // pull down: fresh list (not the saved one)
        // the round spinner is hidden: a film countdown shows instead (see countdown())
        hm.feedRefresh.setColorSchemeColors(android.graphics.Color.TRANSPARENT)
        hm.feedRefresh.setProgressBackgroundColorSchemeColor(android.graphics.Color.TRANSPARENT)
        hm.feedRefresh.setOnRefreshListener {
            countdown()
            if (isMusic) vm.musicCache.remove(musicHome.mood.orEmpty()) else vm.feedCache.remove(cacheKey())
            if (!isMusic && bigAdapter.magazine) {                    // the magazine's chapters come fresh too
                (MAG_CHAPTERS.map { it.second } + MAG_MOVIES).forEach { vm.feedCache.remove("tab:$it") }
                bigAdapter.clearChapters()
            }
            if (!isMusic && bigAdapter.header === shelf.root) shelf.refreshShorts()      // fresh Shorts strip too
            load(reset = true, pulled = true)
        }
        hm.titleBack.setOnClickListener { back() }
        hm.titleDownload.setOnClickListener { playlistItem?.let { download(it) } }
        channelHeader.chSubscribe.setOnClickListener { toggleChannelSubscribe() }

        hm.homeChips.setOnCheckedStateChangeListener { group, ids ->
            if (buildingTabs) return@setOnCheckedStateChangeListener
            val index = ids.firstOrNull()?.let { id -> (0 until group.childCount).firstOrNull { group.getChildAt(it).id == id } } ?: return@setOnCheckedStateChangeListener
            if (query != null) searchKind = index else tabIndex = index
            applyListAdapter()
            load(reset = true)
        }

        hm.searchOpen.setOnClickListener { startTyping() }
        hm.searchOpen.setOnLongClickListener { onVoice(); true }          // hold Search: speak straight away
        // like YouTube: a search icon in the top bar opens the glowing search box (the old pill stays hidden;
        // notifications live on the You tab)
        hm.homeSearchPill.isVisible = false
        hm.homeSearchMic.setOnClickListener { onVoice() }
        hm.homeMenu.setOnClickListener { onMenu() }
        hm.homeBell.setOnClickListener { openNotifications() }
        hm.searchMic.setOnClickListener { onVoice() }
        hm.searchInput.setOnFocusChangeListener { _, focused -> if (focused) startTyping() }
        hm.searchInput.setOnClickListener { startTyping() }
        hm.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit(hm.searchInput.text.toString())
                true
            } else false
        }
        hm.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hm.searchClear.isVisible = !s.isNullOrEmpty()
                hm.searchMic.isVisible = s.isNullOrEmpty()
                if (hm.suggestList.isVisible) suggest(s?.toString().orEmpty())
            }
        })
        hm.searchClear.setOnClickListener {
            hm.searchInput.setText("")
            startTyping()
        }
        hm.searchBack.setOnClickListener { back() }
        hm.feedRetry.setOnClickListener { load(reset = true) }
        hm.feedSettings.setOnClickListener { Net.openSettings(act) }

        // + on every video: pick several, then download them together
        listOf(bigAdapter, smallAdapter, playlistAdapter, channelAdapter, specialAdapter).forEach { a ->
            a.onPick = { Picks.toggle(it); if (Picks.has(it.url) && Picks.size <= Picks.SAMPLE) vm.warm(it.url) }
            a.isPicked = { Picks.has(it) }
        }
        Picks.listen { refreshPicks() }
        hm.pickClear.setOnClickListener { Picks.clear() }
        hm.pickCount.setOnClickListener { PicksSheet.show(act) { downloadMany(it) } }
        hm.pickDownload.setOnClickListener {
            val list = Picks.all()
            if (list.isEmpty()) return@setOnClickListener
            Picks.clear()
            downloadMany(list)
        }

        buildTabs()
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
            hm.titleBar.isVisible = false
            hm.chipsScroll.isVisible = true
            if (query != null) hm.searchBar.isVisible = true else hm.brandBar.isVisible = true
            if (query == null) buildTabs()
            applyListAdapter()
            load(reset = true)                     // the list before is still saved: shows at once
            return true
        }
        if (hm.suggestList.isVisible) {
            stopTyping()
            if (query == null) showSearchBox(false) else hm.searchInput.setText(query)
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
        return false
    }

    // ---------- picking several videos ----------

    /** Called with the picked videos when Download is tapped on the bar. */
    var downloadMany: (List<VideoItem>) -> Unit = {}

    private fun refreshPicks() {
        val n = Picks.size
        hm.pickBar.isVisible = n > 0
        hm.pickCount.text = if (n == 1) "1 selected · View" else "$n selected · View"
        hm.pickDownload.text = if (n > 1) "Download $n" else "Download"
        val d = act.resources.displayMetrics.density
        hm.feedList.setPadding(hm.feedList.paddingLeft, hm.feedList.paddingTop, hm.feedList.paddingRight, ((if (n > 0) 88 else 16) * d).toInt())
        hm.feedList.adapter?.notifyDataSetChanged()
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
        val w = act.resources.configuration.screenWidthDp
        val cols = when {
            w >= 1000 -> 3
            w >= 600 -> 2
            else -> 1
        }
        val cur = hm.feedList.layoutManager
        // columns: a small gap between the full-width pictures
        if (cols > 1 && hm.feedList.itemDecorationCount == 0) hm.feedList.addItemDecoration(columnGap)
        if (cols == 1 && hm.feedList.itemDecorationCount > 0) hm.feedList.removeItemDecoration(columnGap)
        if (cols == 1) {
            if (cur !is LinearLayoutManager || cur is androidx.recyclerview.widget.GridLayoutManager) {
                hm.feedList.layoutManager = LinearLayoutManager(act)
            }
            return
        }
        val grid = (cur as? androidx.recyclerview.widget.GridLayoutManager)
            ?: androidx.recyclerview.widget.GridLayoutManager(act, cols).also { hm.feedList.layoutManager = it }
        grid.spanCount = cols
        grid.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val a = hm.feedList.adapter
                return if (a is VideoAdapter && !a.isFullWidth(position)) 1 else cols
            }
        }
    }

    private val columnGap = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val g = (6 * act.resources.displayMetrics.density).toInt()
            out.set(g, 0, g, 0)
        }
    }

    /** Home is on screen again: newest Continue watching row and watched bars. */
    fun onShown() {
        if (bigAdapter.header != null) shelf.refresh()
        if (isMusic && hm.feedList.adapter === musicHome) refreshListenAgain()
        if (hm.feedList.adapter === bigAdapter || hm.feedList.adapter === smallAdapter) hm.feedList.adapter?.notifyDataSetChanged()
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
        hm.brandBar.isVisible = !on
        hm.searchBar.isVisible = on
        if (!on) hm.searchInput.setText("")
    }

    private fun startTyping() {
        val opening = !hm.searchBar.isVisible
        showSearchBox(true)
        if (opening) Motion.focusIn(hm.searchBox)          // the glowing box eases in
        hm.suggestList.isVisible = true
        suggest(hm.searchInput.text.toString())
        if (!hm.searchInput.hasFocus()) hm.searchInput.requestFocus()
        hm.searchInput.post { imm().showSoftInput(hm.searchInput, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun stopTyping() {
        hm.suggestList.isVisible = false
        suggestJob?.cancel()
        hm.root.requestFocus()                 // take focus away from the box (it would reopen the suggestions)
        imm().hideSoftInputFromWindow(hm.searchInput.windowToken, 0)
    }

    private fun suggest(text: String) {
        suggestJob?.cancel()
        val history = AppPrefs.searchHistory(act)
        if (text.isBlank()) {
            // like YouTube: your recent searches, then "You may like" (channels you watch, popular videos) with pictures
            val recent = history.take(10).map { Sug(it, icon = R.drawable.ic_history) }
            val local = mayLike(history)
            suggestAdapter.submit(emptyList(), local, showClear = history.isNotEmpty(), stubs = recent)
            // then, like YouTube: searches related to what you watch and search, each with a matching picture
            suggestJob = act.lifecycleScope.launch {
                val personal = try {
                    withContext(Dispatchers.IO) { relatedSearches(history, local) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                if (personal.isEmpty() || hm.searchInput.text?.isNotBlank() == true) return@launch
                val channels = local.filter { it.sub != null }
                val trending = local.filter { it.sub == null && personal.none { p -> p.text == it.text } }
                suggestAdapter.submit(emptyList(), (channels + personal + trending).take(8), showClear = history.isNotEmpty(), stubs = recent)
            }
            return
        }
        val fromHistory = history.filter { it.contains(text, true) }.take(3).map { Sug(it, icon = R.drawable.ic_history) }
        suggestAdapter.submit(fromHistory)
        suggestJob = act.lifecycleScope.launch {
            delay(220)                           // wait for a pause in typing
            val list = withContext(Dispatchers.IO) { YtCatalog.suggestions(text) }
            suggestAdapter.submit(fromHistory + list.filter { s -> fromHistory.none { it.text.equals(s, true) } }.map { Sug(it) })
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
        val link = Regex("https?://\\S+").find(q)?.value
        if (link != null) {
            stopTyping()
            hm.searchInput.setText("")
            if (query == null) showSearchBox(false)
            download(VideoItem(link, "", "", null, 0, -1, null))
            return
        }
        AppPrefs.addSearch(act, q)
        rememberScroll()
        stopTyping()
        showSearchBox(true)
        hm.searchInput.setText(q)
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
        if (hm.suggestList.isVisible) stopTyping()
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
        hm.titleText.text = title
        hm.titleDownload.isVisible = false
        hm.brandBar.isVisible = false
        hm.searchBar.isVisible = false
        hm.titleBar.isVisible = true
        hm.chipsScroll.isVisible = false
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
        (hm.homeChips.getChildAt(i) as? Chip)?.isChecked = true
    }

    /** The bell: YouTube's own notifications, on their own page laid out like the YouTube app. */
    fun openNotifications() {
        if (!YtAccount.isSignedIn(act)) {
            Notify.show(act, "Sign in to YouTube to see your notifications", long = true)
            onAccount()
            return
        }
        if (hm.suggestList.isVisible) stopTyping()
        NotificationsPage(act, { open(it) }, { download(it) }).show()
    }

    /** A playlist: its videos (each with Download) and "Download all" at the top. */
    fun openPlaylist(item: VideoItem) {
        if (hm.suggestList.isVisible) stopTyping()
        rememberScroll()
        special = null
        channelUrl = null
        channel = null
        playlistUrl = item.url
        playlistItem = item
        hm.titleText.text = item.title
        hm.titleDownload.isVisible = true
        hm.brandBar.isVisible = false
        hm.searchBar.isVisible = false
        hm.titleBar.isVisible = true
        hm.chipsScroll.isVisible = false
        applyListAdapter()
        load(reset = true)
    }

    /** A channel: picture, name, Subscribe, and its videos (each with Download). */
    fun openChannel(url: String, name: String? = null, avatar: String? = null) {
        if (hm.suggestList.isVisible) stopTyping()
        rememberScroll()
        special = null
        playlistUrl = null
        playlistItem = null
        channelUrl = url
        channel = null
        channelSubscribed = null
        channelTab = null
        channelHeader.chTabs.removeAllViews()
        channelHeader.chSubscribe.isVisible = true
        hm.titleText.text = name.orEmpty()
        hm.titleDownload.isVisible = false
        hm.brandBar.isVisible = false
        hm.searchBar.isVisible = false
        hm.titleBar.isVisible = true
        hm.chipsScroll.isVisible = false
        channelHeader.chHeadName.text = name.orEmpty()
        channelHeader.chHeadMeta.text = ""
        channelHeader.chHeadDesc.isVisible = false
        Img.load(channelHeader.chHeadAvatar, avatar, circle = true, widthPx = 200)
        Img.load(channelHeader.chBanner, null)
        Ui.subscribeButton(channelHeader.chSubscribe, false)
        vm.feedCache.keys.removeAll { it.startsWith("ch:$url:") }      // the header comes with the first page
        applyListAdapter()
        load(reset = true)
    }

    private fun fillChannel(d: ChannelDetails) {
        channel = d
        hm.titleText.text = d.name
        channelHeader.chHeadName.text = d.name
        channelHeader.chHeadMeta.text = if (d.subscribers >= 0) YtCatalog.count(d.subscribers) + " subscribers" else ""
        channelHeader.chHeadDesc.text = d.description
        channelHeader.chHeadDesc.isVisible = d.description.isNotBlank()
        // (keep the picture already shown when YouTube's answer has none)
        if (d.avatar != null) Img.load(channelHeader.chHeadAvatar, d.avatar, circle = true, widthPx = 200)
        Img.load(channelHeader.chBanner, d.banner, widthPx = 1080)
        // your own channel: no Subscribe button (like YouTube)
        val me = if (YtAccount.isSignedIn(act)) YtAccount.profile(act) else null
        val handle = me?.handle?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        val own = me != null && ((handle != null && d.url.contains("@$handle", true)) || me.name.equals(d.name, true))
        channelHeader.chSubscribe.isVisible = !own
        // Videos / Shorts / Live / Playlists
        channelTab = d.tab
        channelHeader.chTabs.setOnCheckedStateChangeListener(null)
        channelHeader.chTabs.removeAllViews()
        d.tabs.forEach { label ->
            val chip = act.layoutInflater.inflate(R.layout.item_chip, channelHeader.chTabs, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chip.tag = label
            channelHeader.chTabs.addView(chip)
            if (label == d.tab) chip.isChecked = true
        }
        channelHeader.chTabs.isVisible = d.tabs.size > 1
        channelHeader.chTabs.setOnCheckedStateChangeListener { group, ids ->
            val label = ids.firstOrNull()?.let { group.findViewById<View>(it)?.tag as? String } ?: return@setOnCheckedStateChangeListener
            if (label == channelTab || channelUrl != d.url) return@setOnCheckedStateChangeListener
            channelTab = label
            load(reset = true)
        }
        if (own) return
        val id = d.id
        if (id != null && YtAccount.isSignedIn(act)) {
            act.lifecycleScope.launch {
                val sub = withContext(Dispatchers.IO) { runCatching { YtAccount.channelSubscribed(id) }.getOrNull() }
                if (channel?.id != id) return@launch
                channelSubscribed = sub
                Ui.subscribeButton(channelHeader.chSubscribe, sub == true)
            }
        }
    }

    private fun toggleChannelSubscribe() {
        if (!YtAccount.isSignedIn(act)) {
            onAccount()                                 // sign in first
            return
        }
        val id = channel?.id ?: return
        val now = channelSubscribed == true
        channelSubscribed = !now
        Ui.subscribeButton(channelHeader.chSubscribe, !now)
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.subscribe(id, !now) }.isSuccess }
            if (!ok) {
                channelSubscribed = now
                Ui.subscribeButton(channelHeader.chSubscribe, now)
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
        hm.homeChips.removeAllViews()
        val titles = if (query != null) YtCatalog.SEARCH_KINDS.map { it.first } else tabs.map { it.first }
        val sel = if (query != null) searchKind else tabIndex
        titles.forEachIndexed { i, t ->
            val chip = LayoutInflater.from(act).inflate(R.layout.item_chip, hm.homeChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = t
            hm.homeChips.addView(chip)
            if (i == sel) chip.isChecked = true
        }
        buildingTabs = false
    }

    /** Where each list was scrolled to, so Back returns to the same place. */
    private val scrollMemory = HashMap<String, android.os.Parcelable?>()

    private fun rememberScroll() {
        if (isMusic) return
        scrollMemory[cacheKey()] = hm.feedList.layoutManager?.onSaveInstanceState()
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
        musicGlow.colors = intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(glow, 0x38), android.graphics.Color.TRANSPARENT)
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
        hm.feedError.isVisible = false
        hm.feedLoading.isVisible = false
        val mood = musicHome.mood
        val key = mood.orEmpty()
        val recent = MusicHistory.recent(act)
        musicSeenVersion = MusicHistory.version
        val local = if (mood == null) listenAgain(recent) else null
        vm.musicCache[key]?.let {
            musicHome.submit(listOfNotNull(local) + it, false)
            hm.feedRefresh.isRefreshing = false
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
                                hm.feedRefresh.isRefreshing = false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed = e
                        }
                    }
                }
            }
            hm.feedRefresh.isRefreshing = false
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
            hm.feedError.isVisible = false
            val cached = vm.feedCache[cacheKey()]
            if (cached != null) {
                hm.feedRefresh.isRefreshing = false
                a.submit(cached.first)
                next = cached.second
                a.loadingMore = next != null
                hm.feedLoading.isVisible = false
                // back to a list seen before: the same place in it, not the top
                val saved = scrollMemory.remove(cacheKey())
                if (saved != null) hm.feedList.layoutManager?.onRestoreInstanceState(saved)
                else hm.feedList.scrollToPosition(0)
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
                if (reset && res.items.isNotEmpty()) hm.feedList.scheduleLayoutAnimation()
                val added = if (reset) { a.submit(res.items); res.items.size } else a.append(res.items)
                next = if (added == 0 && !reset) null else res.next      // a page with nothing new: stop (mixes repeat)
                a.loadingMore = next != null
                vm.feedCache[key] = a.all() to next
                hm.feedLoading.isVisible = false
                applyListAdapter()
                hm.feedRefresh.isRefreshing = false
                if (reset) hm.feedList.scrollToPosition(0)
                if (reset && res.items.isEmpty()) showError(if (q != null) "No results for \"$q\"" else emptyText(sp ?: tabId))
                // a short page that doesn't fill the screen can't be scrolled: load the next one by itself
                if (added > 0 && next != null) hm.feedList.post {
                    if (loadJob?.isActive != true && !hm.feedList.canScrollVertically(1)) load(reset = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hm.feedLoading.isVisible = false
                hm.feedRefresh.isRefreshing = false
                applyListAdapter()
                a.loadingMore = false
                if (reset || a.count == 0) showError(e.message ?: "Couldn't load. Check your internet")
            }
        }
    }

    /** Pull to refresh: 3 · 2 · 1 like an old film leader, round and round until the new list is in. */
    private fun countdown() {
        val v = hm.feedCountdown
        if (v.isVisible) return
        v.isVisible = true
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(150).start()
        var n = 3
        val tick = object : Runnable {
            override fun run() {
                if (!hm.feedRefresh.isRefreshing) {
                    v.animate().alpha(0f).setDuration(180).withEndAction { v.isVisible = false }.start()
                    return
                }
                v.text = n.toString()
                v.scaleX = 1.25f; v.scaleY = 1.25f
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
                n = if (n == 1) 3 else n - 1
                v.postDelayed(this, 520)
            }
        }
        v.post(tick)
    }

    /** Grey placeholder rows while the first page loads (feels faster than a spinner). */
    private fun showSkeleton() {
        hm.feedLoading.isVisible = false
        skeleton.magazine = adapter === bigAdapter && tabIndex == 0 && query == null && playlistUrl == null &&
            channelUrl == null && special == null
        if (hm.feedList.adapter !== skeleton) hm.feedList.adapter = skeleton
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
        val avatar = if (YtAccount.isSignedIn(act)) YtAccount.profile(act)?.avatar else null
        if (avatar != null) {
            hm.accountIcon.imageTintList = null
            Img.load(hm.accountIcon, avatar, circle = true, widthPx = 96)
        } else {
            Img.load(hm.accountIcon, null)
            hm.accountIcon.setImageResource(R.drawable.ic_person)
            hm.accountIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(act, R.color.rx_text)
            )
        }
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
        hm.feedErrorText.text = if (offline) "No internet connection\nTurn on Wi-Fi or mobile data, then tap Try again." else friendlyError(text)
        hm.feedSettings.isVisible = offline
        hm.feedError.isVisible = true
    }

    /** Five grey rows with a band of light sweeping across them (the row root is an RxShimmer). */
    /** Grey placeholders in the shape of what is coming: the magazine (cover, section, Top 5) or a video list. */
    private class SkeletonAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var magazine = false
            set(value) {
                if (field == value) return
                field = value
                notifyDataSetChanged()
            }
        override fun getItemCount() = if (magazine) 6 else 5
        override fun getItemViewType(position: Int) = when {
            !magazine -> R.layout.item_skeleton
            position == 0 -> R.layout.item_skeleton_cover
            position == 1 -> R.layout.item_skeleton_section
            else -> R.layout.item_skeleton_chart
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
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

    /** One search suggestion: text, an optional small line and picture, and its icon (clock, search, trending). */
    class Sug(val text: String, val sub: String? = null, val thumb: String? = null, val icon: Int = R.drawable.ic_search)

    /**
     * Search suggestions, like YouTube. Empty box: recent searches, "Clear search history", then "You may like".
     * Typing: matching recent searches first, then YouTube's suggestions.
     */
    private class SuggestAdapter(
        private val onPick: (String) -> Unit,
        private val onFill: (String) -> Unit,
        private val onClear: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private sealed class Row {
            class Item(val s: Sug) : Row()
            class Header(val title: String) : Row()
            object Clear : Row()
            class Stubs(val list: List<Sug>) : Row()
        }
        private var rows: List<Row> = emptyList()

        fun submit(list: List<Sug>, mayLike: List<Sug> = emptyList(), showClear: Boolean = false, stubs: List<Sug> = emptyList()) {
            val r = mutableListOf<Row>()
            if (stubs.isNotEmpty()) r += Row.Stubs(stubs)
            list.forEach { r += Row.Item(it) }
            if (showClear) r += Row.Clear
            if (mayLike.isNotEmpty()) {
                r += Row.Header("You may like")
                mayLike.forEach { r += Row.Item(it) }
            }
            rows = r
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Item -> ROW
            is Row.Header -> HEADER
            Row.Clear -> CLEAR
            is Row.Stubs -> STUBS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val d = parent.resources.displayMetrics.density
            if (viewType == HEADER) {
                val tv = TextView(c).apply {
                    textSize = 22f
                    isAllCaps = true
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.rx_display)
                    setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.rx_text))
                    setPadding((16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            if (viewType == STUBS) {
                // recent searches as ticket stubs in one sideways row, under a small gold label
                val box = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (14 * d).toInt(), 0, (6 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                box.addView(TextView(c).apply {
                    text = "RECENT SEARCHES"
                    textSize = 10.5f
                    letterSpacing = 0.25f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.rx_gold))
                    setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt())
                })
                val scroll = android.widget.HorizontalScrollView(c).apply {
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                }
                scroll.addView(LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; tag = "stubs" })
                box.addView(scroll)
                return object : RecyclerView.ViewHolder(box) {}
            }
            if (viewType == CLEAR) {
                // one row like the others: a bin icon in the same column, the words in film red, a thin line under it
                val row = LayoutInflater.from(c).inflate(R.layout.item_suggestion, parent, false)
                val red = Ui.accent(c)
                row.findViewById<ImageView>(R.id.icon).apply {
                    setImageResource(R.drawable.ic_delete)
                    imageTintList = android.content.res.ColorStateList.valueOf(red)
                }
                row.findViewById<TextView>(R.id.text).apply {
                    text = "Clear search history"
                    setTextColor(red)
                    textSize = 14f
                    isAllCaps = true
                    letterSpacing = 0.08f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                row.findViewById<View>(R.id.fill).isVisible = false
                row.findViewById<View>(R.id.thumb).isVisible = false
                val box = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(row)
                    addView(View(c).apply {
                        setBackgroundColor(androidx.core.content.ContextCompat.getColor(c, R.color.rx_divider))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (1 * d).toInt())).apply {
                        marginStart = (16 * d).toInt(); marginEnd = (16 * d).toInt(); topMargin = (4 * d).toInt()
                    })
                }
                return object : RecyclerView.ViewHolder(box) {}
            }
            return VH(LayoutInflater.from(c).inflate(R.layout.item_suggestion, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.title
                is Row.Stubs -> {
                    val c = holder.itemView.context
                    val d = c.resources.displayMetrics.density
                    val strip = holder.itemView.findViewWithTag<LinearLayout>("stubs")
                    strip.removeAllViews()
                    row.list.forEach { s ->
                        strip.addView(TextView(c).apply {
                            text = s.text
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            maxWidth = (180 * d).toInt()
                            textSize = 13.5f
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.rx_text))
                            setBackgroundResource(R.drawable.bg_recent_stub)
                            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
                            contentDescription = "Search ${s.text}. Hold to edit"
                            setOnClickListener { onPick(s.text) }
                            setOnLongClickListener { onFill(s.text); true }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * d).toInt()).apply {
                            marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
                        })
                    }
                }
                Row.Clear -> (holder.itemView as ViewGroup).getChildAt(0).setOnClickListener { onClear() }
                is Row.Item -> {
                    val h = holder as VH
                    val s = row.s
                    h.text.text = s.text
                    h.sub.text = s.sub
                    h.sub.isVisible = !s.sub.isNullOrBlank()
                    h.icon.setImageResource(s.icon)
                    h.thumb.isVisible = s.thumb != null
                    if (s.thumb != null) Img.load(h.thumb, s.thumb, widthPx = 240)
                    h.itemView.setOnClickListener { onPick(s.text) }
                    h.fill.setOnClickListener { onFill(s.text) }
                }
            }
        }

        companion object {
            private const val ROW = 0
            private const val CLEAR = 1
            private const val HEADER = 2
            private const val STUBS = 3
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val text: TextView = v.findViewById(R.id.text)
            val sub: TextView = v.findViewById(R.id.sub)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val fill: ImageView = v.findViewById(R.id.fill)
        }
    }
}
