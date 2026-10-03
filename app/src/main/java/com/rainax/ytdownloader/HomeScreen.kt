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
    private val download: (VideoItem) -> Unit
) {
    private var tabIndex = 0
    private var query: String? = null          // showing search results for this
    private var playlists = false              // search tab: Playlists instead of Videos
    private var next: Page? = null
    private var loadJob: Job? = null
    private var suggestJob: Job? = null
    private var buildingTabs = false

    private var playlistUrl: String? = null    // showing this playlist's videos
    private var playlistItem: VideoItem? = null

    private val bigAdapter = VideoAdapter(true, { open(it) }, { download(it) })
    private val smallAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    private val playlistAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    private val musicAdapter = MusicAdapter({ openPlaylist(it) }, { download(it) })
    private val adapter get() = when {
        playlistUrl != null -> playlistAdapter
        query != null -> smallAdapter
        else -> bigAdapter
    }

    /** Music tab (rows of playlists) is showing. */
    private val isMusic get() = query == null && playlistUrl == null && YtCatalog.TABS[tabIndex].second == YtCatalog.MUSIC

    private fun applyListAdapter() {
        val want: RecyclerView.Adapter<*> = if (isMusic) musicAdapter else adapter
        if (hm.feedList.adapter !== want) hm.feedList.adapter = want
    }
    private val suggestAdapter = SuggestAdapter { submit(it) }

    fun setup() {
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

        hm.homeChips.setOnCheckedStateChangeListener { group, ids ->
            if (buildingTabs) return@setOnCheckedStateChangeListener
            val index = ids.firstOrNull()?.let { id -> (0 until group.childCount).firstOrNull { group.getChildAt(it).id == id } } ?: return@setOnCheckedStateChangeListener
            if (query != null) playlists = index == 1 else tabIndex = index
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

        buildTabs()
        load(reset = true)
    }

    /** True while searching or typing (Back returns to Home). */
    val inSearch get() = query != null || playlistUrl != null || hm.suggestList.isVisible

    /** Back: stop typing, or leave the search results. Returns false when there is nothing to undo. */
    fun back(): Boolean {
        if (playlistUrl != null) {
            playlistUrl = null
            playlistItem = null
            hm.titleBar.isVisible = false
            hm.chipsScroll.isVisible = true
            if (query != null) hm.searchBar.isVisible = true else hm.brandBar.isVisible = true
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
            playlists = false
            showSearchBox(false)
            buildTabs()
            applyListAdapter()
            load(reset = true)
            return true
        }
        return false
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
        AppPrefs.addSearch(act, q)
        stopTyping()
        showSearchBox(true)
        hm.searchInput.setText(q)
        val wasSearching = query != null
        query = q
        if (!wasSearching) playlists = false
        buildTabs()
        applyListAdapter()
        load(reset = true)
    }

    /** A playlist: its videos (each with Download) and "Download all" at the top. */
    fun openPlaylist(item: VideoItem) {
        if (hm.suggestList.isVisible) stopTyping()
        playlistUrl = item.url
        playlistItem = item
        hm.titleText.text = item.title
        hm.brandBar.isVisible = false
        hm.searchBar.isVisible = false
        hm.titleBar.isVisible = true
        hm.chipsScroll.isVisible = false
        applyListAdapter()
        load(reset = true)
    }

    private fun imm() = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ---------- tabs and list ----------

    /** Filter chips: YouTube / Music on Home, Videos / Playlists for search results. */
    private fun buildTabs() {
        buildingTabs = true
        hm.homeChips.removeAllViews()
        val titles = if (query != null) listOf("Videos", "Playlists") else YtCatalog.TABS.map { it.first }
        val sel = if (query != null) (if (playlists) 1 else 0) else tabIndex
        titles.forEachIndexed { i, t ->
            val chip = LayoutInflater.from(act).inflate(R.layout.item_chip, hm.homeChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = t
            hm.homeChips.addView(chip)
            if (i == sel) chip.isChecked = true
        }
        buildingTabs = false
    }

    private fun cacheKey(): String = playlistUrl?.let { "pl:$it" } ?: query?.let { "q:$it:$playlists" }
        ?: "tab:${YtCatalog.TABS[tabIndex].second}"

    private fun open(item: VideoItem) = if (item.isPlaylist) openPlaylist(item) else openVideo(item)

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
            hm.feedLoading.isVisible = true
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
                hm.feedList.scrollToPosition(0)
                return
            }
            if (!pulled) {
                a.submit(emptyList())
                hm.feedLoading.isVisible = true
            }
            a.loadingMore = false
        } else if (loadJob?.isActive == true || next == null) {
            return
        }
        val q = query
        val plUrl = playlistUrl
        val pl = playlists
        val tabId = YtCatalog.TABS[tabIndex].second
        val page = next
        val key = cacheKey()
        val history = AppPrefs.searchHistory(act)
        loadJob = act.lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    when {
                        plUrl != null -> YtCatalog.playlist(plUrl, page)
                        q != null -> YtCatalog.search(q, pl, page)
                        else -> YtCatalog.kiosk(tabId, page, history)
                    }
                }
                val added = if (reset) { a.submit(res.items); res.items.size } else a.append(res.items)
                next = if (added == 0 && !reset) null else res.next      // a page with nothing new: stop (mixes repeat)
                a.loadingMore = next != null
                vm.feedCache[key] = a.all() to next
                hm.feedLoading.isVisible = false
                hm.feedRefresh.isRefreshing = false
                if (reset) hm.feedList.scrollToPosition(0)
                if (reset && res.items.isEmpty()) showError(if (q != null) "No results for \"$q\"" else "Nothing here right now")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hm.feedLoading.isVisible = false
                hm.feedRefresh.isRefreshing = false
                a.loadingMore = false
                if (reset || a.count == 0) showError(e.message ?: "Couldn't load. Check your internet")
            }
        }
    }

    private fun showError(text: String) {
        hm.feedErrorText.text = text
        hm.feedError.isVisible = true
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
