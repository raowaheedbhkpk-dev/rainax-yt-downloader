package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Video list with a Download button on every row. [big] = large pictures (Home, related),
 * otherwise picture on the left (search). An optional [header] view sits on top (video page details).
 */
class VideoAdapter(
    private val big: Boolean,
    private val onOpen: (VideoItem) -> Unit,
    private val onDownload: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    var header: View? = null
        set(value) {
            field = value
            rebuild()
            notifyDataSetChanged()
        }

    /** + button on every video: pick several, then download them together (null = no + button). */
    var onPick: ((VideoItem) -> Unit)? = null
    var isPicked: (String) -> Boolean = { false }

    private fun itemAt(i: Int) = items[i]
    private val contentCount get() = items.size

    var loadingMore = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            notifyDataSetChanged()
        }

    val count get() = items.size

    /** Header, loading row and channels take the whole width in a grid (tablets); videos one column. */
    fun isFullWidth(position: Int): Boolean = getItemViewType(position) != ITEM

    /** ⋮ on every video (Save to playlist, Share, Not interested…); null = no ⋮ button. */
    var onMore: ((VideoItem, View) -> Unit)? = null

    /** Home (Cinema Pro): the first video is shown as a big movie poster with Play and Download. */
    var hero = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            notifyDataSetChanged()
        }

    /**
     * Home's first tab as a magazine (Cinema Pro): Cover story, Top 5, the shelf, Editor's picks, chapters
     * (Music, Gaming, News...) with their own rows of posters, a pull quote, then "More stories".
     */
    var magazine = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            notifyDataSetChanged()
        }

    /** Magazine chapters: title to list id. Their videos load on their own (see [onNeedChapter]). */
    var chapters: List<Pair<String, String>> = emptyList()
    var onNeedChapter: ((String) -> Unit)? = null
    var onChapterMore: ((String) -> Unit)? = null
    /** "Your week" numbers for the magazine (null = no such card). */
    class WeekStats(val watchedMs: Long, val videos: Int, val downloads: Int, val topChannel: String?)
    var weekStats: (() -> WeekStats)? = null
    private var week: WeekStats? = null

    /** The magazine's Movies section (cinema tickets): its list id, loaded like a chapter. */
    var moviesId: String? = null
    private val chapterItems = HashMap<String, List<VideoItem>>()
    private val chapterAsked = HashSet<String>()

    /** A chapter's videos arrived (empty = it failed or had nothing: the chapter is left out). */
    fun setChapter(id: String, list: List<VideoItem>) {
        chapterItems[id] = keep(list)
        if (magazine) { rebuild(); notifyDataSetChanged() }
    }

    /** Pull to refresh: chapters load again too. */
    fun clearChapters() {
        chapterItems.clear()
        chapterAsked.clear()
        if (magazine) { rebuild(); notifyDataSetChanged() }
    }

    /** Home and Up next: leave out videos and channels you said "Not interested" to. */
    var filterHidden = false

    private fun keep(list: List<VideoItem>) = if (filterHidden) list.filter { HiddenFeed.allowed(it) } else list

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(keep(list))
        rebuild()
        notifyDataSetChanged()
    }

    /** "Not interested": the video (or a whole channel's videos) leaves the list at once. */
    fun removeWhere(test: (VideoItem) -> Boolean) {
        if (magazine) {
            items.removeAll(test)
            chapterItems.keys.toList().forEach { k -> chapterItems[k] = chapterItems[k].orEmpty().filterNot(test) }
            rebuild()
            notifyDataSetChanged()
            return
        }
        var i = 0
        while (i < items.size) {
            if (test(items[i])) {
                items.removeAt(i)
                val wasHero = slots.getOrNull(i + headerCount)?.type == HERO
                rebuild()
                if (wasHero || (hero && i == 0)) notifyDataSetChanged() else notifyItemRemoved(i + headerCount)
            } else i++
        }
    }

    /** Adds the next page (skipping videos already shown). Returns how many were new. */
    fun append(list: List<VideoItem>): Int {
        val known = items.mapTo(HashSet()) { it.url }
        val fresh = keep(list).filter { known.add(it.url) }
        items.addAll(fresh)
        rebuild()
        notifyDataSetChanged()
        return fresh.size
    }

    fun all(): List<VideoItem> = items.toList()

    private val headerCount get() = if (header != null) 1 else 0

    /** What sits at each place in the list. */
    private sealed class Slot(val type: Int) {
        object Header : Slot(HEADER)
        object Footer : Slot(FOOTER)
        class Video(val i: Int, type: Int) : Slot(type)
        class Section(val label: String, val title: String) : Slot(SECTION)
        class Chart(val i: Int, val rank: Int) : Slot(CHART)
        class Grid(val i: Int) : Slot(GRID)
        class Chapter(val label: String, val title: String, val id: String) : Slot(CHAPTER)
        class Movies(val label: String, val id: String) : Slot(MOVIES)
        object Week : Slot(WEEK)
    }

    private var slots: List<Slot> = emptyList()

    private fun rebuild() {
        val out = ArrayList<Slot>()
        val n = items.size
        fun video(i: Int, normal: Int = ITEM) = Slot.Video(i, if (items[i].isChannel) CHANNEL else normal)
        if (magazine && n >= 9 && items.take(9).none { it.isChannel }) {
            var chapterNo = 0
            fun label(t: String): String { chapterNo++; return String.format(java.util.Locale.US, "%02d — %s", chapterNo, t.uppercase()) }
            out += Slot.Video(0, COVER)
            out += Slot.Section(label("Today"), "Top 5 right now")
            for (r in 1..5) out += Slot.Chart(r, r)
            if (header != null) out += Slot.Header
            out += Slot.Section(label("Editor's picks"), "Worth your time")
            out += Slot.Grid(6)
            week = weekStats?.invoke()?.takeIf { it.videos > 0 || it.downloads > 0 }
            if (week != null) out += Slot.Week
            var next = 9
            chapters.forEachIndexed { ci, (title, id) ->
                val got = chapterItems[id]
                if (got == null || got.isNotEmpty()) out += Slot.Chapter(label(title), title, id)
                // a pull quote after the first chapter, then the cinema
                if (ci == 0 && next < n && !items[next].isChannel) out += Slot.Video(next++, WIDE)
                if (ci == 0) moviesId?.let { mid ->
                    val m = chapterItems[mid]
                    if (m == null || m.isNotEmpty()) out += Slot.Movies(label("Movies"), mid)
                }
            }
            if (next < n) out += Slot.Section(label("More stories"), "Keep watching")
            for (i in next until n) out += video(i)
        } else {
            if (header != null) out += Slot.Header
            for (i in 0 until n) out += video(i, if (hero && big && i == 0 && !items[0].isPlaylist && !items[0].isShort) HERO else ITEM)
        }
        if (loadingMore) out += Slot.Footer
        slots = out
    }

    override fun getItemCount() = slots.size

    override fun getItemViewType(position: Int): Int = slots[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            HEADER -> object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
            FOOTER -> object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_loading, parent, false)) {}
            CHANNEL -> ChannelRow(inf.inflate(R.layout.item_channel_row, parent, false))
            HERO -> Row(inf.inflate(R.layout.item_video_hero, parent, false), isHero = true)
            COVER -> Row(inf.inflate(R.layout.item_mag_cover, parent, false), isHero = true)
            WIDE -> Row(inf.inflate(R.layout.item_mag_wide, parent, false), isHero = true)
            SECTION -> SectionRow(inf.inflate(R.layout.item_mag_section, parent, false))
            CHART -> ChartRow(inf.inflate(R.layout.item_mag_chart, parent, false))
            GRID -> GridRow(inf.inflate(R.layout.item_mag_grid, parent, false))
            CHAPTER -> ChapterRow(inf.inflate(R.layout.item_mag_chapter, parent, false))
            MOVIES -> MoviesRow(inf.inflate(R.layout.item_mag_movies, parent, false))
            WEEK -> WeekRow(inf.inflate(R.layout.item_mag_week, parent, false))
            else -> Row(inf.inflate(if (big) R.layout.item_video_big else R.layout.item_video_small, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val slot = slots[position]
        when (holder) {
            is Row -> holder.bind(itemAt((slot as Slot.Video).i))
            is ChannelRow -> holder.bind(itemAt((slot as Slot.Video).i))
            is SectionRow -> (slot as Slot.Section).let { holder.bind(it.label, it.title, null) }
            is ChartRow -> (slot as Slot.Chart).let { holder.bind(itemAt(it.i), it.rank) }
            is GridRow -> holder.bind((slot as Slot.Grid).i)
            is ChapterRow -> (slot as Slot.Chapter).let { holder.bind(it.label, it.title, it.id) }
            is MoviesRow -> (slot as Slot.Movies).let { holder.bind(it.label, it.id) }
            is WeekRow -> week?.let { holder.bind(it) }
            else -> if (slot === Slot.Header) {
                val box = holder.itemView as FrameLayout
                val h = header ?: return
                if (h.parent !== box) {
                    (h.parent as? ViewGroup)?.removeView(h)
                    box.removeAllViews()
                    box.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
    }

    inner class Row(v: View, private val isHero: Boolean = false) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        /** The duration badge (feed autoplay shows the time left in it). */
        val duration: TextView = v.findViewById(R.id.duration)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val download: View = v.findViewById(R.id.download)
        private val heroPlay: View? = v.findViewById(R.id.heroPlay)
        private val avatar: ImageView? = v.findViewById(R.id.avatar)      // big rows only
        private val magTag: TextView? = v.findViewById(R.id.magTag)        // magazine cover byline
        private val watchBar: android.widget.ProgressBar? = v.findViewById(R.id.watchBar)
        private val pick: ImageButton? = v.findViewById(R.id.pick)
        private val more: ImageButton? = v.findViewById(R.id.more)

        /** The picture's frame: feed autoplay puts the playing video in it. */
        val thumbBox: ViewGroup get() = thumb.parent as ViewGroup
        /** The video this row shows now. */
        var item: VideoItem? = null
            private set
        /** Big rows of normal videos can play in the list (not playlists, channels or live streams). */
        val canPreview get() = big && !isHero && item?.let { !it.isPlaylist && !it.isChannel && !it.isShort && it.seconds > 0 } == true

        init {
            (thumb.parent as View).clipToOutline = true      // rounded corners from the background shape
        }

        fun bind(item: VideoItem) {
            this.item = item
            Img.load(thumb, item.thumb, widthPx = if (big) 720 else 360)
            title.text = if (itemViewType == WIDE) "\u201C${item.title}\u201D" else item.title
            magTag?.text = listOf("Cover story", item.uploader).filter { it.isNotBlank() }.joinToString(" · ")
            avatar?.let { Img.load(it, item.avatar, circle = true, widthPx = 96) }
            val parts = mutableListOf<String>()
            if (item.uploader.isNotBlank()) parts += item.uploader
            if (item.isPlaylist) {
                if (item.count > 0) parts += "${item.count} videos"
            } else {
                if (item.views >= 0) parts += YtCatalog.count(item.views) + if (item.seconds < 0) " watching" else " views"
                item.uploaded?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            meta.text = when (itemViewType) {
                COVER -> listOf(metaShort(item)).filter { it.isNotBlank() }.joinToString()
                WIDE -> listOf(item.uploader, metaShort(item)).filter { it.isNotBlank() }.joinToString(" · ")
                else -> parts.joinToString(" • ")
            }
            val badge = when {
                item.isPlaylist -> if (item.count > 0) "${item.count} videos" else "Playlist"
                item.isShort -> "SHORTS"
                else -> YtCatalog.duration(item.seconds)
            }
            // how much of it you watched (red bar, like YouTube)
            val watched = if (item.isPlaylist || item.isChannel) -1f else WatchHistory.progress(itemView.context, item.url)
            watchBar?.isVisible = watched > 0f
            if (watched > 0f) watchBar?.progress = (watched * 1000).toInt()
            duration.text = badge
            duration.isVisible = badge.isNotEmpty()
            duration.setBackgroundResource(if (item.seconds < 0) R.drawable.bg_live else R.drawable.bg_duration)
            itemView.setOnClickListener { onOpen(item) }
            heroPlay?.setOnClickListener { onOpen(item) }
            val menu = onMore
            more?.isVisible = menu != null && !item.isChannel
            more?.setOnClickListener { v -> menu?.invoke(item, v) }
            itemView.setOnLongClickListener { v -> if (menu != null && !item.isChannel) { menu(item, v); true } else false }
            download.setOnClickListener { onDownload(item) }
            // sizes ready before Download is tapped (normal videos and Shorts; not live, playlists or channels)
            if (!item.isPlaylist && !item.isChannel && item.seconds >= 0) FastExtractor.lookAhead(item.url)
            val picker = onPick
            pick?.isVisible = picker != null && !item.isPlaylist && !item.isChannel && item.seconds >= 0
            if (picker != null) {
                val on = isPicked(item.url)
                pick?.setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_add)
                pick?.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (on) Ui.accent(itemView.context) else androidx.core.content.ContextCompat.getColor(itemView.context, R.color.rx_text)
                )
                pick?.contentDescription = if (on) "Selected. Tap to unselect" else "Select for download"
                pick?.setOnClickListener {
                    picker(item)
                }
            }
        }
    }

    /** "1.2M views · 3 days ago" */
    private fun metaShort(item: VideoItem): String = listOfNotNull(
        item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + if (item.seconds < 0) " watching" else " views" },
        item.uploaded?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    private fun badge(item: VideoItem): String = when {
        item.isPlaylist -> if (item.count > 0) "${item.count} videos" else "Playlist"
        item.isShort -> "SHORTS"
        item.seconds < 0 -> "LIVE"
        else -> YtCatalog.duration(item.seconds)
    }

    /** Tap opens, hold shows the menu (Save, Share, Not interested…): the same on every magazine piece. */
    private fun wire(v: View, item: VideoItem) {
        v.setOnClickListener { onOpen(item) }
        val menu = onMore
        v.setOnLongClickListener { x -> if (menu != null && !item.isChannel) { menu(item, x); true } else false }
    }

    /** Section opener: "01 — TODAY" and a big heading. */
    inner class SectionRow(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.magLabel)
        private val title: TextView = v.findViewById(R.id.magTitle)
        private val more: TextView = v.findViewById(R.id.magMore)

        fun bind(l: String, t: String, onMoreClick: (() -> Unit)?) {
            label.text = l
            title.text = t
            more.isVisible = onMoreClick != null
            more.setOnClickListener { onMoreClick?.invoke() }
        }
    }

    /** One place in the Top 5: number 1 in film red, the others as outlined numbers. */
    inner class ChartRow(v: View) : RecyclerView.ViewHolder(v) {
        private val rank: TextView = v.findViewById(R.id.magRank)
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        private val duration: TextView = v.findViewById(R.id.duration)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val download: View = v.findViewById(R.id.download)

        init { (thumb.parent as View).clipToOutline = true }

        fun bind(item: VideoItem, place: Int) {
            val ctx = itemView.context
            rank.text = place.toString()
            val first = place == 1
            rank.paint.style = if (first) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
            rank.paint.strokeWidth = 1.5f * ctx.resources.displayMetrics.density
            rank.setTextColor(if (first) Ui.accent(ctx) else androidx.core.content.ContextCompat.getColor(ctx, R.color.rx_text))
            rank.contentDescription = "Number $place"
            rank.invalidate()
            Img.load(thumb, item.thumb, widthPx = 360)
            duration.text = badge(item)
            duration.isVisible = duration.text.isNotEmpty()
            title.text = item.title
            meta.text = listOf(item.uploader, metaShort(item)).filter { it.isNotBlank() }.joinToString(" · ")
            wire(itemView, item)
            download.setOnClickListener { onDownload(item) }
        }
    }

    /** Editor's picks: one tall story and two small ones. */
    inner class GridRow(v: View) : RecyclerView.ViewHolder(v) {
        private inner class Tile(val box: View, val thumb: ImageView, val tag: TextView, val title: TextView, val meta: TextView)

        private val tiles = listOf(
            Tile(v.findViewById(R.id.g0), v.findViewById(R.id.g0Thumb), v.findViewById(R.id.g0Tag), v.findViewById(R.id.g0Title), v.findViewById(R.id.g0Meta)),
            Tile(v.findViewById(R.id.g1), v.findViewById(R.id.g1Thumb), v.findViewById(R.id.g1Tag), v.findViewById(R.id.g1Title), v.findViewById(R.id.g1Meta)),
            Tile(v.findViewById(R.id.g2), v.findViewById(R.id.g2Thumb), v.findViewById(R.id.g2Tag), v.findViewById(R.id.g2Title), v.findViewById(R.id.g2Meta))
        )

        init { tiles.forEach { it.box.clipToOutline = true } }

        fun bind(start: Int) {
            tiles.forEachIndexed { n, t ->
                val item = items.getOrNull(start + n) ?: return@forEachIndexed
                Img.load(t.thumb, item.thumb, widthPx = if (n == 0) 720 else 480)
                t.tag.text = item.uploader
                t.title.text = item.title
                t.meta.text = listOfNotNull(badge(item).takeIf { it.isNotBlank() }, item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " views" })
                    .joinToString(" · ")
                wire(t.box, item)
            }
        }
    }

    /** A chapter: the opener and a sideways row of tall posters (its videos load when it first shows). */
    inner class ChapterRow(v: View) : RecyclerView.ViewHolder(v) {
        private val head = SectionRow(v.findViewById(R.id.magHead))
        private val row: RecyclerView = v.findViewById(R.id.magRow)
        private val loading: View = v.findViewById(R.id.magLoading)
        private val posters = PosterAdapter()

        init {
            row.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
            row.adapter = posters
            row.isNestedScrollingEnabled = false
        }

        fun bind(label: String, title: String, id: String) {
            head.bind(label, title, onChapterMore?.let { m -> { m(id) } })
            val got = chapterItems[id]
            loading.isVisible = got == null
            if (posters.submit(got.orEmpty())) row.scrollToPosition(0)
            if (got == null && chapterAsked.add(id)) onNeedChapter?.invoke(id)
        }
    }

    /** Your week: hours watched, videos, downloads, and your top channel. */
    inner class WeekRow(v: View) : RecyclerView.ViewHolder(v) {
        private val hours: TextView = v.findViewById(R.id.wkHours)
        private val hoursLabel: TextView = v.findViewById(R.id.wkHoursLabel)
        private val videos: TextView = v.findViewById(R.id.wkVideos)
        private val downloads: TextView = v.findViewById(R.id.wkDownloads)
        private val top: TextView = v.findViewById(R.id.wkTop)

        fun bind(w: WeekStats) {
            val minutes = w.watchedMs / 60_000
            if (minutes >= 60) {
                hours.text = String.format(java.util.Locale.US, "%.1f", minutes / 60.0)
                hoursLabel.text = "Hours watched"
            } else {
                hours.text = minutes.toString()
                hoursLabel.text = "Minutes watched"
            }
            videos.text = w.videos.toString()
            downloads.text = w.downloads.toString()
            top.isVisible = !w.topChannel.isNullOrBlank()
            top.text = "Most watched: ${w.topChannel.orEmpty()}"
            itemView.contentDescription = "Your week: ${hours.text} ${hoursLabel.text}, ${w.videos} videos, ${w.downloads} downloads"
        }
    }

    /** Movies: a dark cinema band with NOW SHOWING and a row of tickets (long films first). */
    inner class MoviesRow(v: View) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.movLabel)
        private val row: RecyclerView = v.findViewById(R.id.movRow)
        private val more: View = v.findViewById(R.id.movMore)
        private val tickets = TicketAdapter()

        init {
            row.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(v.context, RecyclerView.HORIZONTAL, false)
            row.adapter = tickets
            row.isNestedScrollingEnabled = false
        }

        fun bind(l: String, id: String) {
            label.text = l
            more.isVisible = onChapterMore != null
            more.setOnClickListener { onChapterMore?.invoke(id) }
            val got = chapterItems[id]
            // real films (over 50 minutes) first; trailers and clips only when there are too few
            val films = got.orEmpty().let { all -> all.filter { it.seconds >= 3000 }.let { f -> if (f.size >= 3) f else all } }
            if (tickets.submit(films)) row.scrollToPosition(0)
            if (got == null && chapterAsked.add(id)) onNeedChapter?.invoke(id)
        }
    }

    private inner class TicketAdapter : RecyclerView.Adapter<TicketAdapter.T>() {
        private var list: List<VideoItem> = emptyList()

        fun submit(l: List<VideoItem>): Boolean {
            if (l == list) return false
            list = l
            notifyDataSetChanged()
            return true
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            T(LayoutInflater.from(parent.context).inflate(R.layout.item_mag_ticket, parent, false))

        override fun onBindViewHolder(h: T, position: Int) = h.bind(list[position])

        inner class T(v: View) : RecyclerView.ViewHolder(v) {
            private val poster: View = v.findViewById(R.id.tkPoster)
            private val thumb: ImageView = v.findViewById(R.id.thumb)
            private val title: TextView = v.findViewById(R.id.title)
            private val meta: TextView = v.findViewById(R.id.meta)
            private val duration: TextView = v.findViewById(R.id.duration)

            init { poster.clipToOutline = true }

            fun bind(item: VideoItem) {
                Img.load(thumb, item.thumb, widthPx = 480)
                title.text = item.title
                meta.text = listOf(item.uploader, item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " views" }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · ")
                duration.text = badge(item)
                itemView.contentDescription = item.title
                wire(itemView, item)
            }
        }
    }

    private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.P>() {
        private var list: List<VideoItem> = emptyList()

        /** True when the row shows something new. */
        fun submit(l: List<VideoItem>): Boolean {
            if (l == list) return false
            list = l
            notifyDataSetChanged()
            return true
        }

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            P(LayoutInflater.from(parent.context).inflate(R.layout.item_mag_poster, parent, false))

        override fun onBindViewHolder(h: P, position: Int) = h.bind(list[position])

        inner class P(v: View) : RecyclerView.ViewHolder(v) {
            private val thumb: ImageView = v.findViewById(R.id.thumb)
            private val duration: TextView = v.findViewById(R.id.duration)
            private val title: TextView = v.findViewById(R.id.title)
            private val meta: TextView = v.findViewById(R.id.meta)

            init { v.clipToOutline = true }

            fun bind(item: VideoItem) {
                Img.load(thumb, item.thumb, widthPx = 480)
                duration.text = badge(item)
                duration.isVisible = duration.text.isNotEmpty()
                title.text = item.title
                meta.text = item.uploader
                wire(itemView, item)
            }
        }
    }

    /** A channel (search results, subscriptions): picture, name, subscribers. */
    inner class ChannelRow(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: ImageView = v.findViewById(R.id.chAvatar)
        private val name: TextView = v.findViewById(R.id.chName)
        private val meta: TextView = v.findViewById(R.id.chMeta)

        fun bind(item: VideoItem) {
            Img.load(avatar, item.thumb, circle = true, widthPx = 160)
            name.text = item.title
            meta.text = item.uploaded ?: listOfNotNull(
                item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " subscribers" },
                item.count.takeIf { it > 0 }?.let { "$it videos" }
            ).joinToString(" • ")
            itemView.setOnClickListener { onOpen(item) }
        }
    }

    companion object {
        private const val CHANNEL = 3
        private const val HEADER = 0
        private const val ITEM = 1
        private const val FOOTER = 2
        private const val HERO = 4
        private const val COVER = 5
        private const val SECTION = 6
        private const val CHART = 7
        private const val GRID = 8
        private const val WIDE = 9
        private const val CHAPTER = 10
        private const val MOVIES = 11
        private const val WEEK = 12
    }
}
