package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Music tab: rows ("Punjabi Hits", "Bollywood", ...) of playlist covers, each with a Download button. */
class MusicAdapter(
    private val onOpen: (VideoItem) -> Unit,
    private val onDownload: (VideoItem) -> Unit
) : RecyclerView.Adapter<MusicAdapter.Row>() {

    private var sections: List<MusicSection> = emptyList()
    private val pool = RecyclerView.RecycledViewPool()

    fun submit(list: List<MusicSection>) {
        sections = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = sections.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_music_section, parent, false)
        return Row(v)
    }

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(sections[position])

    inner class Row(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.sectionTitle)
        private val list: RecyclerView = v.findViewById(R.id.sectionList)
        private val cards = CardAdapter()

        init {
            list.layoutManager = LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            list.setRecycledViewPool(pool)
            list.adapter = cards
        }

        fun bind(s: MusicSection) {
            title.text = s.title
            cards.submit(s.items)
            list.scrollToPosition(0)
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<Card>() {
        private var items: List<VideoItem> = emptyList()

        fun submit(list: List<VideoItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Card(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false))

        override fun onBindViewHolder(holder: Card, position: Int) = holder.bind(items[position])
    }

    private inner class Card(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.cardTitle)
        private val sub: TextView = v.findViewById(R.id.cardSub)
        private val download: ImageButton = v.findViewById(R.id.cardDownload)

        init {
            v.findViewById<View>(R.id.coverBox).clipToOutline = true      // rounded corners
        }

        fun bind(item: VideoItem) {
            Img.load(cover, item.thumb, widthPx = 400)
            title.text = item.title
            sub.text = when {
                item.uploader.isNotBlank() -> item.uploader
                item.count > 0 -> "${item.count} songs"
                else -> "Playlist"
            }
            itemView.setOnClickListener { onOpen(item) }
            download.setOnClickListener { onDownload(item) }
        }
    }
}
