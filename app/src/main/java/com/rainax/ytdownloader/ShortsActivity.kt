package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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

    /** What the screen shows (Compose, ShortsScreen.kt). This screen's own list is ui.items
     *  (the Home strip may change the shared one while it is open). */
    private val ui = ShortsUi()
    private val items get() = ui.items
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private val details = HashMap<String, VideoDetails>()
    private val loadingDetails = HashSet<String>()
    private val failed = HashSet<String>()
    private val current get() = ui.current
    private var loadingMore = false
    private var userPaused = false
    /** Your like / dislike per video id ("LIKE", "DISLIKE", "INDIFFERENT"). */
    private val rating get() = ui.rating
    private val likeCounts get() = ui.likes
    private val dislikeCounts get() = ui.dislikes

    private val signIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Notify.show(this, "Signed in to YouTube")
            items.getOrNull(current)?.let { loadState(it.url) }
        }
    }

    private fun askSignIn() {
        Notify.show(this, "Sign in to YouTube to like and comment")
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
                Notify.show(this@ShortsActivity, "Couldn't save that. Try again")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items.addAll(ShortsFeed.items)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        playerView = layoutInflater.inflate(R.layout.view_short_player, android.widget.FrameLayout(this), false) as PlayerView
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
            override fun onRenderedFirstFrame() = started()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started()
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = items.getOrNull(current) ?: return
                // expired or refused address: once more with a fresh lookup, then give up on this one
                if (failed.add(item.url)) {
                    details.remove(item.url)
                    FastExtractor.forget(item.url)
                    loadAndPlay(current)
                } else {
                    showError(item.url)
                }
            }
        })
        player = p
        playerView.player = p

        ui.start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val actions = ShortsActions(
            back = { finish() },
            select = { select(it) },
            tap = { pos -> if (pos == current) togglePause() },
            like = { rate(it.url, "LIKE") },
            dislike = { rate(it.url, "DISLIKE") },
            comments = { CommentsSheet(this, it.url, { askSignIn() }).show() },
            download = { chooseDownload(it) },
            share = { share(it) },
            nav = { id ->
                // back to the main screen at that place (it is already open underneath)
                startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_NAV, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        )
        setContent { RxTheme { ShortsScreen(ui, playerView, actions) } }
        if (items.isEmpty()) loadMore()
    }

    /** The Short on screen started showing its video: its picture and spinner go. */
    private fun started() {
        items.getOrNull(current)?.let { ui.started[it.url] = true; ui.errors.remove(it.url) }
    }

    private fun showError(url: String) {
        ui.errors[url] = true
    }

    /** A Short came on screen: the video surface moves there (Compose) and it plays. */
    private fun select(pos: Int) {
        if (pos == current || pos !in items.indices) return
        ui.started.clear()                       // the pages show their pictures until their video starts
        ui.errors.remove(items[pos].url)
        ui.current = pos
        userPaused = false
        ui.paused = false
        player?.run { stop(); clearMediaItems() }
        loadAndPlay(pos)
        // the next Short is looked up now, so it starts at once
        items.getOrNull(pos + 1)?.let { prefetch(it.url) }
        loadState(items[pos].url)
        if (pos >= items.size - 4) loadMore()
    }

    private fun prefetch(url: String) {
        if (details.containsKey(url) || !loadingDetails.add(url)) return
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { YtCatalog.video(url) }.getOrNull() }
            loadingDetails.remove(url)
            if (d != null) { details[url] = d; fill(d) }
        }
    }

    private fun loadAndPlay(pos: Int) {
        val item = items.getOrNull(pos) ?: return
        details[item.url]?.let { play(pos, it); return }
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { YtCatalog.video(item.url) }.getOrNull() }
            if (d != null) details[item.url] = d
            if (current != pos) return@launch
            if (d == null) showError(item.url) else play(pos, d)
        }
    }

    /** Short videos from this Short's "up next" list join the feed (so there is always more to swipe). */
    private fun addRelated(d: VideoDetails) {
        val known = items.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
        val more = d.related.filter {
            !it.isPlaylist && !it.isChannel && (it.isShort || it.seconds in 1..180) && known.add(youtubeId(it.url) ?: it.url)
        }
        if (more.isNotEmpty()) items.addAll(more)
    }

    private fun play(pos: Int, d: VideoDetails) {
        val p = player ?: return
        fill(d)
        addRelated(d)
        val src = d.play
        val uri = when {
            src.video != null && src.audio != null -> Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", src.video).appendQueryParameter("a", src.audio)
                .appendQueryParameter("u", d.url).build()
            src.muxed != null -> Uri.parse(src.muxed)
            src.hls != null -> Uri.Builder().scheme("rainax").authority("hls")
                .appendQueryParameter("h", src.hls).appendQueryParameter("u", d.url).build()
            else -> { items.getOrNull(pos)?.let { showError(it.url) }; return }
        }
        p.setMediaItem(MediaItem.Builder().setMediaId(d.url).setUri(uri).build())
        p.prepare()
        if (!userPaused) p.play()
    }

    /** Better title and channel, the like count, and the dislike count (Return YouTube Dislike). */
    private fun fill(d: VideoDetails) {
        val item = items.firstOrNull { youtubeId(it.url) == youtubeId(d.url) } ?: return
        ui.names[item.url] = d.title.ifBlank { item.title } to d.uploader.ifBlank { item.uploader }
        val id = youtubeId(d.url) ?: return
        if (d.likes >= 0 && !likeCounts.containsKey(id)) likeCounts[id] = d.likes
        if (dislikeCounts.containsKey(id)) return
        lifecycleScope.launch {
            val (likes, n) = withContext(Dispatchers.IO) { Dislikes.votes(id) }
            if (likes > 0 && (likeCounts[id] ?: 0L) <= 0L) likeCounts[id] = likes
            if (n >= 0 && !dislikeCounts.containsKey(id)) {
                dislikeCounts[id] = n + if (rating[id] == "DISLIKE" && n == 0L) 1 else 0
            }
        }
    }

    /** Signed in: whether you already liked or disliked this Short. */
    private fun loadState(pageUrl: String) {
        val id = youtubeId(pageUrl) ?: return
        if (!YtAccount.isSignedIn(this) || rating.containsKey(id)) return
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { YtAccount.videoState(id) }.getOrNull() } ?: return@launch
            if (rating.containsKey(id)) return@launch
            rating[id] = st.likeStatus ?: "INDIFFERENT"
        }
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
                    Notify.show(this@ShortsActivity, text, long = true)
                }
                return@launch
            }
            items.addAll(fresh)
            ShortsFeed.items.addAll(found.filter { f -> ShortsFeed.items.none { it.url == f.url } })
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
        ui.paused = userPaused
    }

    /** Download: the same download sheet as everywhere (Video / Audio tabs, quality cards, real sizes). */
    private fun chooseDownload(item: VideoItem) {
        val title = details[item.url]?.title ?: item.title
        QuickSheet.show(this, item.url, title, item.thumb) { Notify.show(this, "Added to downloads") }
    }

    private fun share(item: VideoItem) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, listOf(item.title, item.url).filter { it.isNotBlank() }.joinToString("\n"))
        runCatching { startActivity(Intent.createChooser(send, "Share Short")) }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        if (current >= 0 && !userPaused) player?.play()
        // Shorts play one after another without touching the screen: keep it awake while they are open
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
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
