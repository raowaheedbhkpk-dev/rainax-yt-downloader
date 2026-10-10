package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rainax.ytdownloader.databinding.ActivityShortsBinding
import com.rainax.ytdownloader.databinding.ItemShortPageBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Shorts list: filled from YouTube's search for short videos, page by page, with a few topics for variety. */
object ShortsFeed {
    /** Read and changed on the main thread only. */
    val items = mutableListOf<VideoItem>()

    private val seen: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var next: String? = null
    private var topic = 0
    /** Topics in a new random order every time the app starts (a different mix each time). */
    private val topics = listOf(
        "shorts", "funny shorts", "music shorts", "satisfying", "cricket shorts", "food shorts",
        "travel shorts", "comedy", "cute animals", "trending shorts", "dance shorts", "football shorts",
        "magic tricks", "cooking shorts", "pakistan shorts", "india shorts"
    ).shuffled()

    /** Blocking. The next Shorts not shown yet, in random order (add them to [items] on the main thread). */
    @Synchronized
    fun fetchMore(): List<VideoItem> {
        repeat(4) {
            val q = topics[topic % topics.size]
            // YouTube's own "Shorts" search filter first; the normal search if that one fails
            val (list, token) = runCatching { ShortsSearch.search(q, next).let { it.items to it.next } }.getOrNull()
                ?.takeIf { it.first.isNotEmpty() }
                ?: (runCatching { YtCatalog.shorts(q, null).items }.getOrDefault(emptyList()) to null)
            next = token
            if (next == null || it >= 1) { topic++; next = null }          // next time: another topic (variety)
            val fresh = list.filter { v -> seen.add(youtubeId(v.url) ?: v.url) }.shuffled()
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    /** A Short opened from a list: it plays first, the feed follows. */
    fun putFirst(item: VideoItem) {
        val id = youtubeId(item.url)
        items.removeAll { youtubeId(it.url) == id }
        items.add(0, item)
        id?.let { seen.add(it) }
    }
}

/**
 * Shorts: one full-screen video at a time, swipe up for the next (like YouTube Shorts). Each Short repeats,
 * tap to pause, and has Like/Dislike counts, Comments, Download and Share.
 */
@OptIn(UnstableApi::class)
class ShortsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }


    private lateinit var b: ActivityShortsBinding
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private val adapter = PageAdapter()
    /** This screen's own list (the Home strip may change the shared one while it is open). */
    private val items = mutableListOf<VideoItem>()
    private val details = HashMap<String, VideoDetails>()
    private val loadingDetails = HashSet<String>()
    private val failed = HashSet<String>()
    private var current = -1
    private var loadingMore = false
    private var bottomInset = 0
    private var userPaused = false
    /** Your like / dislike per video id ("LIKE", "DISLIKE", "INDIFFERENT"). */
    private val rating = HashMap<String, String>()
    private val likeCounts = HashMap<String, Long>()
    private val dislikeCounts = HashMap<String, Long>()

    private val signIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Toast.makeText(this, "Signed in to YouTube", Toast.LENGTH_SHORT).show()
            holder(current)?.let { h -> items.getOrNull(current)?.let { h.loadState(it.url) } }
        }
    }

    private fun askSignIn() {
        Toast.makeText(this, "Sign in to YouTube to like and comment", Toast.LENGTH_SHORT).show()
        signIn.launch(Intent(this, SignInActivity::class.java))
    }

    /** Like / dislike (tap again to take it back), saved to your YouTube account. */
    private fun rate(url: String, want: String) {
        if (!YtAccount.isSignedIn(this)) { askSignIn(); return }
        val id = youtubeId(url) ?: return
        fun move(from: String, to: String) {
            if (from == "LIKE") likeCounts[id] = ((likeCounts[id] ?: 0L) - 1).coerceAtLeast(0)
            if (from == "DISLIKE") dislikeCounts[id] = ((dislikeCounts[id] ?: 0L) - 1).coerceAtLeast(0)
            if (to == "LIKE") likeCounts[id] = (likeCounts[id] ?: 0L).coerceAtLeast(0) + 1
            if (to == "DISLIKE") dislikeCounts[id] = (dislikeCounts[id] ?: 0L).coerceAtLeast(0) + 1
            rating[id] = to
            holder(current)?.showRating()
        }
        lifecycleScope.launch {
            // know your current like first (tapping Like on a video you already liked takes it back)
            if (!rating.containsKey(id)) {
                val st = withContext(Dispatchers.IO) { runCatching { YtAccount.videoState(id) }.getOrNull() }
                rating[id] = st?.likeStatus ?: "INDIFFERENT"
            }
            val before = rating[id] ?: "INDIFFERENT"
            val now = if (before == want) "INDIFFERENT" else want
            move(before, now)
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.rate(id, now) }.isSuccess }
            if (!ok) {
                move(now, before)
                Toast.makeText(this@ShortsActivity, "Couldn't save that. Try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items.addAll(ShortsFeed.items)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityShortsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.isForceDarkAllowed = false
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.shortsTop.updatePadding(top = bars.top)
            if (bottomInset != bars.bottom) {
                bottomInset = bars.bottom
                // only move the text up on the pages already shown (a full refresh would restart them)
                (b.pager.getChildAt(0) as? RecyclerView)?.let { rv ->
                    for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? PageVH)?.padInfo()
                }
            }
            insets
        }
        b.shortsBack.setOnClickListener { finish() }

        playerView = layoutInflater.inflate(R.layout.view_short_player, b.root, false) as PlayerView
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(RxMediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true                                   // the other player (video page, music) pauses
            )
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 700, 1_500)
                    .build()
            )
            .build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() = holder(current)?.started() ?: Unit

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) holder(current)?.started()
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = items.getOrNull(current) ?: return
                // expired or refused address: once more with a fresh lookup, then give up on this one
                if (failed.add(item.url)) {
                    details.remove(item.url)
                    FastExtractor.forget(item.url)
                    loadAndPlay(current)
                } else {
                    holder(current)?.showError()
                }
            }
        })
        player = p
        playerView.player = p

        b.pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        b.pager.offscreenPageLimit = 1
        b.pager.adapter = adapter
        b.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = select(position)
        })
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isEmpty()) loadMore() else b.pager.setCurrentItem(start, false)
        b.pager.post { if (current < 0 && items.isNotEmpty()) select(b.pager.currentItem) }
    }

    private fun holder(pos: Int): PageVH? =
        (b.pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? PageVH

    /** A Short came on screen: move the video surface there and play it. */
    private fun select(pos: Int) {
        if (pos == current || pos !in items.indices) return
        current = pos
        userPaused = false
        player?.run { stop(); clearMediaItems() }
        attachSurface(pos)
        loadAndPlay(pos)
        // the next Short is looked up now, so it starts at once
        items.getOrNull(pos + 1)?.let { prefetch(it.url) }
        if (pos >= items.size - 4) loadMore()
    }

    private fun attachSurface(pos: Int) {
        val h = holder(pos)
        if (h == null) {
            b.pager.post { if (current == pos) attachSurface(pos) }
            return
        }
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        h.b.spVideo.addView(playerView)
    }

    private fun prefetch(url: String) {
        if (details.containsKey(url) || !loadingDetails.add(url)) return
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { YtCatalog.video(url) }.getOrNull() }
            loadingDetails.remove(url)
            if (d != null) details[url] = d
        }
    }

    private fun loadAndPlay(pos: Int) {
        val item = items.getOrNull(pos) ?: return
        details[item.url]?.let { play(pos, it); return }
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { YtCatalog.video(item.url) }.getOrNull() }
            if (d != null) details[item.url] = d
            if (current != pos) return@launch
            if (d == null) holder(pos)?.showError() else play(pos, d)
        }
    }

    /** Short videos from this Short's "up next" list join the feed (so there is always more to swipe). */
    private fun addRelated(d: VideoDetails) {
        val known = items.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
        val more = d.related.filter {
            !it.isPlaylist && !it.isChannel && (it.isShort || it.seconds in 1..180) && known.add(youtubeId(it.url) ?: it.url)
        }
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        adapter.notifyItemRangeInserted(start, more.size)
    }

    private fun play(pos: Int, d: VideoDetails) {
        val p = player ?: return
        holder(pos)?.fill(d)
        addRelated(d)
        val src = d.play
        val uri = when {
            src.video != null && src.audio != null -> Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", src.video).appendQueryParameter("a", src.audio)
                .appendQueryParameter("u", d.url).build()
            src.muxed != null -> Uri.parse(src.muxed)
            src.hls != null -> Uri.Builder().scheme("rainax").authority("hls")
                .appendQueryParameter("h", src.hls).appendQueryParameter("u", d.url).build()
            else -> { holder(pos)?.showError(); return }
        }
        p.setMediaItem(MediaItem.Builder().setMediaId(d.url).setUri(uri).build())
        p.prepare()
        if (!userPaused) p.play()
    }

    private fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { ShortsFeed.fetchMore() }.getOrDefault(emptyList()) }
            loadingMore = false
            val known = items.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
            val fresh = found.filter { known.add(youtubeId(it.url) ?: it.url) }
            if (fresh.isEmpty()) {
                if (items.isEmpty()) {
                    val text = if (!Net.online(this@ShortsActivity)) NO_INTERNET else "Couldn't load Shorts. Try again in a moment"
                    Toast.makeText(this@ShortsActivity, text, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val start = items.size
            items.addAll(fresh)
            ShortsFeed.items.addAll(found.filter { f -> ShortsFeed.items.none { it.url == f.url } })
            adapter.notifyItemRangeInserted(start, fresh.size)
            if (current < 0) select(b.pager.currentItem)
        }
    }

    private fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) {
            userPaused = true
            p.pause()
        } else {
            userPaused = false
            p.play()
        }
        holder(current)?.b?.spPaused?.isVisible = userPaused
    }

    /** Download: this Short's real formats with their sizes (looked up first, nothing made up). */
    private fun chooseDownload(item: VideoItem) {
        val wait = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Download this Short")
            .setMessage("Getting formats and sizes…")
            .setNegativeButton("Cancel", null)
            .show()
        var looked = false
        val job = lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching { FastExtractor.fetch(item.url) }.getOrElse { PreviewState(error = friendlyError(it.message ?: "Couldn't read this Short")) }
            }
            if (!wait.isShowing) return@launch
            looked = true
            wait.dismiss()
            val choices = st.all.ifEmpty { st.quick }
            if (st.error != null || choices.isEmpty()) {
                Toast.makeText(this@ShortsActivity, st.error ?: "No formats found for this Short", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = choices.map { c -> if (c.size.isNotBlank()) "${c.title}   •   ${c.size}" else c.title }
            MaterialAlertDialogBuilder(this@ShortsActivity, R.style.ThemeOverlay_Rainax_Dialog)
                .setTitle("Download this Short")
                .setItems(labels.toTypedArray()) { _, which ->
                    val title = st.title?.takeIf { it.isNotBlank() } ?: details[item.url]?.title ?: item.title
                    Downloader.enqueueAsync(applicationContext, listOf(EnqueueItem(item.url, title, null, item.thumb)), choices[which].spec, null, null)
                    Toast.makeText(this@ShortsActivity, "Added to downloads", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        wait.setOnDismissListener { if (!looked) job.cancel() }      // Cancel tapped: stop looking
    }

    private fun share(item: VideoItem) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, listOf(item.title, item.url).filter { it.isNotBlank() }.joinToString("\n"))
        runCatching { startActivity(Intent.createChooser(send, "Share Short")) }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (current >= 0 && !userPaused) player?.play()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---------- pages ----------

    private inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PageVH(ItemShortPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PageVH, position: Int) = holder.bind(items[position], position)

        override fun onViewRecycled(holder: PageVH) {
            if (playerView.parent === holder.b.spVideo) holder.b.spVideo.removeView(playerView)
        }
    }

    private inner class PageVH(val b: ItemShortPageBinding) : RecyclerView.ViewHolder(b.root) {
        private var url: String? = null

        fun bind(item: VideoItem, pos: Int) {
            url = item.url
            padInfo()
            // tall picture, same size as the video will be (no small picture that then jumps bigger)
            val id = youtubeId(item.url)
            Img.loadPortrait(b.spThumb, listOfNotNull(
                id?.let { "https://i.ytimg.com/vi/$it/oar2.jpg" },
                id?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" },
                id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                item.thumb
            ))
            b.spThumb.isVisible = true
            b.spLoading.isVisible = true
            b.spError.isVisible = false
            b.spPaused.isVisible = false
            b.spTitle.text = item.title
            b.spUploader.text = item.uploader
            details[item.url]?.let { fill(it) }
            b.root.setOnClickListener { if (pos == current) togglePause() }
            b.spComments.setOnClickListener {
                CommentsSheet(this@ShortsActivity, item.url, { askSignIn() }).show()
            }
            b.spDownload.setOnClickListener { chooseDownload(item) }
            b.spShare.setOnClickListener { share(item) }
            b.spLike.setOnClickListener { rate(item.url, "LIKE") }
            b.spDislike.setOnClickListener { rate(item.url, "DISLIKE") }
            showRating()
            loadState(item.url)
            if (pos == current) {
                if (playerView.parent == null) b.spVideo.addView(playerView)
                // the playing Short shown again (e.g. after a refresh): no picture or spinner over it
                if ((player?.playbackState ?: Player.STATE_IDLE) == Player.STATE_READY) started()
                b.spPaused.isVisible = userPaused
            } else if (playerView.parent === b.spVideo) {
                b.spVideo.removeView(playerView)
            }
        }

        fun padInfo() {
            b.spInfo.updatePadding(bottom = bottomInset + (20 * resources.displayMetrics.density).toInt())
        }

        fun fill(d: VideoDetails) {
            if (youtubeId(d.url) != youtubeId(url)) return
            if (d.title.isNotBlank()) b.spTitle.text = d.title
            if (d.uploader.isNotBlank()) b.spUploader.text = d.uploader
            val id = youtubeId(d.url) ?: return
            if (d.likes >= 0 && !likeCounts.containsKey(id)) likeCounts[id] = d.likes
            showRating()
            if (dislikeCounts.containsKey(id)) return
            lifecycleScope.launch {
                val (likes, n) = withContext(Dispatchers.IO) { Dislikes.votes(id) }
                if (likes > 0 && (likeCounts[id] ?: 0L) <= 0L) likeCounts[id] = likes
                if (n >= 0 && !dislikeCounts.containsKey(id)) {
                    dislikeCounts[id] = n + if (rating[id] == "DISLIKE" && n == 0L) 1 else 0
                    if (youtubeId(url) == id) showRating()
                }
            }
        }

        /** Counts and your own like/dislike (coloured) on the buttons. */
        fun showRating() {
            val id = youtubeId(url) ?: return
            val likes = likeCounts[id] ?: -1
            val dislikes = dislikeCounts[id] ?: -1
            b.spLikeText.text = if (likes > 0) YtCatalog.count(likes) else "Like"
            b.spDislikeText.text = if (dislikes > 0) YtCatalog.count(dislikes) else "Dislike"
            val mine = rating[id]
            val on = androidx.core.content.ContextCompat.getColor(this@ShortsActivity, R.color.rx_primary)
            val off = androidx.core.content.ContextCompat.getColor(this@ShortsActivity, R.color.rx_white)
            b.spLike.imageTintList = android.content.res.ColorStateList.valueOf(if (mine == "LIKE") on else off)
            b.spDislike.imageTintList = android.content.res.ColorStateList.valueOf(if (mine == "DISLIKE") on else off)
        }

        /** Signed in: whether you already liked or disliked this Short. */
        fun loadState(pageUrl: String) {
            val id = youtubeId(pageUrl) ?: return
            if (!YtAccount.isSignedIn(this@ShortsActivity) || rating.containsKey(id)) return
            lifecycleScope.launch {
                val st = withContext(Dispatchers.IO) { runCatching { YtAccount.videoState(id) }.getOrNull() } ?: return@launch
                if (rating.containsKey(id)) return@launch
                rating[id] = st.likeStatus ?: "INDIFFERENT"
                if (youtubeId(url) == id) showRating()
            }
        }

        fun started() {
            b.spThumb.isVisible = false
            b.spLoading.isVisible = false
            b.spError.isVisible = false
        }

        fun showError() {
            b.spLoading.isVisible = false
            b.spError.isVisible = true
        }
    }

    companion object {
        private const val EXTRA_INDEX = "index"

        /** Opens the Shorts feed at [index]. */
        fun open(context: Context, index: Int) {
            context.startActivity(Intent(context, ShortsActivity::class.java).putExtra(EXTRA_INDEX, index))
        }

        /** Opens [item] first (a Short tapped in a list), then the feed. */
        fun open(context: Context, item: VideoItem) {
            ShortsFeed.putFirst(item)
            open(context, 0)
        }
    }
}
