package com.rainax.ytdownloader

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.rainax.ytdownloader.databinding.SheetCommentsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * All comments of a video, like YouTube: Top / Newest, like and dislike, replies and replying,
 * more comments while scrolling, and writing a comment (signed in). Your own comments come first.
 */
class CommentsSheet(
    private val act: AppCompatActivity,
    private val videoUrl: String,
    private val onSignIn: () -> Unit,
    /** Height of the sheet (0 = most of the screen). The video page passes the space below the video. */
    private val heightPx: Int = 0
) {
    private val sb = SheetCommentsBinding.inflate(act.layoutInflater)
    private val dialog = BottomSheetDialog(act)
    private val rows = mutableListOf<YtComments.Item>()
    private val openReplies = HashSet<String>()                     // comment ids with replies shown
    private val replyJobs = HashMap<String, Job>()                  // replies being loaded, by comment id
    // loading stops when the sheet closes (posting and liking still finish)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private val fallbackReplies = HashMap<String, Page>()          // simple reader: replies page by comment id
    private var full = true                                        // YouTube's own comment reader works for this video
    private var nextToken: String? = null
    private var nextPage: Page? = null
    private var loading: Job? = null
    private var replyTo: YtComments.Item? = null
    private var buildingSort = false
    private val adapter = Adapter()

    val isShowing get() = dialog.isShowing

    fun show() {
        dialog.setContentView(sb.root)
        val tall = heightPx.takeIf { it > act.resources.displayMetrics.heightPixels / 3 }
            ?: (act.resources.displayMetrics.heightPixels * 0.85).toInt()
        if (tall == heightPx) dialog.window?.setDimAmount(0.1f)     // under the video: the video stays bright
        // quick emoji while typing: tap one to put it where the cursor is
        for (i in 0 until sb.commentEmojis.childCount) {
            val v = sb.commentEmojis.getChildAt(i) as? android.widget.TextView ?: continue
            v.setOnClickListener {
                val e = sb.commentInput.text
                val at = sb.commentInput.selectionStart.coerceIn(0, e.length)
                e.insert(at, v.text)
            }
        }
        sb.commentInput.setOnFocusChangeListener { _, focused ->
            sb.commentEmojis.isVisible = focused && YtAccount.isSignedIn(act)
        }
        sb.root.layoutParams?.let {
            it.height = tall
            sb.root.layoutParams = it
        }
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { scope.coroutineContext[Job]?.cancel() }
        // the screen closes or is rebuilt (theme change): close the sheet and stop its loading too
        act.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.coroutineContext[Job]?.cancel()
                runCatching { dialog.dismiss() }
            }
        })
        // typing: the window shrinks above the keyboard and the sheet shrinks with it,
        // so the text box always sits right on top of the keyboard and you see what you type
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        dialog.findViewById<View>(com.google.android.material.R.id.coordinator)
            ?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val want = minOf(tall, v.height).takeIf { it > 0 } ?: return@addOnLayoutChangeListener
                val lp = sb.root.layoutParams ?: return@addOnLayoutChangeListener
                if (lp.height != want) {
                    lp.height = want
                    sb.root.post {
                        sb.root.layoutParams = lp
                        val st = dialog.behavior.state
                        if (st != BottomSheetBehavior.STATE_HIDDEN && st != BottomSheetBehavior.STATE_DRAGGING &&
                            st != BottomSheetBehavior.STATE_SETTLING
                        ) dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }

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
        sb.commentInput.setOnClickListener { if (!YtAccount.isSignedIn(act)) needSignIn() }
        sb.commentSend.setOnClickListener { post() }
        sb.replyCancel.setOnClickListener { setReplyTo(null) }

        dialog.show()
        loadFirst()
    }

    private fun needSignIn() {
        dialog.dismiss()
        onSignIn()
    }

    private fun videoId(): String? = Regex("(?:[?&]v=|shorts/)([\\w-]{6,})").find(videoUrl)?.groupValues?.get(1)

    // ---------- loading ----------

    private fun loadFirst() {
        sb.commentLoading.isVisible = true
        val id = videoId()
        loading = scope.launch {
            // YouTube's own reader first (sorting, likes, replies); the simple reader if it can't read this video
            val rich = withContext(Dispatchers.IO) { runCatching { id?.let { YtComments.first(it) } }.getOrNull() }
            if (rich != null && (rich.items.isNotEmpty() || rich.next != null)) {
                full = true
                show(rich, replace = true)
                buildSort(rich.sorts)
                return@launch
            }
            full = false
            val simple = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(videoUrl, null) }.getOrNull() }
            sb.commentLoading.isVisible = false
            addMine()
            when {
                simple == null -> if (rows.isEmpty()) empty("Couldn't load comments. Check your internet")
                simple.disabled -> empty("Comments are turned off")
                simple.items.isEmpty() -> if (rows.isEmpty()) empty("No comments yet")
                else -> {
                    if (simple.total > 0) sb.commentCount.text = YtCatalog.count(simple.total.toLong())
                    val mine = rows.map { it.text.trim() }.toSet()
                    simple.items.filter { plain(it).toString().trim() !in mine }.forEach { rows += fromSimple(it) }
                    nextPage = simple.next
                }
            }
            adapter.notifyDataSetChanged()
        }
    }

    /** Shows a page from YouTube's reader ([replace] = new list, e.g. another sort order). */
    private fun show(r: YtComments.Result, replace: Boolean) {
        sb.commentLoading.isVisible = false
        if (replace) {
            rows.clear()
            openReplies.clear()
            addMine(r.items)
        }
        r.count?.let { sb.commentCount.text = it }
        val known = rows.mapTo(HashSet()) { it.id }
        val myTexts = rows.filter { it.mine }.map { it.text.trim() }.toSet()
        r.items.filter { it.id !in known && it.text.trim() !in myTexts }.forEach { rows += it }
        nextToken = r.next
        adapter.notifyDataSetChanged()
        sb.commentEmpty.isVisible = rows.isEmpty()
        if (rows.isEmpty()) sb.commentEmpty.text = "No comments yet"
    }

    /** Comments you wrote from RAINAX (YouTube shows yours first to you). */
    private fun addMine(fromYouTube: List<YtComments.Item> = emptyList()) {
        val id = videoId() ?: return
        val seen = fromYouTube.map { it.text.trim() }.toSet()
        MyComments.forVideo(act, id).filter { it.text.trim() !in seen }.forEach { c ->
            rows += YtComments.Item(
                "mine-" + c.text.hashCode(), c.author, c.avatar, c.text, c.date, null, "NONE", 0, null,
                null, null, null, null, null, isReply = false, byCreator = false, mine = true
            )
        }
    }

    private fun fromSimple(c: Comment): YtComments.Item {
        val id = "s-" + System.identityHashCode(c)
        c.replies?.let { fallbackReplies[id] = it }
        return YtComments.Item(
            id, c.author, c.avatar, plain(c).toString(), c.date, c.likes, "NONE", c.replyCount, null,
            null, null, null, null, null, isReply = c.isReply, byCreator = false
        )
    }

    private fun empty(text: String) {
        sb.commentEmpty.text = text
        sb.commentEmpty.isVisible = true
    }

    private fun loadMore() {
        if (loading?.isActive == true) return
        if (full) {
            val token = nextToken ?: return
            loading = scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { YtComments.more(token, false) }.getOrNull() } ?: return@launch
                show(r, replace = false)
            }
        } else {
            val page = nextPage ?: return
            loading = scope.launch {
                val more = withContext(Dispatchers.IO) { runCatching { YtCatalog.comments(videoUrl, page) }.getOrNull() } ?: return@launch
                nextPage = more.next
                val start = rows.size
                more.items.forEach { rows += fromSimple(it) }
                adapter.notifyItemRangeInserted(start, rows.size - start)
            }
        }
    }

    /** Top / Newest chips. */
    private fun buildSort(sorts: List<Pair<String, String>>) {
        if (sorts.size < 2) return
        buildingSort = true
        sb.commentSort.removeAllViews()
        sorts.forEachIndexed { i, (title, token) ->
            val chip = LayoutInflater.from(act).inflate(R.layout.item_chip, sb.commentSort, false) as Chip
            chip.id = View.generateViewId()
            chip.text = title
            chip.tag = token
            sb.commentSort.addView(chip)
            if (i == 0) chip.isChecked = true
        }
        sb.commentSort.setOnCheckedStateChangeListener { group, ids ->
            if (buildingSort) return@setOnCheckedStateChangeListener
            val token = ids.firstOrNull()?.let { group.findViewById<Chip>(it)?.tag as? String } ?: return@setOnCheckedStateChangeListener
            loading?.cancel()
            sb.commentLoading.isVisible = true
            loading = scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { YtComments.more(token, false) }.getOrNull() }
                sb.commentLoading.isVisible = false
                if (r != null) {
                    show(r, replace = true)
                    sb.commentList.scrollToPosition(0)
                }
            }
        }
        sb.commentSort.isVisible = true
        buildingSort = false
    }

    // ---------- replies ----------

    private fun toggleReplies(c: YtComments.Item) {
        val index = rows.indexOf(c)
        if (index < 0) return
        if (c.id in openReplies) {
            openReplies.remove(c.id)
            replyJobs.remove(c.id)?.cancel()            // still loading: never insert them twice
            var end = index + 1
            while (end < rows.size && rows[end].isReply) end++
            val count = end - index - 1
            repeat(count) { rows.removeAt(index + 1) }
            adapter.notifyItemRangeRemoved(index + 1, count)
            adapter.notifyItemChanged(index)
            return
        }
        openReplies.add(c.id)
        adapter.notifyItemChanged(index)
        replyJobs.remove(c.id)?.cancel()
        replyJobs[c.id] = scope.launch {
            val replies = withContext(Dispatchers.IO) {
                runCatching {
                    val token = c.repliesToken
                    val page = fallbackReplies[c.id]
                    when {
                        token != null -> YtComments.more(token, true).items.map { copyAsReply(it) }
                        page != null -> YtCatalog.comments(videoUrl, page, replies = true).items.map { fromSimple(it) }
                        else -> emptyList()
                    }
                }.getOrNull()
            }.orEmpty()
            val at = rows.indexOf(c)
            if (at < 0 || c.id !in openReplies) return@launch
            rows.addAll(at + 1, replies)
            adapter.notifyItemRangeInserted(at + 1, replies.size)
        }
    }

    private fun copyAsReply(i: YtComments.Item) = if (i.isReply) i else YtComments.Item(
        i.id, i.author, i.avatar, i.text, i.date, i.likeCount, i.likeState, 0, null,
        i.likeAction, i.unlikeAction, i.dislikeAction, i.undislikeAction, i.replyParams, isReply = true, byCreator = i.byCreator
    )

    private fun setReplyTo(c: YtComments.Item?) {
        replyTo = c
        sb.replyBar.isVisible = c != null
        sb.replyText.text = c?.let { "Replying to ${it.author}" }.orEmpty()
        sb.commentInput.hint = if (c != null) "Add a reply…" else "Add a comment…"
        if (c != null) {
            sb.commentInput.requestFocus()
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(sb.commentInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------- like / dislike ----------

    private fun rate(c: YtComments.Item, like: Boolean) {
        if (!YtAccount.isSignedIn(act)) { needSignIn(); return }
        val before = c.likeState
        val beforeCount = c.likeCount
        val action = if (like) {
            if (before == "LIKE") c.unlikeAction else c.likeAction
        } else {
            if (before == "DISLIKE") c.undislikeAction else c.dislikeAction
        } ?: run {
            Toast.makeText(act, "YouTube doesn't allow rating this comment", Toast.LENGTH_SHORT).show()
            return
        }
        val now = if (like) (if (before == "LIKE") "NONE" else "LIKE") else (if (before == "DISLIKE") "NONE" else "DISLIKE")
        c.likeState = now
        c.likeCount = bump(beforeCount, (if (now == "LIKE") 1 else 0) - (if (before == "LIKE") 1 else 0))
        rows.indexOf(c).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtComments.act(action) }.isSuccess }
            if (!ok) {
                c.likeState = before
                c.likeCount = beforeCount
                rows.indexOf(c).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
                Toast.makeText(act, "Couldn't save that. Try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** "12" + 1 = "13"; "1.2K" stays as it is. */
    private fun bump(count: String?, by: Int): String? {
        if (by == 0) return count
        val n = count?.trim()?.toIntOrNull() ?: if (count.isNullOrBlank()) 0 else return count
        val v = n + by
        return if (v > 0) v.toString() else null
    }

    // ---------- writing ----------

    private fun post() {
        if (!YtAccount.isSignedIn(act)) { needSignIn(); return }
        val text = sb.commentInput.text.toString().trim()
        if (text.isEmpty()) return
        val id = videoId() ?: return
        val target = replyTo
        val params = target?.replyParams
        if (target != null && params == null) {
            Toast.makeText(act, "Replies can't be sent to this comment", Toast.LENGTH_SHORT).show()
            return
        }
        sb.commentSend.isEnabled = false
        act.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { if (params != null) YtComments.reply(params, text) else YtAccount.postComment(id, text) }.exceptionOrNull()
            }
            sb.commentSend.isEnabled = true
            if (error != null) {
                Toast.makeText(act, error.message ?: "Couldn't post", Toast.LENGTH_SHORT).show()
                return@launch
            }
            sb.commentInput.setText("")
            (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(sb.commentInput.windowToken, 0)
            val me = YtAccount.profile(act)
            val author = me?.handle ?: me?.name ?: "You"
            if (target != null) {
                // the reply shows under the comment
                val at = rows.indexOf(target)
                val item = YtComments.Item(
                    "reply-" + System.currentTimeMillis(), author, me?.avatar, text, "Just now", null, "NONE", 0, null,
                    null, null, null, null, null, isReply = true, byCreator = false, mine = true
                )
                if (at >= 0) {
                    rows.add(at + 1, item)
                    adapter.notifyItemInserted(at + 1)
                }
                setReplyTo(null)
                Toast.makeText(act, "Reply posted", Toast.LENGTH_SHORT).show()
            } else {
                MyComments.add(act, id, text)
                rows.add(0, YtComments.Item(
                    "mine-" + text.hashCode(), author, me?.avatar, text, "Just now", null, "NONE", 0, null,
                    null, null, null, null, null, isReply = false, byCreator = false, mine = true
                ))
                adapter.notifyItemInserted(0)
                sb.commentEmpty.isVisible = false
                sb.commentList.scrollToPosition(0)
                Toast.makeText(act, "Comment posted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    // ---------- list ----------

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
            holder.text.text = c.text

            // like / dislike / reply
            val red = ContextCompat.getColor(act, R.color.rx_primary)
            val normal = ContextCompat.getColor(act, R.color.rx_text)
            holder.likeIcon.imageTintList = ColorStateList.valueOf(if (c.likeState == "LIKE") red else normal)
            holder.dislike.imageTintList = ColorStateList.valueOf(if (c.likeState == "DISLIKE") red else normal)
            holder.likeCount.text = c.likeCount.orEmpty()
            holder.likeCount.isVisible = !c.likeCount.isNullOrBlank()
            val rateable = !c.mine && full
            holder.like.isEnabled = rateable
            holder.like.alpha = if (rateable) 1f else 0.6f
            holder.dislike.isVisible = rateable
            holder.like.setOnClickListener { rate(c, true) }
            holder.dislike.setOnClickListener { rate(c, false) }
            holder.reply.isVisible = full && !c.mine && (c.replyParams != null || !YtAccount.isSignedIn(act))
            holder.reply.setOnClickListener {
                if (!YtAccount.isSignedIn(act)) needSignIn() else setReplyTo(if (c.isReply) parentOf(c) ?: c else c)
            }

            val hasReplies = !c.isReply && c.replyCount > 0 && (c.repliesToken != null || fallbackReplies.containsKey(c.id))
            holder.replies.isVisible = hasReplies
            if (hasReplies) {
                holder.replies.text = when {
                    c.id in openReplies -> "Hide replies"
                    c.replyCount == 1 -> "1 reply"
                    else -> "${c.replyCount} replies"
                }
                holder.replies.setOnClickListener { toggleReplies(c) }
            }
        }
    }

    /** The comment a reply belongs to (replies to a reply go to the same thread). */
    private fun parentOf(reply: YtComments.Item): YtComments.Item? {
        var i = rows.indexOf(reply)
        while (i > 0) {
            i--
            if (!rows[i].isReply) return rows[i]
        }
        return null
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.cAvatar)
        val author: TextView = v.findViewById(R.id.cAuthor)
        val text: TextView = v.findViewById(R.id.cText)
        val like: LinearLayout = v.findViewById(R.id.cLike)
        val likeIcon: ImageView = v.findViewById(R.id.cLikeIcon)
        val likeCount: TextView = v.findViewById(R.id.cLikeCount)
        val dislike: ImageView = v.findViewById(R.id.cDislike)
        val reply: ImageView = v.findViewById(R.id.cReply)
        val replies: TextView = v.findViewById(R.id.cReplies)
    }
}
