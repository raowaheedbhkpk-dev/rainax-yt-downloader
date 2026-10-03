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
import com.google.android.material.tabs.TabLayout
import com.rainax.ytdownloader.databinding.PageHomeBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val bigAdapter = VideoAdapter(true, { open(it) }, { download(it) })
    private val smallAdapter = VideoAdapter(false, { open(it) }, { download(it) })
    private val adapter get() = if (query != null) smallAdapter else bigAdapter
    private val suggestAdapter = SuggestAdapter { submit(it) }

    fun setup() {
        hm.root.isFocusableInTouchMode = true
        hm.feedList.layoutManager = LinearLayoutManager(act)
        hm.feedList.adapter = bigAdapter
        hm.feedList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - 4) load(reset = false)
            }
        })
        hm.suggestList.layoutManager = LinearLayoutManager(act)
        hm.suggestList.adapter = suggestAdapter

        hm.homeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (buildingTabs) return
                if (query != null) playlists = tab.position == 1 else tabIndex = tab.position
                load(reset = true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                hm.feedList.smoothScrollToPosition(0)
            }
        })

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
    val inSearch get() = query != null || hm.suggestList.isVisible

    /** Back: stop typing, or leave the search results. Returns false when there is nothing to undo. */
    fun back(): Boolean {
        if (hm.suggestList.isVisible) {
            stopTyping()
            if (query == null) {
                hm.searchInput.setText("")
                hm.searchBack.isVisible = false
                hm.searchIcon.isVisible = true
            } else {
                hm.searchInput.setText(query)
            }
            return true
        }
        if (query != null) {
            query = null
            playlists = false
            hm.searchInput.setText("")
            hm.searchBack.isVisible = false
            hm.searchIcon.isVisible = true
            hm.feedList.adapter = bigAdapter
            buildTabs()
            load(reset = true)
            return true
        }
        return false
    }

    /** Search for [text] (used by the search box and for links that are not videos). */
    fun search(text: String) = submit(text)

    // ---------- search box ----------

    private fun startTyping() {
        hm.suggestList.isVisible = true
        hm.searchBack.isVisible = true
        hm.searchIcon.isVisible = false
        suggest(hm.searchInput.text.toString())
        if (!hm.searchInput.hasFocus()) hm.searchInput.requestFocus()
        imm().showSoftInput(hm.searchInput, InputMethodManager.SHOW_IMPLICIT)
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
        hm.searchInput.setText(q)
        hm.searchBack.isVisible = true
        hm.searchIcon.isVisible = false
        val wasSearching = query != null
        query = q
        if (!wasSearching) playlists = false
        hm.feedList.adapter = smallAdapter
        buildTabs()
        load(reset = true)
    }

    private fun imm() = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    // ---------- tabs and list ----------

    private fun buildTabs() {
        buildingTabs = true
        hm.homeTabs.removeAllTabs()
        val titles = if (query != null) listOf("Videos", "Playlists") else YtCatalog.TABS.map { it.first }
        titles.forEach { hm.homeTabs.addTab(hm.homeTabs.newTab().setText(it), false) }
        val sel = if (query != null) (if (playlists) 1 else 0) else tabIndex
        hm.homeTabs.getTabAt(sel)?.select()
        buildingTabs = false
    }

    private fun cacheKey(): String = query?.let { "q:$it:$playlists" } ?: "tab:${YtCatalog.TABS[tabIndex].second}"

    private fun open(item: VideoItem) = openVideo(item)

    /** Loads the first page ([reset]) or the next page when the list is scrolled to the end. */
    private fun load(reset: Boolean) {
        val a = adapter
        if (reset) {
            loadJob?.cancel()
            next = null
            hm.feedError.isVisible = false
            val cached = vm.feedCache[cacheKey()]
            if (cached != null) {
                a.submit(cached.first)
                next = cached.second
                a.loadingMore = next != null
                hm.feedLoading.isVisible = false
                hm.feedList.scrollToPosition(0)
                return
            }
            a.submit(emptyList())
            a.loadingMore = false
            hm.feedLoading.isVisible = true
        } else if (loadJob?.isActive == true || next == null) {
            return
        }
        val q = query
        val pl = playlists
        val tabId = YtCatalog.TABS[tabIndex].second
        val page = next
        val key = cacheKey()
        val history = AppPrefs.searchHistory(act)
        loadJob = act.lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    if (q != null) YtCatalog.search(q, pl, page) else YtCatalog.kiosk(tabId, page, history)
                }
                if (reset) a.submit(res.items) else a.append(res.items)
                next = res.next
                a.loadingMore = next != null
                vm.feedCache[key] = a.all() to next
                hm.feedLoading.isVisible = false
                if (reset && res.items.isEmpty()) showError(if (q != null) "No results for \"$q\"" else "Nothing here right now")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hm.feedLoading.isVisible = false
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
