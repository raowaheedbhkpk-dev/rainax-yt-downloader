package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.LayoutHomeShelfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top of the Home feed: "Continue watching" (videos you didn't finish) and a strip of Shorts. */
class HomeShelf(private val act: AppCompatActivity, private val openVideo: (VideoItem) -> Unit) {

    private val b = LayoutHomeShelfBinding.inflate(act.layoutInflater)
    val root: View get() = b.root

    private val continueAdapter = ContinueAdapter()
    private val shortsAdapter = ShortsAdapter()
    private var shownVersion = -1
    private var shortsLoading = false
    private var shortsTriedAt = 0L

    /** Tap on a channel picture, and on All (the Subscriptions tab). */
    var openChannel: (VideoItem) -> Unit = {}
    var openAllSubs: () -> Unit = {}
    private val subsAdapter = ChannelAvatarAdapter { openChannel(it) }
    private var subsLoading = false
    private var subsTriedAt = 0L

    init {
        b.subsRow.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.subsRow.adapter = subsAdapter
        b.subsRowAll.setOnClickListener { openAllSubs() }
        b.continueList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.continueList.adapter = continueAdapter
        b.shortsList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.shortsList.adapter = shortsAdapter
        // swipe to the end of the Shorts strip: more Shorts load by themselves
        b.shortsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dx > 0 && lm.findLastVisibleItemPosition() >= shortsAdapter.itemCount - 4) loadMoreShorts(force = true)
            }
        })
    }

    /** Called when Home shows: newest Continue watching list, and the Shorts strip (loaded once). */
    fun refresh() {
        refreshSubs()
        if (WatchHistory.version != shownVersion || shownVersion < 0) {
            shownVersion = WatchHistory.version
            val list = WatchHistory.continueList(act)
            continueAdapter.submit(list)
            b.continueBox.isVisible = list.isNotEmpty()
        }
        if (ShortsFeed.items.isNotEmpty()) showShorts()
        // only a few Shorts so far: get more (the strip should always have plenty to swipe)
        if (ShortsFeed.items.size < 12) loadMoreShorts(force = false)
    }

    /** More Shorts at the end of the strip ([force]: the strip was swiped to its end, no 30 s wait). */
    private fun loadMoreShorts(force: Boolean) {
        if (shortsLoading) return
        val since = System.currentTimeMillis() - shortsTriedAt
        if (since < (if (force) 3_000 else 30_000)) return
        shortsLoading = true
        shortsTriedAt = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { ShortsFeed.fetchMore() }.getOrDefault(emptyList()) }
            val known = ShortsFeed.items.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
            ShortsFeed.items.addAll(fresh.filter { known.add(youtubeId(it.url) ?: it.url) })
            shortsLoading = false
            showShorts()
        }
    }

    /** Signed in: the round pictures of your channels on top (loaded once, kept 30 minutes). */
    private fun refreshSubs() {
        if (!YtAccount.isSignedIn(act)) {
            b.subsBox.isVisible = false
            return
        }
        val have = SubsChannels.cached()
        subsAdapter.submit(have)
        b.subsBox.isVisible = have.isNotEmpty()
        if (subsLoading || System.currentTimeMillis() - subsTriedAt < 60_000) return
        subsLoading = true
        subsTriedAt = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { SubsChannels.load() }.getOrDefault(emptyList()) }
            subsLoading = false
            if (!YtAccount.isSignedIn(act)) return@launch
            subsAdapter.submit(list)
            b.subsBox.isVisible = list.isNotEmpty()
        }
    }

    private fun showShorts() {
        val list = ShortsFeed.items.toList()
        shortsAdapter.submit(list)
        b.shortsBox.isVisible = list.isNotEmpty()
    }

    private inner class ContinueAdapter : RecyclerView.Adapter<ContinueAdapter.VH>() {
        private var items: List<WatchHistory.Entry> = emptyList()

        fun submit(list: List<WatchHistory.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_continue, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            Img.load(holder.thumb, e.thumb ?: youtubeId(e.url)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }, widthPx = 440)
            holder.title.text = e.title.ifBlank { "Video" }
            holder.meta.text = e.uploader
            holder.bar.progress = (e.fraction * 1000).toInt()
            holder.left.text = YtCatalog.duration((e.durMs - e.posMs) / 1000) + " left"
            holder.itemView.setOnClickListener {
                openVideo(VideoItem(e.url, e.title, e.uploader, e.thumb, e.durMs / 1000, -1, null))
            }
            // press and hold: remove it from the row
            holder.itemView.setOnLongClickListener {
                WatchHistory.remove(act, e.url)
                refresh()
                true
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.cwThumb)
            val title: TextView = v.findViewById(R.id.cwTitle)
            val meta: TextView = v.findViewById(R.id.cwMeta)
            val left: TextView = v.findViewById(R.id.cwLeft)
            val bar: ProgressBar = v.findViewById(R.id.cwBar)

            init { v.findViewById<View>(R.id.cwThumbBox).clipToOutline = true }
        }
    }

    private inner class ShortsAdapter : RecyclerView.Adapter<ShortsAdapter.VH>() {
        private var items: List<VideoItem> = emptyList()

        fun submit(list: List<VideoItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_short_card, parent, false)
            // about two and a bit Shorts across the screen, tall 9:16 like YouTube's
            val d = parent.resources.displayMetrics
            val full = parent.width.takeIf { it > 0 } ?: d.widthPixels
            val w = (full / 2.25f).toInt().coerceAtMost((190 * d.density).toInt())
            val lp = v.layoutParams
            lp.width = w
            lp.height = w * 16 / 9
            v.layoutParams = lp
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            Img.load(holder.thumb, shortThumb(item), widthPx = 360)
            holder.title.text = item.title
            holder.itemView.setOnClickListener { ShortsActivity.open(act, position) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.scThumb)
            val title: TextView = v.findViewById(R.id.scTitle)

            init { v.clipToOutline = true }
        }
    }

    companion object {
        /** The Short's picture (cropped to fill the tall card). */
        fun shortThumb(item: VideoItem): String? =
            item.thumb ?: youtubeId(item.url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }
}
