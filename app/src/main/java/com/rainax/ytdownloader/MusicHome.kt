package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * The Music tab, like YouTube Music: mood chips, Listen again, Quick picks and song rows (columns of four
 * songs you swipe through, each row with Play all), and playlists at the end.
 */
class MusicHome(
    /** Plays songs in the Music player: the list, where to start, its name, and "radio" (similar songs after it). */
    private val play: (songs: List<VideoItem>, index: Int, from: String, radio: Boolean) -> Unit,
    private val songMenu: (VideoItem) -> Unit,
    private val openPlaylist: (VideoItem) -> Unit,
    private val playPlaylist: (VideoItem) -> Unit,
    private val downloadPlaylist: (VideoItem) -> Unit,
    /** A mood chip was picked (null = back to the Music home): load its rows. */
    private val onMood: (String?) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** The mood chip that is on (null = Music home). */
    var mood: String? = null
        private set

    private var sections: List<MusicSection> = emptyList()
    private var loading = false
    /** The song playing now (its cover shows the equalizer). */
    private var nowId: String? = null
    private val pool = RecyclerView.RecycledViewPool()

    fun submit(list: List<MusicSection>, stillLoading: Boolean) {
        sections = list
        loading = stillLoading
        notifyDataSetChanged()
    }

    /** Marks the song playing now in every row. */
    fun setNowPlaying(url: String?) {
        val id = youtubeId(url)
        if (id == nowId) return
        nowId = id
        notifyDataSetChanged()
    }

    override fun getItemCount() = 1 + sections.size + if (loading) 2 else 0

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_MOODS
        position - 1 < sections.size -> when (sections[position - 1].kind) {
            MusicSection.SONGS -> TYPE_SONGS
            MusicSection.COVERS -> TYPE_COVERS
            else -> TYPE_PLAYLISTS
        }
        else -> TYPE_SKELETON
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MOODS -> Moods(inf.inflate(R.layout.item_music_moods, parent, false))
            TYPE_SKELETON -> Skeleton(inf.inflate(R.layout.item_music_skeleton, parent, false))
            else -> Shelf(inf.inflate(R.layout.item_music_shelf, parent, false), viewType)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is Moods -> holder.bind()
            is Shelf -> holder.bind(sections[position - 1])
        }
    }

    // ---------- mood chips ----------

    private inner class Moods(v: View) : RecyclerView.ViewHolder(v) {
        private val group: ChipGroup = v.findViewById(R.id.moodChips)
        private var building = false

        init {
            val inf = LayoutInflater.from(v.context)
            YtCatalog.MUSIC_MOODS.forEach { (name, _) ->
                val chip = inf.inflate(R.layout.item_mood_chip, group, false) as Chip
                chip.id = View.generateViewId()
                chip.text = name
                chip.tag = name
                group.addView(chip)
            }
            group.setOnCheckedStateChangeListener { g, ids ->
                if (building) return@setOnCheckedStateChangeListener
                val picked = ids.firstOrNull()?.let { id -> g.findViewById<Chip>(id)?.tag as? String }
                if (picked == mood) return@setOnCheckedStateChangeListener
                mood = picked
                onMood(picked)
            }
        }

        fun bind() {
            building = true
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as Chip
                chip.isChecked = chip.tag == mood
            }
            building = false
        }
    }

    private class Skeleton(v: View) : RecyclerView.ViewHolder(v) {
        init {
            val rows: LinearLayout = v.findViewById(R.id.skRows)
            val inf = LayoutInflater.from(v.context)
            repeat(4) { rows.addView(inf.inflate(R.layout.item_music_skeleton_row, rows, false)) }
            v.startAnimation(android.view.animation.AlphaAnimation(1f, 0.45f).apply {
                duration = 750
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
            })
        }
    }

    // ---------- rows ----------

    private inner class Shelf(v: View, private val type: Int) : RecyclerView.ViewHolder(v) {
        private val label: TextView = v.findViewById(R.id.shelfLabel)
        private val title: TextView = v.findViewById(R.id.shelfTitle)
        private val playAll: MaterialButton = v.findViewById(R.id.shelfPlayAll)
        private val list: RecyclerView = v.findViewById(R.id.shelfList)
        private val songs = SongCells()
        private val covers = CoverCells()
        private val playlists = PlaylistCells()
        private var section: MusicSection? = null

        init {
            list.setRecycledViewPool(pool)
            list.layoutManager = when (type) {
                TYPE_SONGS -> GridLayoutManager(v.context, 4, GridLayoutManager.HORIZONTAL, false)
                else -> LinearLayoutManager(v.context, LinearLayoutManager.HORIZONTAL, false)
            }
            list.adapter = when (type) {
                TYPE_SONGS -> songs
                TYPE_COVERS -> covers
                else -> playlists
            }
            playAll.setOnClickListener {
                val s = section ?: return@setOnClickListener
                if (s.items.isNotEmpty()) play(s.items, 0, s.title, false)
            }
        }

        fun bind(s: MusicSection) {
            val changed = section !== s
            section = s
            title.text = s.title
            label.text = labelFor(s)
            label.isVisible = label.text.isNotEmpty()
            playAll.isVisible = type == TYPE_SONGS && s.items.size > 1
            when (type) {
                TYPE_SONGS -> {
                    (list.layoutManager as GridLayoutManager).spanCount = s.items.size.coerceIn(1, 4)
                    songs.submit(s)
                }
                TYPE_COVERS -> covers.submit(s)
                else -> playlists.submit(s.items)
            }
            if (changed) list.scrollToPosition(0)
        }
    }

    /** Small grey words above some rows, like YouTube Music. */
    private fun labelFor(s: MusicSection): String = when {
        s.title == QUICK_PICKS -> "Start radio from a song"
        s.title == LISTEN_AGAIN -> "Your recent songs"
        else -> ""
    }

    // songs in columns of four: each column is a bit narrower than the screen, so the next one peeks in
    private inner class SongCells : RecyclerView.Adapter<SongCell>() {
        private var section: MusicSection? = null

        fun submit(s: MusicSection) {
            section = s
            notifyDataSetChanged()
        }

        override fun getItemCount() = section?.items?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongCell {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song_row, parent, false)
            val d = parent.resources.displayMetrics
            val full = parent.width.takeIf { it > 0 } ?: d.widthPixels
            v.layoutParams = RecyclerView.LayoutParams((full * 0.86f).toInt().coerceAtMost((420 * d.density).toInt()), (64 * d.density).toInt())
            return SongCell(v)
        }

        override fun onBindViewHolder(holder: SongCell, position: Int) {
            val s = section ?: return
            holder.bind(s.items[position])
        }

        override fun getItemViewType(position: Int) = CELL_SONG
    }

    private inner class SongCell(v: View) : RecyclerView.ViewHolder(v) {
        private val art: ImageView = v.findViewById(R.id.songArt)
        private val now: ImageView = v.findViewById(R.id.songNow)
        private val title: TextView = v.findViewById(R.id.songTitle)
        private val sub: TextView = v.findViewById(R.id.songSub)
        private val more: ImageButton = v.findViewById(R.id.songMore)

        init {
            v.findViewById<View>(R.id.songArtBox).clipToOutline = true
        }

        fun bind(song: VideoItem) {
            Img.loadSquare(art, MusicArt.small(song.url, song.thumb), widthPx = 240)
            title.text = song.title
            sub.text = songLine(song)
            val playing = nowId != null && youtubeId(song.url) == nowId
            now.isVisible = playing
            itemView.setOnClickListener { play(listOf(song), 0, song.title, true) }
            itemView.setOnLongClickListener { songMenu(song); true }
            more.setOnClickListener { songMenu(song) }
            itemView.contentDescription = "${song.title}, ${song.uploader}"
        }
    }

    private inner class CoverCells : RecyclerView.Adapter<CoverCell>() {
        private var section: MusicSection? = null

        fun submit(s: MusicSection) {
            section = s
            notifyDataSetChanged()
        }

        override fun getItemCount() = section?.items?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CoverCell(LayoutInflater.from(parent.context).inflate(R.layout.item_music_card, parent, false))

        override fun onBindViewHolder(holder: CoverCell, position: Int) {
            val s = section ?: return
            holder.bind(s.items[position])
        }

        override fun getItemViewType(position: Int) = CELL_COVER
    }

    private inner class CoverCell(v: View) : RecyclerView.ViewHolder(v) {
        private val art: ImageView = v.findViewById(R.id.mcArt)
        private val title: TextView = v.findViewById(R.id.mcTitle)
        private val sub: TextView = v.findViewById(R.id.mcSub)

        init {
            v.findViewById<View>(R.id.mcBox).clipToOutline = true
        }

        fun bind(song: VideoItem) {
            Img.loadSquare(art, MusicArt.small(song.url, song.thumb), widthPx = 400,
                fallbacks = listOfNotNull(youtubeId(song.url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }))
            title.text = song.title
            sub.text = song.uploader
            itemView.setOnClickListener { play(listOf(song), 0, song.title, true) }
            itemView.setOnLongClickListener { songMenu(song); true }
        }
    }

    private inner class PlaylistCells : RecyclerView.Adapter<PlaylistCell>() {
        private var items: List<VideoItem> = emptyList()

        fun submit(list: List<VideoItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PlaylistCell(LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_card, parent, false))

        override fun onBindViewHolder(holder: PlaylistCell, position: Int) = holder.bind(items[position])

        override fun getItemViewType(position: Int) = CELL_PLAYLIST
    }

    private inner class PlaylistCell(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val title: TextView = v.findViewById(R.id.cardTitle)
        private val sub: TextView = v.findViewById(R.id.cardSub)
        private val download: ImageButton = v.findViewById(R.id.cardDownload)
        private val playBtn: ImageButton = v.findViewById(R.id.cardPlay)

        init {
            v.findViewById<View>(R.id.coverBox).clipToOutline = true
        }

        fun bind(item: VideoItem) {
            Img.load(cover, item.thumb, widthPx = 400)
            title.text = item.title
            sub.text = when {
                item.uploader.isNotBlank() -> item.uploader
                item.count > 0 -> "${item.count} songs"
                else -> "Playlist"
            }
            itemView.setOnClickListener { openPlaylist(item) }
            playBtn.setOnClickListener { playPlaylist(item) }
            download.setOnClickListener { downloadPlaylist(item) }
        }
    }

    companion object {
        private const val TYPE_MOODS = 0
        private const val TYPE_SONGS = 1
        private const val TYPE_COVERS = 2
        private const val TYPE_PLAYLISTS = 3
        private const val TYPE_SKELETON = 4
        // cells inside the rows (all rows share one pool of cells, so each kind needs its own number)
        private const val CELL_SONG = 10
        private const val CELL_COVER = 11
        private const val CELL_PLAYLIST = 12

        const val QUICK_PICKS = "Quick picks"
        const val LISTEN_AGAIN = "Listen again"

        /** "Artist • 3:24" (or "• 107M plays" when YouTube gives the plays). */
        fun songLine(song: VideoItem): String {
            val parts = mutableListOf<String>()
            if (song.uploader.isNotBlank()) parts += song.uploader
            when {
                song.views > 0 -> parts += YtCatalog.count(song.views) + " plays"
                song.seconds > 0 -> parts += YtCatalog.duration(song.seconds)
            }
            return parts.joinToString(" • ")
        }
    }
}
