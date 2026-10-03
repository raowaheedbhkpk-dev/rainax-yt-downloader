package com.rainax.ytdownloader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.ItemVideoHeaderBinding
import com.rainax.ytdownloader.databinding.PageVideoBinding
import com.rainax.ytdownloader.databinding.SheetCommentsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Video page: the in-app player (it plays in BgPlayService, so the sound can continue in the background),
 * Download and audio buttons, comments and related videos (each with its own Download button).
 */
@OptIn(UnstableApi::class)
class VideoScreen(
    private val act: AppCompatActivity,
    private val vb: PageVideoBinding,
    private val vm: MainViewModel,
    private val player: () -> Player?,
    private val download: (url: String, title: String?, audio: Boolean) -> Unit,
    private val onChanged: () -> Unit
) {
    private val header = ItemVideoHeaderBinding.inflate(act.layoutInflater)
    private val related = VideoAdapter(true, { open(it.url, it.title, it.uploader) }, { download(it.url, it.title, false) })

    /** The video shown (null when the page is closed). */
    var url: String? = null
        private set
    private var details: VideoDetails? = null
    private var comments: List<Comment> = emptyList()
    private var job: Job? = null
    private var commentsJob: Job? = null

    var fullscreen = false
        private set

    val isOpen get() = url != null

    fun setup() {
        vb.videoList.layoutManager = LinearLayoutManager(act)
        related.header = header.root
        vb.videoList.adapter = related
        vb.videoClose.setOnClickListener { close() }
        vb.playerView.setFullscreenButtonClickListener { full -> setFullscreen(full) }
        header.vDownload.setOnClickListener { url?.let { download(it, currentTitle(), false) } }
        header.vAudio.setOnClickListener { url?.let { download(it, currentTitle(), true) } }
        header.vShare.setOnClickListener { share() }
        header.vDesc.setOnClickListener {
            header.vDesc.maxLines = if (header.vDesc.maxLines == 3) Int.MAX_VALUE else 3
        }
        header.vComments.setOnClickListener { showComments() }
    }

    fun attach(p: Player?) {
        vb.playerView.player = p
    }

    private fun currentTitle(): String? = details?.title ?: header.vTitle.text?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Shows a video and starts playing it. [resume]: the player already plays this video (back in the app,
     * or the screen was rebuilt), so keep it going instead of starting again.
     */
    fun open(url: String, title: String? = null, uploader: String? = null, startMs: Long = 0, resume: Boolean = false) {
        val clean = FastExtractor.videoUrl(url)
        this.url = clean
        details = null
        comments = emptyList()
        vb.root.isVisible = true
        onChanged()

        header.vTitle.text = title.orEmpty()
        header.vMeta.text = ""
        header.vUploader.text = uploader.orEmpty()
        header.vSubs.text = ""
        Img.load(header.vAvatar, null)
        header.vLoading.isVisible = true
        header.vError.isVisible = false
        header.vComments.isVisible = false
        header.vDesc.isVisible = false
        header.vDesc.maxLines = 3
        header.vUpNext.isVisible = false
        related.submit(emptyList())
        vb.videoList.scrollToPosition(0)
        vm.prefetch(clean)                    // the download sheet then has the sizes at once

        val p = player()
        val keep = resume && p != null && p.mediaItemCount > 0 && p.currentMediaItem?.mediaId == clean &&
            p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_VIDEO) == true
        if (!keep) p?.pause()

        job?.cancel()
        job = act.lifecycleScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { YtCatalog.video(clean) }
                if (this@VideoScreen.url != clean) return@launch
                details = d
                fill(d)
                if (!keep) play(d, startMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@VideoScreen.url != clean) return@launch
                header.vLoading.isVisible = false
                showError((e.message ?: "Couldn't open this video") + "\nYou can still try Download.")
            }
        }
        loadComments(clean)
    }

    /** The player moved on (next track, or a new video started elsewhere): show that video. */
    fun follow(p: Player) {
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isBlank() || id == url) return
        open(id, item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString(), p.currentPosition.coerceAtLeast(0), resume = true)
    }

    fun close() {
        if (fullscreen) exitFullscreen()
        vb.root.isVisible = false
        job?.cancel()
        commentsJob?.cancel()
        url = null
        details = null
        player()?.run {
            stop()
            clearMediaItems()
        }
        onChanged()
    }

    fun showError(text: String) {
        header.vError.text = text
        header.vError.isVisible = true
    }

    // ---------- playback ----------

    private fun play(d: VideoDetails, startMs: Long) {
        val p = player() ?: run { showError("The player is starting. Tap the video again in a moment."); return }
        val src = d.play
        val uri = when {
            src.video != null && src.audio != null -> Uri.Builder().scheme("rainax").authority("av")
                .appendQueryParameter("v", src.video).appendQueryParameter("a", src.audio)
                .appendQueryParameter("u", d.url).build()
            src.hls != null -> Uri.Builder().scheme("rainax").authority("hls")
                .appendQueryParameter("h", src.hls).appendQueryParameter("u", d.url).build()
            src.muxed != null -> Uri.parse(src.muxed)
            else -> {
                showError("This video can't play here, but you can still download it.")
                return
            }
        }
        d.audioUrl?.let { BgPlayService.remember(d.url, it) }
        val item = MediaItem.Builder()
            .setMediaId(d.url)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(d.title)
                    .setArtist(d.uploader)
                    .setArtworkUri(d.thumb?.let { Uri.parse(it) })
                    .setExtras(Bundle().apply { putBoolean(EXTRA_VIDEO, true) })
                    .build()
            )
            .build()
        // "Up next" videos are the next tracks (in the background they play as sound)
        val next = d.related.filter { it.seconds > 0 }.take(25).map { e ->
            val lazy = BgPlayService.lazyUri(e.url)
            MediaItem.Builder()
                .setMediaId(e.url)
                .setUri(lazy)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(lazy).build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(e.title)
                        .setArtist(e.uploader)
                        .setArtworkUri(e.thumb?.let { Uri.parse(it) })
                        .build()
                )
                .build()
        }
        try {
            p.setMediaItems(listOf(item) + next, 0, startMs)
            p.prepare()
            p.play()
        } catch (e: Exception) {
            showError("Couldn't start the video: " + (e.message ?: e.javaClass.simpleName).take(80))
        }
    }

    // ---------- details ----------

    private fun fill(d: VideoDetails) {
        header.vLoading.isVisible = false
        header.vTitle.text = d.title
        val meta = mutableListOf<String>()
        if (d.views >= 0) meta += YtCatalog.count(d.views) + if (d.seconds < 0) " watching" else " views"
        d.uploaded?.takeIf { it.isNotBlank() }?.let { meta += it }
        if (d.likes > 0) meta += YtCatalog.count(d.likes) + " likes"
        header.vMeta.text = meta.joinToString(" • ")
        header.vUploader.text = d.uploader
        header.vSubs.text = if (d.subscribers > 0) YtCatalog.count(d.subscribers) + " subscribers" else ""
        Img.load(header.vAvatar, d.avatar, circle = true, widthPx = 120)
        val desc = d.description.trim()
        if (desc.isNotEmpty()) {
            header.vDesc.text = if (desc.contains('<')) HtmlCompat.fromHtml(desc, HtmlCompat.FROM_HTML_MODE_COMPACT) else desc
            header.vDesc.isVisible = true
        }
        related.submit(d.related)
        header.vUpNext.isVisible = d.related.isNotEmpty()
    }

    private fun loadComments(clean: String) {
        commentsJob?.cancel()
        commentsJob = act.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { YtCatalog.comments(clean) }
            if (url != clean || list.isEmpty()) return@launch
            comments = list
            val first = list.first()
            Img.load(header.vCommentAvatar, first.avatar, circle = true, widthPx = 80)
            header.vCommentText.text = plain(first)
            header.vComments.isVisible = true
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    private fun showComments() {
        if (comments.isEmpty()) return
        val sb = SheetCommentsBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        sb.commentList.layoutManager = LinearLayoutManager(act)
        sb.commentList.adapter = CommentAdapter(comments) { plain(it) }
        dialog.show()
    }

    private fun share() {
        val u = url ?: return
        val text = listOfNotNull(currentTitle(), u).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            act.startActivity(Intent.createChooser(send, "Share video"))
        } catch (e: Exception) { }
    }

    // ---------- fullscreen ----------

    /** Back while fullscreen: press the player's own fullscreen button so its icon stays right. */
    fun exitFullscreen() {
        val btn = vb.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (btn != null && btn.visibility == View.VISIBLE) btn.performClick() else setFullscreen(false)
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        vb.playerBox.fill = on
        val lp = vb.playerBox.layoutParams as LinearLayout.LayoutParams
        lp.height = if (on) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = if (on) 1f else 0f
        vb.playerBox.layoutParams = lp
        vb.videoList.isVisible = !on
        vb.videoClose.isVisible = !on
        act.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (on) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onChanged()
    }

    private class CommentAdapter(
        private val items: List<Comment>,
        private val text: (Comment) -> CharSequence
    ) : RecyclerView.Adapter<CommentAdapter.VH>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            Img.load(holder.avatar, c.avatar, circle = true, widthPx = 96)
            holder.author.text = listOfNotNull(c.author.takeIf { it.isNotBlank() }, c.date).joinToString(" • ")
            holder.text.text = text(c)
            holder.likes.text = c.likes?.let { "👍 $it" }.orEmpty()
            holder.likes.isVisible = c.likes != null
        }
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.cAvatar)
            val author: TextView = v.findViewById(R.id.cAuthor)
            val text: TextView = v.findViewById(R.id.cText)
            val likes: TextView = v.findViewById(R.id.cLikes)
        }
    }

    companion object {
        /** Marks queue entries that carry the picture (the "Up next" ones are sound only until opened). */
        const val EXTRA_VIDEO = "rainax_video"
    }
}
