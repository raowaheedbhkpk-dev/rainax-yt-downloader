package com.rainax.ytdownloader

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.PageSubsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * Subscriptions tab, like YouTube: the round pictures of your channels (tap one for its page, All for the
 * list) and the newest videos from them, each with its Download button. Needs the YouTube account.
 */
class SubsScreen(
    private val act: AppCompatActivity,
    private val b: PageSubsBinding,
    private val openVideo: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit,
    private val openChannel: (VideoItem) -> Unit,
    private val openAllChannels: () -> Unit,
    private val signIn: () -> Unit
) {
    private val adapter = VideoAdapter(true, { openVideo(it) }, { download(it) })
    private val channels = ChannelAvatarAdapter { openChannel(it) }
    private var next: Page? = null
    private var job: Job? = null
    private var loadedFor: Boolean? = null           // signed-in state the list was loaded for

    fun setup() {
        b.subsList.layoutManager = LinearLayoutManager(act)
        b.subsList.adapter = adapter
        b.subsChannels.layoutManager = LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false)
        b.subsChannels.adapter = channels
        b.subsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - 5) load(reset = false)
            }
        })
        b.subsSwipe.setColorSchemeResources(R.color.rx_primary)
        b.subsSwipe.setOnRefreshListener { load(reset = true, fresh = true) }
        b.subsRefresh.setOnClickListener { load(reset = true, fresh = true) }
        b.subsAll.setOnClickListener { openAllChannels() }
        b.subsAction.setOnClickListener {
            if (YtAccount.isSignedIn(act)) load(reset = true, fresh = true) else signIn()
        }
    }

    /** The tab is on screen: load once (again after signing in or out). */
    fun onShown() {
        val signedIn = YtAccount.isSignedIn(act)
        if (loadedFor == signedIn && (adapter.count > 0 || job?.isActive == true)) return
        load(reset = true)
    }

    /** Signed in or out: start again. */
    fun onAccountChanged() {
        loadedFor = null
        SubsChannels.clear()
        adapter.submit(emptyList())
        channels.submit(emptyList())
    }

    private fun showEmpty(text: String?, action: String? = null) {
        b.subsEmpty.isVisible = text != null
        b.subsEmptyText.text = text.orEmpty()
        b.subsAction.isVisible = action != null
        b.subsAction.text = action.orEmpty()
    }

    private fun load(reset: Boolean, fresh: Boolean = false) {
        val signedIn = YtAccount.isSignedIn(act)
        loadedFor = signedIn
        if (!signedIn) {
            job?.cancel()
            b.subsSwipe.isRefreshing = false
            b.subsLoading.isVisible = false
            b.subsChannelsBox.isVisible = false
            adapter.submit(emptyList())
            showEmpty("Sign in to YouTube to see new videos\nfrom the channels you subscribe to.", "Sign in")
            return
        }
        if (reset) {
            job?.cancel()
            next = null
            showEmpty(null)
            if (adapter.count == 0 && !b.subsSwipe.isRefreshing) b.subsLoading.isVisible = true
        } else if (job?.isActive == true || next == null) {
            return
        }
        val page = next
        job = act.lifecycleScope.launch {
            try {
                if (reset) {
                    val ch = withContext(Dispatchers.IO) { runCatching { SubsChannels.load(fresh) }.getOrDefault(emptyList()) }
                    channels.submit(ch)
                    b.subsChannelsBox.isVisible = ch.isNotEmpty()
                }
                val res = withContext(Dispatchers.IO) { YtAccount.browse("FEsubscriptions", page) }
                val items = res.items.filter { !it.isChannel }
                val added = if (reset) { adapter.submit(items); items.size } else adapter.append(items)
                next = if (added == 0 && !reset) null else res.next
                adapter.loadingMore = next != null
                if (reset) b.subsList.scrollToPosition(0)
                if (reset && items.isEmpty()) showEmpty("No videos from your subscriptions yet.\nSubscribe to channels and their new videos show here.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                adapter.loadingMore = false
                if (reset || adapter.count == 0) {
                    val offline = !Net.online(act)
                    showEmpty(if (offline) NO_INTERNET else friendlyError(e.message ?: "Couldn't load"), "Try again")
                }
            } finally {
                // (a load replaced by a newer one leaves the spinner to that one)
                if (job === coroutineContext[Job]) {
                    b.subsLoading.isVisible = false
                    b.subsSwipe.isRefreshing = false
                }
            }
        }
    }
}
