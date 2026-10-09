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
        ) else YtCatalog.TABS

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
    private val channelHeader = com.rainax.ytdownloader.databinding.ItemChannelHeaderBinding.inflate(act.layoutInflater)
    private val channelAdapter = VideoAdapter(false, { open(it) }, { download(it) }).also { it.header = channelHeader.root }

    private val bigAdapter = VideoAdapter(true, { open(it) }, { download(it) })
    private val smallAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    /** Continue watching + Shorts, on top of the first Home tab. */
    private val shelf = HomeShelf(act, openVideo)
    private val playlistAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    private val musicAdapter = MusicAdapter({ openPlaylist(it) }, { download(it) })
    private val adapter get() = when {
        channelUrl != null -> channelAdapter
        playlistUrl != null -> playlistAdapter
        query != null -> smallAdapter
        else -> bigAdapter
    }

    /** Music tab (rows of playlists) is showing. */
    private val isMusic get() = query == null && playlistUrl == null && channelUrl == null && tabs[tabIndex].second == YtCatalog.MUSIC

    private fun applyListAdapter() {
        val shelfHere = tabIndex == 0 && query == null && playlistUrl == null && channelUrl == null
        val wantHeader = if (shelfHere) shelf.root else null
        if (bigAdapter.header !== wantHeader) bigAdapter.header = wantHeader
        if (shelfHere) shelf.refresh()
        val want: RecyclerView.Adapter<*> = if (isMusic) musicAdapter else adapter
        if (hm.feedList.adapter !== want) hm.feedList.adapter = want
    }
    private val suggestAdapter = SuggestAdapter { submit(it) }

    fun setup() {
        tabs = computeTabs()
        hm.accountBtn.setOnClickListener { onAccount() }
        updateAccountIcon()
        hm.root.isFocusableInTouchMode = true
        hm.feedList.layoutManager = LinearLayoutManager(act)
        hm.feedList.adapter = bigAdapter
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
        hm.feedRefresh.setColorSchemeResources(R.color.rx_primary)
        hm.feedRefresh.setOnRefreshListener {
            if (isMusic) vm.musicCache = null else vm.feedCache.remove(cacheKey())
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
        listOf(bigAdapter, smallAdapter, playlistAdapter, channelAdapter).forEach { a ->
            a.onPick = { togglePick(it) }
            a.isPicked = { picked.containsKey(it) }
        }
        hm.pickClear.setOnClickListener {
            picked.clear()
            refreshPicks()
        }
        hm.pickDownload.setOnClickListener {
            val list = picked.values.toList()
            if (list.isEmpty()) return@setOnClickListener
            picked.clear()
            refreshPicks()
            downloadMany(list)
        }

        buildTabs()
        load(reset = true)
    }

    /** Back: stop typing, or leave the search results. Returns false when there is nothing to undo. */
    fun back(): Boolean {
        if (playlistUrl != null || channelUrl != null) {
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
    private val picked = LinkedHashMap<String, VideoItem>()

    private fun togglePick(item: VideoItem) {
        if (picked.remove(item.url) == null) picked[item.url] = item
        refreshPicks()
    }

    private fun refreshPicks() {
        val n = picked.size
        hm.pickBar.isVisible = n > 0
        hm.pickCount.text = if (n == 1) "1 video selected" else "$n videos selected"
        hm.pickDownload.text = if (n > 1) "Download $n" else "Download"
        val d = act.resources.displayMetrics.density
        hm.feedList.setPadding(hm.feedList.paddingLeft, hm.feedList.paddingTop, hm.feedList.paddingRight, ((if (n > 0) 88 else 16) * d).toInt())
        hm.feedList.adapter?.notifyDataSetChanged()
    }

    /** Back with videos picked: clear the picks first. */
    fun clearPicks(): Boolean {
        if (picked.isEmpty()) return false
        picked.clear()
        refreshPicks()
        return true
    }

    /** Home is on screen again: newest Continue watching row and watched bars. */
    fun onShown() {
        if (bigAdapter.header != null) shelf.refresh()
        if (hm.feedList.adapter === bigAdapter || hm.feedList.adapter === smallAdapter) hm.feedList.adapter?.notifyDataSetChanged()
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
        showSearchBox(true)
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
            suggestAdapter.submit(history.take(12), history.size)
            return
        }
        val fromHistory = history.filter { it.contains(text, true) }.take(3)
        suggestAdapter.submit(fromHistory, fromHistory.size)
        suggestJob = act.lifecycleScope.launch {
            delay(220)                           // wait for a pause in typing
            val list = withContext(Dispatchers.IO) { YtCatalog.suggestions(text) }
            suggestAdapter.submit(fromHistory + list.filter { s -> fromHistory.none { it.equals(s, true) } }, fromHistory.size)
        }
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

    /** A playlist: its videos (each with Download) and "Download all" at the top. */
    fun openPlaylist(item: VideoItem) {
        if (hm.suggestList.isVisible) stopTyping()
        rememberScroll()
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
        playlistUrl = null
        playlistItem = null
        channelUrl = url
        channel = null
        channelSubscribed = null
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
        vm.feedCache.remove("ch:$url")                 // the header comes with the first page
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
        Img.load(channelHeader.chHeadAvatar, d.avatar, circle = true, widthPx = 200)
        Img.load(channelHeader.chBanner, d.banner, widthPx = 1080)
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
                android.widget.Toast.makeText(act, "Couldn't change the subscription. Try again", android.widget.Toast.LENGTH_SHORT).show()
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

    private fun cacheKey(): String = channelUrl?.let { "ch:$it" } ?: playlistUrl?.let { "pl:$it" } ?: query?.let { "q:$it:$searchKind" }
        ?: "tab:${tabs[tabIndex].second}"

    private fun open(item: VideoItem) = when {
        item.isChannel -> openChannel(item.url, item.title, item.thumb)
        item.isPlaylist -> openPlaylist(item)
        else -> openVideo(item)
    }

    /** Music tab: all rows load at the same time and appear as soon as each is ready. */
    private fun loadMusic(pulled: Boolean) {
        loadJob?.cancel()
        hm.feedError.isVisible = false
        vm.musicCache?.let {
            musicAdapter.submit(it)
            hm.feedLoading.isVisible = false
            hm.feedRefresh.isRefreshing = false
            return
        }
        if (!pulled) {
            musicAdapter.submit(emptyList())
            showSkeleton()
        }
        val rows = YtCatalog.MUSIC_SECTIONS
        loadJob = act.lifecycleScope.launch {
            val results = arrayOfNulls<MusicSection>(rows.size)
            var failed: Exception? = null
            coroutineScope {
                rows.forEachIndexed { i, row ->
                    launch {
                        try {
                            val items = withContext(Dispatchers.IO) { YtCatalog.musicSection(row.second) }
                            if (items.isNotEmpty()) {
                                results[i] = MusicSection(row.first, items)
                                musicAdapter.submit(results.filterNotNull())
                                applyListAdapter()
                                hm.feedLoading.isVisible = false
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
            hm.feedLoading.isVisible = false
            hm.feedRefresh.isRefreshing = false
            applyListAdapter()
            val list = results.filterNotNull()
            if (list.isEmpty()) showError(failed?.message ?: "Couldn't load music. Check your internet")
            else vm.musicCache = list
        }
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
        val pl = searchKind
        val tabId = tabs[tabIndex].second
        val page = next
        val key = cacheKey()
        val history = AppPrefs.searchHistory(act)
        loadJob = act.lifecycleScope.launch {
            try {
                var header: ChannelDetails? = null
                val res = withContext(Dispatchers.IO) {
                    when {
                        chUrl != null && page == null -> YtCatalog.channel(chUrl).let { header = it.first; it.second }
                        chUrl != null -> YtCatalog.channelMore(chUrl, page!!)
                        plUrl != null -> YtCatalog.playlist(plUrl, page)
                        q != null -> YtCatalog.search(q, pl, page)
                        // For you on a new account (no history yet) is empty on YouTube too: show popular videos instead
                        tabId == FOR_YOU && (page == null || key in popularFallback) -> {
                            if (page != null) YtCatalog.kiosk(YtCatalog.HOME, page, history)
                            else {
                                val mine = runCatching { YtAccount.browse(tabId.removePrefix("acc:"), null) }.getOrNull()
                                if (mine != null && mine.items.size >= 6) { popularFallback.remove(key); mine }
                                else {
                                    popularFallback.add(key)
                                    val popular = YtCatalog.kiosk(YtCatalog.HOME, null, history)
                                    FeedPage((mine?.items.orEmpty() + popular.items).distinctBy { it.url }, popular.next)
                                }
                            }
                        }
                        tabId.startsWith("acc:") -> YtAccount.browse(tabId.removePrefix("acc:"), page)
                        else -> YtCatalog.kiosk(tabId, page, history)
                    }
                }
                header?.let { if (channelUrl == chUrl) fillChannel(it) }
                val added = if (reset) { a.submit(res.items); res.items.size } else a.append(res.items)
                next = if (added == 0 && !reset) null else res.next      // a page with nothing new: stop (mixes repeat)
                a.loadingMore = next != null
                vm.feedCache[key] = a.all() to next
                hm.feedLoading.isVisible = false
                applyListAdapter()
                hm.feedRefresh.isRefreshing = false
                if (reset) hm.feedList.scrollToPosition(0)
                if (reset && res.items.isEmpty()) showError(if (q != null) "No results for \"$q\"" else emptyText(tabId))
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

    /** Grey placeholder rows while the first page loads (feels faster than a spinner). */
    private fun showSkeleton() {
        hm.feedLoading.isVisible = false
        if (hm.feedList.adapter !== skeleton) hm.feedList.adapter = skeleton
    }

    /** Signed in or out: new chips and lists. */
    fun onAccountChanged() {
        tabs = computeTabs()
        tabIndex = 0
        vm.feedCache.keys.removeAll { it.startsWith("tab:acc:") }
        updateAccountIcon()
        if (query == null) buildTabs()            // new chips now, even if a channel is open (Back shows them)
        if (query == null && playlistUrl == null && channelUrl == null) {
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
        else -> "Nothing here right now"
    }

    private fun showError(text: String) {
        // no connection: say so plainly, with a button to the phone's internet settings
        val offline = !Net.online(act)
        hm.feedErrorText.text = if (offline) "No internet connection\nTurn on Wi-Fi or mobile data, then tap Try again." else friendlyError(text)
        hm.feedSettings.isVisible = offline
        hm.feedError.isVisible = true
    }

    /** Five grey rows that gently pulse. */
    private class SkeletonAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 5
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_skeleton, parent, false)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder.itemView.startAnimation(android.view.animation.AlphaAnimation(1f, 0.45f).apply {
                duration = 750
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            })
        }
    }

    private companion object {
        const val FOR_YOU = "acc:FEwhat_to_watch"
    }

    /** Search suggestions: recent searches (clock icon) first, then YouTube's suggestions. */
    private class SuggestAdapter(private val onPick: (String) -> Unit) : RecyclerView.Adapter<SuggestAdapter.VH>() {
        private var items: List<String> = emptyList()
        private var historyCount = 0

        fun submit(list: List<String>, fromHistory: Int) {
            items = list
            historyCount = fromHistory
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val text = items[position]
            holder.text.text = text
            holder.icon.setImageResource(if (position < historyCount) R.drawable.ic_history else R.drawable.ic_search)
            holder.itemView.setOnClickListener { onPick(text) }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val text: TextView = v.findViewById(R.id.text)
        }
    }
}
