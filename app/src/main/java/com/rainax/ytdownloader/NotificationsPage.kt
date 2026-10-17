package com.rainax.ytdownloader

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.ItemNotificationBinding
import com.rainax.ytdownloader.databinding.PageNotificationsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bell: YouTube's real notifications, laid out like the YouTube app — unread dot, channel picture,
 * "For you" / video title / "2 weeks ago", the video's picture and a ••• menu. All / Mentions on top.
 * It never shows feed videos in place of notifications.
 */
class NotificationsPage(
    private val act: AppCompatActivity,
    private val open: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit
) {
    private val b = PageNotificationsBinding.inflate(act.layoutInflater)
    private val dialog = Dialog(act, if (AppPrefs.themeMode(act) == 3) R.style.Theme_Rainax_Page_Amoled else R.style.Theme_Rainax_Page)
    private val all = mutableListOf<YtAccount.Notif>()
    private var next: String? = null
    private var job: Job? = null
    private var mentionsOnly = false
    private val adapter = Adapter()

    fun show() {
        dialog.setContentView(b.root)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        b.notifBack.setOnClickListener { dialog.dismiss() }
        b.notifRefresh.setOnClickListener { load(reset = true) }
        b.notifRetry.setOnClickListener { load(reset = true) }
        b.notifSwipe.setColorSchemeColors(androidx.core.content.ContextCompat.getColor(act, R.color.rx_primary))
        b.notifSwipe.setOnRefreshListener { load(reset = true) }
        b.notifChips.setOnCheckedStateChangeListener { _, ids ->
            mentionsOnly = ids.firstOrNull() == R.id.notifMentions
            render()
        }
        b.notifList.layoutManager = LinearLayoutManager(act)
        b.notifList.adapter = adapter
        b.notifList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && next != null && job?.isActive != true && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) load(reset = false)
            }
        })
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        if (!reset && next == null) return
        job?.cancel()
        if (reset) {
            next = null
            if (all.isEmpty()) b.notifLoading.isVisible = true
            b.notifEmpty.isVisible = false
        }
        val token = if (reset) null else next
        job = act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtAccount.inbox(token) } }
            b.notifLoading.isVisible = false
            b.notifSwipe.isRefreshing = false
            r.onSuccess { page ->
                if (reset) all.clear()
                val have = all.map { it.id }.toHashSet()
                all += page.items.filter { have.add(it.id) }
                next = page.next?.takeIf { page.items.isNotEmpty() }
                render()
            }.onFailure { e ->
                if (all.isEmpty()) showMessage("Couldn't load notifications", friendlyError(e.message ?: "Check your internet and try again"), retry = true)
                else Notify.show(act, "Couldn't load more notifications")
            }
        }
    }

    private fun render() {
        val list = if (mentionsOnly) all.filter { it.mention } else all
        adapter.submit(list)
        when {
            b.notifLoading.isVisible -> b.notifEmpty.isVisible = false
            list.isNotEmpty() -> b.notifEmpty.isVisible = false
            mentionsOnly -> showMessage("No mentions", "Replies to your comments and @mentions show here.", retry = false)
            else -> showMessage("No notifications yet", "New videos from your channels, replies and likes show here.", retry = false)
        }
    }

    private fun showMessage(title: String, sub: String, retry: Boolean) {
        b.notifEmpty.isVisible = true
        b.notifEmptyTitle.text = title
        b.notifEmptySub.text = sub
        b.notifRetry.isVisible = retry
        Motion.riseUp(b.notifEmpty)
    }

    private fun itemOf(n: YtAccount.Notif): VideoItem? {
        val url = n.url ?: return null
        val isChannel = n.videoId == null
        return VideoItem(
            url = url, title = if (isChannel) n.heading else n.body.ifBlank { n.heading }, uploader = if (isChannel) "" else n.heading.takeIf { it != "For you" }.orEmpty(),
            thumb = if (isChannel) n.avatar else n.thumb, seconds = 0, views = -1, uploaded = null,
            avatar = n.avatar, isChannel = isChannel
        )
    }

    private fun tap(n: YtAccount.Notif) {
        markRead(act, n.id)
        adapter.notifyDataSetChanged()
        val item = itemOf(n)
        if (item == null) { Notify.show(act, "This notification can't be opened in RAINAX"); return }
        dialog.dismiss()
        open(item)
    }

    private fun menu(n: YtAccount.Notif, anchor: View) {
        val item = itemOf(n)
        val pm = PopupMenu(act, anchor)
        if (item != null && !item.isChannel) pm.menu.add(0, 1, 0, "Download")
        if (item != null) pm.menu.add(0, 2, 1, "Share")
        if (!isRead(act, n)) pm.menu.add(0, 3, 2, "Mark as read")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { dialog.dismiss(); download(item!!) }
                2 -> runCatching {
                    act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, item!!.url), "Share"))
                }
                3 -> { markRead(act, n.id); adapter.notifyDataSetChanged() }
            }
            true
        }
        pm.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var items: List<YtAccount.Notif> = emptyList()

        fun submit(list: List<YtAccount.Notif>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemNotificationBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            val v = holder.v
            v.nDot.visibility = if (isRead(act, n)) View.INVISIBLE else View.VISIBLE
            v.nHeading.text = n.heading
            v.nBody.text = n.body
            v.nBody.isVisible = n.body.isNotBlank()
            v.nTime.text = n.time
            v.nTime.isVisible = n.time.isNotBlank()
            Img.load(v.nAvatar, n.avatar, circle = true, widthPx = 160)
            v.nThumb.isVisible = n.thumb != null
            if (n.thumb != null) Img.load(v.nThumb, n.thumb, widthPx = 360)
            v.nRow.setOnClickListener { tap(n) }
            v.nMore.setOnClickListener { menu(n, it) }
        }
    }

    companion object {
        private const val PREFS = "notif_read"
        private const val MAX_KEEP = 400

        /** Read on YouTube, or tapped here (kept on the phone). */
        fun isRead(c: Context, n: YtAccount.Notif): Boolean = n.read || readIds(c).contains(n.id)

        private fun readIds(c: Context): Set<String> =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty()

        private fun markRead(c: Context, id: String) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ids = LinkedHashSet(p.getStringSet("ids", emptySet()).orEmpty())
            ids += id
            while (ids.size > MAX_KEEP) ids.remove(ids.first())
            p.edit().putStringSet("ids", ids).apply()
        }
    }
}
