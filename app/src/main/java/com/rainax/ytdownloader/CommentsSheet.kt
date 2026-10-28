package com.rainax.ytdownloader

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private val ui = CommentsUi()
    private val root = ComposeView(act)
    private val dialog = RxSheetDialog(act)
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
    private var sortTokens: List<String> = emptyList()

    val isShowing get() = dialog.isShowing

    fun show() {
        val actions = CommentsActions(
            sort = { i -> chooseSort(i) },
            nearEnd = { loadMore() },
            like = { rate(it, true) },
            dislike = { rate(it, false) },
            reply = { c -> if (!YtAccount.isSignedIn(act)) needSignIn() else setReplyTo(if (c.isReply) parentOf(c) ?: c else c) },
            replies = { toggleReplies(it) },
            signIn = { needSignIn() },
            cancelReply = { setReplyTo(null) },
            send = { post() },
            repliesLabel = { c ->
                val has = !c.isReply && c.replyCount > 0 && (c.repliesToken != null || fallbackReplies.containsKey(c.id))
                when {
                    !has -> null
                    c.id in openReplies -> "Hide replies"
                    c.replyCount == 1 -> "1 reply"
                    else -> "${c.replyCount} replies"
                }
            },
            canReply = { c -> full && !c.mine && (c.replyParams != null || !YtAccount.isSignedIn(act)) }
        )
        root.setContent { RxTheme { CommentsScreen(ui, actions) } }
        dialog.setContentView(root)
        val tall = heightPx.takeIf { it > act.resources.displayMetrics.heightPixels / 3 }
            ?: (act.resources.displayMetrics.heightPixels * 0.85).toInt()
        if (tall == heightPx) dialog.window?.setDimAmount(0.1f)     // under the video: the video stays bright
        root.layoutParams?.let {
            it.height = tall
            root.layoutParams = it
        }
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // the screen closes or is rebuilt (theme change): close the sheet and stop its loading too
        val watcher = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                scope.coroutineContext[Job]?.cancel()
                runCatching { dialog.dismiss() }
            }
        }
        act.lifecycle.addObserver(watcher)
        dialog.setOnDismissListener {
            scope.coroutineContext[Job]?.cancel()
            act.lifecycle.removeObserver(watcher)      // (each closed sheet was kept in memory until the screen closed)
        }
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
                val lp = root.layoutParams ?: return@addOnLayoutChangeListener
                if (lp.height != want) {
                    lp.height = want
                    root.post {
                        root.layoutParams = lp
                        val st = dialog.behavior.state
                        if (st != BottomSheetBehavior.STATE_HIDDEN && st != BottomSheetBehavior.STATE_DRAGGING &&
                            st != BottomSheetBehavior.STATE_SETTLING
                        ) dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }

        ui.signedIn = YtAccount.isSignedIn(act)
        ui.myAvatar = YtAccount.profile(act)?.avatar

        dialog.show()
        loadFirst()
    }

    /** The list changed: Compose draws it again. */
    private fun redraw() {
        ui.rows = rows.toList()
        ui.tick++
    }

    private fun needSignIn() {
        dialog.dismiss()
        onSignIn()
    }

    private fun videoId(): String? = Regex("(?:[?&]v=|shorts/)([\\w-]{6,})").find(videoUrl)?.groupValues?.get(1)

    // ---------- loading ----------

    private fun loadFirst() {
        ui.loading = true
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
            ui.loading = false
            addMine()
            when {
                simple == null -> if (rows.isEmpty()) empty("Couldn't load comments. Check your internet")
                simple.disabled -> empty("Comments are turned off")
                simple.items.isEmpty() -> if (rows.isEmpty()) empty("No comments yet")
                else -> {
                    if (simple.total > 0) ui.count = YtCatalog.count(simple.total.toLong())
                    val mine = rows.map { it.text.trim() }.toSet()
                    simple.items.filter { plain(it).toString().trim() !in mine }.forEach { rows += fromSimple(it) }
                    nextPage = simple.next
                }
            }
            redraw()
        }
    }

    /** Shows a page from YouTube's reader ([replace] = new list, e.g. another sort order). */
    private fun show(r: YtComments.Result, replace: Boolean) {
        ui.loading = false
        if (replace) {
            rows.clear()
            openReplies.clear()
            addMine(r.items)
        }
        r.count?.let { ui.count = it }
        val known = rows.mapTo(HashSet()) { it.id }
        val myTexts = rows.filter { it.mine }.map { it.text.trim() }.toSet()
        r.items.filter { it.id !in known && it.text.trim() !in myTexts }.forEach { rows += it }
        nextToken = r.next
        redraw()
        if (rows.isEmpty()) ui.empty = "No comments yet"
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
        ui.empty = text
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
                more.items.forEach { rows += fromSimple(it) }
                redraw()
            }
        }
    }

    /** Top / Newest chips. */
    private fun buildSort(sorts: List<Pair<String, String>>) {
        if (sorts.size < 2) return
        sortTokens = sorts.map { it.second }
        ui.sort = 0
        ui.sorts = sorts.map { it.first }
    }

    private fun chooseSort(i: Int) {
        val token = sortTokens.getOrNull(i) ?: return
        ui.sort = i
        loading?.cancel()
        ui.loading = true
        loading = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtComments.more(token, false) }.getOrNull() }
            ui.loading = false
            if (r != null) {
                show(r, replace = true)
                ui.scrollToTop()
            }
        }
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
            redraw()
            return
        }
        openReplies.add(c.id)
        redraw()
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
            redraw()
        }
    }

    private fun copyAsReply(i: YtComments.Item) = if (i.isReply) i else YtComments.Item(
        i.id, i.author, i.avatar, i.text, i.date, i.likeCount, i.likeState, 0, null,
        i.likeAction, i.unlikeAction, i.dislikeAction, i.undislikeAction, i.replyParams, isReply = true, byCreator = i.byCreator
    )

    private fun setReplyTo(c: YtComments.Item?) {
        replyTo = c
        ui.replyingTo = c?.author
        if (c != null) ui.focusInput()
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
            android.widget.Toast.makeText(act, "YouTube doesn't allow rating this comment", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val now = if (like) (if (before == "LIKE") "NONE" else "LIKE") else (if (before == "DISLIKE") "NONE" else "DISLIKE")
        c.likeState = now
        c.likeCount = bump(beforeCount, (if (now == "LIKE") 1 else 0) - (if (before == "LIKE") 1 else 0))
        redraw()
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtComments.act(action) }.isSuccess }
            if (!ok) {
                c.likeState = before
                c.likeCount = beforeCount
                redraw()
                android.widget.Toast.makeText(act, "Couldn't save that. Try again", android.widget.Toast.LENGTH_SHORT).show()
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
        val text = ui.input.text.trim()
        if (text.isEmpty()) return
        val id = videoId() ?: return
        val target = replyTo
        val params = target?.replyParams
        if (target != null && params == null) {
            android.widget.Toast.makeText(act, "Replies can't be sent to this comment", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        ui.sending = true
        act.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { if (params != null) YtComments.reply(params, text) else YtAccount.postComment(id, text) }.exceptionOrNull()
            }
            ui.sending = false
            if (error != null) {
                android.widget.Toast.makeText(act, error.message ?: "Couldn't post", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            ui.input = TextFieldValue("")
            ui.hideKeyboard()
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
                    redraw()
                }
                setReplyTo(null)
                android.widget.Toast.makeText(act, "Reply posted", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                MyComments.add(act, id, text)
                rows.add(0, YtComments.Item(
                    "mine-" + text.hashCode(), author, me?.avatar, text, "Just now", null, "NONE", 0, null,
                    null, null, null, null, null, isReply = false, byCreator = false, mine = true
                ))
                ui.empty = null
                redraw()
                ui.scrollToTop()
                android.widget.Toast.makeText(act, "Comment posted", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun plain(c: Comment): CharSequence =
        if (c.html) HtmlCompat.fromHtml(c.text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() else c.text

    /** The comment a reply belongs to (replies to a reply go to the same thread). */
    private fun parentOf(reply: YtComments.Item): YtComments.Item? {
        var i = rows.indexOf(reply)
        while (i > 0) {
            i--
            if (!rows[i].isReply) return rows[i]
        }
        return null
    }
}
