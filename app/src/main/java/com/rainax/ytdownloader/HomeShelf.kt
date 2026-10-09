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

    init {
        b.continueList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.continueList.adapter = continueAdapter
        b.shortsList.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.shortsList.adapter = shortsAdapter
    }

    /** Called when Home shows: newest Continue watching list, and the Shorts strip (loaded once). */
    fun refresh() {
        if (WatchHistory.version != shownVersion || shownVersion < 0) {
            shownVersion = WatchHistory.version
            val list = WatchHistory.continueList(act)
            continueAdapter.submit(list)
            b.continueBox.isVisible = list.isNotEmpty()
        }
        if (ShortsFeed.items.isNotEmpty()) showShorts()
        // only a few Shorts so far: get more (the strip should always have plenty to swipe)
        if (ShortsFeed.items.size < 8 && !shortsLoading && System.currentTimeMillis() - shortsTriedAt > 30_000) {
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
    }

    private fun showShorts() {
        val list = ShortsFeed.items.take(15)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_short_card, parent, false))

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
