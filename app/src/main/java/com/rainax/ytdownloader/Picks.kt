package com.rainax.ytdownloader

/**
 * Videos picked with + (Home, search, channels, the video page's Up next and Mix list), to download them
 * together. One list for the whole app, so picks made on the video page and on Home add up.
 */
object Picks {
    private val map = LinkedHashMap<String, VideoItem>()
    private val listeners = mutableListOf<() -> Unit>()

    val size get() = map.size

    fun has(url: String) = map.containsKey(url)

    fun all(): List<VideoItem> = map.values.toList()

    fun toggle(item: VideoItem) {
        if (map.remove(item.url) == null) map[item.url] = item
        changed()
    }

    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        changed()
    }

    /** A new main screen is being built (e.g. theme change): forget the old screen's listeners. */
    fun clearListeners() = listeners.clear()

    fun unlisten(l: () -> Unit) {
        listeners.remove(l)
    }

    /** [l] runs on every change (the bars and + buttons update). */
    fun listen(l: () -> Unit) {
        listeners += l
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/**
 * The picked videos as a list (tap the "N selected" text on the pick bar): see what is picked, remove any
 * with ✕, clear all, or download them.
 */
object PicksSheet {

    fun show(act: androidx.appcompat.app.AppCompatActivity, onDownload: (List<VideoItem>) -> Unit) {
        if (Picks.size == 0) return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
        val dp = act.resources.displayMetrics.density
        val text = androidx.core.content.ContextCompat.getColor(act, R.color.rx_text)
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        val head = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
        }
        val title = android.widget.TextView(act).apply {
            setTextColor(text)
            textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        val clear = android.widget.TextView(act).apply {
            this.text = "Clear all"
            setTextColor(androidx.core.content.ContextCompat.getColor(act, R.color.rx_primary))
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            val tv = android.util.TypedValue()
            act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        head.addView(title, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(clear)
        val list = androidx.recyclerview.widget.RecyclerView(act)
        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(act)
        val download = com.google.android.material.button.MaterialButton(act).apply {
            setIconResource(R.drawable.ic_download)
            isAllCaps = false
        }

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            var items: List<VideoItem> = Picks.all()
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_song_row, parent, false)
                v.findViewById<android.view.View>(R.id.songArtBox).clipToOutline = true
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                val v = holder.itemView
                Img.load(v.findViewById(R.id.songArt), item.thumb, widthPx = 240)
                v.findViewById<android.widget.TextView>(R.id.songTitle).text = item.title.ifBlank { item.url }
                v.findViewById<android.widget.TextView>(R.id.songSub).text = listOf(item.uploader, YtCatalog.duration(item.seconds))
                    .filter { it.isNotBlank() }.joinToString(" • ")
                val remove = v.findViewById<android.widget.ImageButton>(R.id.songMore)
                remove.setImageResource(R.drawable.ic_close)
                remove.contentDescription = "Remove from selection"
                remove.setOnClickListener { Picks.toggle(item) }      // the list updates through the listener below
            }
        }
        list.adapter = adapter

        fun refresh() {
            val n = Picks.size
            if (n == 0) { dialog.dismiss(); return }
            adapter.items = Picks.all()
            adapter.notifyDataSetChanged()
            title.text = if (n == 1) "1 video selected" else "$n videos selected"
            download.text = if (n > 1) "Download $n" else "Download"
        }
        val listener: () -> Unit = { if (dialog.isShowing) refresh() }
        Picks.listen(listener)
        dialog.setOnDismissListener { Picks.unlisten(listener) }

        clear.setOnClickListener { Picks.clear() }
        download.setOnClickListener {
            val all = Picks.all()
            dialog.dismiss()
            if (all.isNotEmpty()) {
                Picks.clear()
                onDownload(all)
            }
        }

        box.addView(head)
        val maxH = (act.resources.displayMetrics.heightPixels * 0.55).toInt()
        val rowH = (64 * dp).toInt()
        box.addView(list, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, minOf(maxH, rowH * Picks.size)))
        box.addView(download, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply {
            setMargins((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), 0)
        })
        dialog.setContentView(box)
        refresh()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}
