package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.SheetCommentsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * All comments of a video: more load while scrolling, "View replies" opens a comment's replies,
 * and signed-in users can write a comment.
 */
class CommentsSheet(
    private val act: AppCompatActivity,
    private val videoUrl: String,
    private val onSignIn: () -> Unit
) {
    private val sb = SheetCommentsBinding.inflate(act.layoutInflater)
    private val dialog = BottomSheetDialog(act)
    private val rows = mutableListOf<Comment>()
    private val openReplies = HashSet<Comment>()
    private var next: Page? = null
    private var loading: Job? = null
    private val adapter = Adapter()

    fun show() {
        dialog.setContentView(sb.root)
        // nearly full screen, like YouTube
        sb.root.layoutParams?.let {
            it.height = (act.resources.displayMetrics.heightPixels * 0.85).toInt()
            sb.root.layoutParams = it
        }
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        sb.commentList.layoutManager = LinearLayoutManager(act)
        sb.commentList.adapter = adapter
        sb.commentList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })

        val signedIn = YtAccount.isSignedIn(act)
        Img.load(sb.commentMe, YtAccount.profile(act)?.avatar, circle = true, widthPx = 96)
        sb.commentInput.isFocusable = signedIn
        sb.commentInput.isFocusableInTouchMode = signedIn
        sb.commentInput.hint = if (signedIn) "Add a comment…" else "Sign in to comment"
        sb.commentInput.setOnClickListener { if (!YtAccount.isSignedIn(act)) { dialog.dismiss(); onSignIn() } }
        sb.commentSend.setOnClickListener { post() }

        dialog.show()
        loadFirst()
    }

    private fun loadFirst() {
        sb.commentLoading.isVisible = true
        loading = act.lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(videoUrl, null) }.getOrNull() }
            sb.commentLoading.isVisible = false
            when {
                page == null -> empty("Couldn't load comments. Check your internet")
                page.disabled -> empty("Comments are turned off")
                page.items.isEmpty() -> empty("No comments yet")
                else -> {
                    if (page.total > 0) sb.commentCount.text = YtCatalog.count(page.total.toLong())
                    rows.addAll(page.items)
                    next = page.next
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun empty(text: String) {
        sb.commentEmpty.text = text
        sb.commentEmpty.isVisible = true
    }

    private fun loadMore() {
        val page = next ?: return
        if (loading?.isActive == true) return
        loading = act.lifecycleScope.launch {
            val more = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(videoUrl, page) }.getOrNull() } ?: return@launch
            next = more.next
            val start = rows.size
            rows.addAll(more.items)
            adapter.notifyItemRangeInserted(start, more.items.size)
        }
    }

    /** "View N replies": loads the replies under the comment (tap again to hide them). */
    private fun toggleReplies(c: Comment) {
        val index = rows.indexOf(c)
        if (index < 0) return
        if (c in openReplies) {
            openReplies.remove(c)
            var end = index + 1
            while (end < rows.size && rows[end].isReply) end++
            val count = end - index - 1
            repeat(count) { rows.removeAt(index + 1) }
            adapter.notifyItemRangeRemoved(index + 1, count)
            adapter.notifyItemChanged(index)
            return
        }
        val page = c.replies ?: return
        openReplies.add(c)
        adapter.notifyItemChanged(index)
        act.lifecycleScope.launch {
            val replies = withContext(Dispatchers.IO) {
                runCatching { YtCatalog.comments(videoUrl, page, replies = true) }.getOrNull()
            }?.items.orEmpty()
            val at = rows.indexOf(c)
            if (at < 0 || c !in openReplies) return@launch
            rows.addAll(at + 1, replies)
            adapter.notifyItemRangeInserted(at + 1, replies.size)
        }
    }

    private fun post() {
        if (!YtAccount.isSignedIn(act)) {
            dialog.dismiss()
            onSignIn()
            return
        }
        val text = sb.commentInput.text.toString().trim()
        if (text.isEmpty()) return
        val id = Regex("(?:[?&]v=|shorts/)([\\w-]{6,})").find(videoUrl)?.groupValues?.get(1) ?: return
        sb.commentSend.isEnabled = false
        act.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { YtAccount.postComment(id, text) }.exceptionOrNull()
            }
            sb.commentSend.isEnabled = true
            if (error != null) {
                Toast.makeText(act, error.message ?: "Couldn't post your comment", Toast.LENGTH_SHORT).show()
                return@launch
            }
            sb.commentInput.setText("")
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(sb.commentInput.windowToken, 0)
            val me = YtAccount.profile(act)
            rows.add(0, Comment(me?.handle ?: me?.name ?: "You", me?.avatar, text, false, null, "just now"))
            adapter.notifyItemInserted(0)
            sb.commentEmpty.isVisible = false
            sb.commentList.scrollToPosition(0)
            Toast.makeText(act, "Comment posted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = rows[position]
            val density = holder.itemView.resources.displayMetrics.density
            holder.itemView.setPaddingRelative(
                ((if (c.isReply) 56 else 16) * density).toInt(), holder.itemView.paddingTop,
                holder.itemView.paddingEnd, holder.itemView.paddingBottom
            )
            Img.load(holder.avatar, c.avatar, circle = true, widthPx = 96)
            holder.author.text = listOfNotNull(c.author.takeIf { it.isNotBlank() }, c.date).joinToString(" • ")
            holder.text.text = plain(c)
            holder.likes.text = c.likes?.let { "👍 $it" }.orEmpty()
            holder.likes.isVisible = c.likes != null
            val canReply = !c.isReply && c.replyCount > 0 && c.replies != null
            holder.replies.isVisible = canReply
            if (canReply) {
                holder.replies.text = if (c in openReplies) "Hide replies"
                else if (c.replyCount == 1) "View 1 reply" else "View ${c.replyCount} replies"
                holder.replies.setOnClickListener { toggleReplies(c) }
            }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.cAvatar)
        val author: TextView = v.findViewById(R.id.cAuthor)
        val text: TextView = v.findViewById(R.id.cText)
        val likes: TextView = v.findViewById(R.id.cLikes)
        val replies: TextView = v.findViewById(R.id.cReplies)
    }
}
