package com.rainax.ytdownloader

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.ItemOptionBinding
import com.rainax.ytdownloader.databinding.ItemSavePlaylistBinding
import com.rainax.ytdownloader.databinding.SheetPlayerOptionsBinding
import com.rainax.ytdownloader.databinding.SheetSavePlaylistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A video's ⋮ menu, like YouTube's: Download, Save to Watch later, Save to playlist, Share, Not interested and
 * Don't recommend channel. Also the "Save video to…" sheet (your YouTube playlists, with tick boxes).
 */
object VideoMenu {

    private fun videoId(url: String) = Regex("(?:[?&]v=|shorts/|youtu\\.be/)([\\w-]{6,})").find(url)?.groupValues?.get(1)

    /**
     * [onHidden]: the video (channel = false) or all of its channel's videos (true) should leave the list now.
     * [signIn]: opens YouTube sign-in (saving needs your account).
     */
    fun show(
        act: AppCompatActivity, item: VideoItem,
        download: (VideoItem) -> Unit, signIn: () -> Unit,
        onHidden: ((VideoItem, Boolean) -> Unit)? = null
    ) {
        val id = videoId(item.url)
        val rows = mutableListOf<Triple<Int, String, () -> Unit>>()
        rows += Triple(R.drawable.ic_download, "Download") { download(item) }
        if (id != null && !item.isPlaylist) {
            rows += Triple(R.drawable.ic_watch_later, "Save to Watch later") { saveWatchLater(act, id, signIn) }
            rows += Triple(R.drawable.ic_playlist_add, "Save to playlist") { saveSheet(act, id, signIn) }
        }
        rows += Triple(R.drawable.ic_share, "Share") { share(act, item) }
        if (onHidden != null && id != null) {
            rows += Triple(R.drawable.ic_not_interested, "Not interested") { notInterested(act, item, false, onHidden) }
            if (item.uploader.isNotBlank()) {
                rows += Triple(R.drawable.ic_remove, "Don't recommend channel") { notInterested(act, item, true, onHidden) }
            }
        }
        val sb = SheetPlayerOptionsBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        sb.optTitle.text = item.title.ifBlank { "Video" }
        sb.optTitle.maxLines = 2
        rows.forEach { (icon, text, action) ->
            val row = ItemOptionBinding.inflate(act.layoutInflater, sb.optList, false)
            row.optText.text = text
            row.optValue.isVisible = false
            row.optIcon.setImageResource(icon)
            row.optIcon.isVisible = true
            row.root.setOnClickListener { dialog.dismiss(); action() }
            sb.optList.addView(row.root)
        }
        dialog.show()
    }

    private fun share(act: AppCompatActivity, item: VideoItem) {
        runCatching {
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, listOf(item.title, item.url).filter { it.isNotBlank() }.joinToString("\n")), "Share"))
        }
    }

    private fun saveWatchLater(act: AppCompatActivity, id: String, signIn: () -> Unit) {
        if (!YtAccount.isSignedIn(act)) { Notify.show(act, "Sign in to save videos", action = "Sign in") { signIn() }; return }
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.watchLater(id) }.isSuccess }
            Notify.show(act, if (ok) "Saved to Watch later" else "Couldn't save. Try again")
        }
    }

    /** "Not interested" / "Don't recommend channel": gone from your lists now, and YouTube is told (signed in). */
    private fun notInterested(act: AppCompatActivity, item: VideoItem, channel: Boolean, onHidden: (VideoItem, Boolean) -> Unit) {
        if (channel) HiddenFeed.hideChannel(act, item) else HiddenFeed.hideVideo(act, item)
        onHidden(item, channel)
        val token = if (channel) item.noChannelToken else item.notInterestedToken
        if (token != null && YtAccount.isSignedIn(act)) {
            act.lifecycleScope.launch(Dispatchers.IO) { runCatching { YtAccount.sendFeedback(token) } }
        }
        Notify.show(act, if (channel) "We won't recommend ${item.uploader} to you" else "Video removed. We'll show fewer like this")
    }

    /** "Save video to…": your YouTube playlists with tick boxes (changes save at once), and New playlist. */
    fun saveSheet(act: AppCompatActivity, videoId: String, signIn: () -> Unit) {
        if (!YtAccount.isSignedIn(act)) { Notify.show(act, "Sign in to save videos", action = "Sign in") { signIn() }; return }
        val sb = SheetSavePlaylistBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        fun privacyIcon(p: String) = when {
            p.contains("PUBLIC", true) -> R.drawable.ic_lock_open
            p.contains("UNLISTED", true) -> R.drawable.ic_link
            else -> R.drawable.ic_lock
        }
        fun render(list: List<YtAccount.PlaylistOption>) {
            sb.spList.removeAllViews()
            sb.spEmpty.isVisible = list.isEmpty()
            sb.spEmpty.text = "No playlists yet. Make one below."
            list.forEach { o ->
                val row = ItemSavePlaylistBinding.inflate(act.layoutInflater, sb.spList, false)
                row.spTitle.text = o.title
                row.spCheck.isChecked = o.has
                row.spPrivacy.setImageResource(privacyIcon(o.privacy))
                row.root.setOnClickListener {
                    val want = !o.has
                    o.has = want
                    row.spCheck.isChecked = want
                    act.lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.setInPlaylist(o.id, videoId, want) }.isSuccess }
                        if (!ok) { o.has = !want; row.spCheck.isChecked = !want }
                        val where = if (o.id == "WL") "Watch later" else o.title
                        say(act, if (!ok) "Couldn't change $where. Try again" else if (want) "Saved to $where" else "Removed from $where")
                    }
                }
                sb.spList.addView(row.root)
            }
        }
        sb.spNew.setOnClickListener { newPlaylist(act, videoId) { dialog.dismiss() } }
        dialog.show()
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtAccount.saveOptions(videoId) } }
            sb.spLoading.isVisible = false
            r.onSuccess { render(it) }.onFailure {
                sb.spEmpty.isVisible = true
                sb.spEmpty.text = "Couldn't load your playlists. Check your internet and try again."
            }
        }
    }

    /** A short message over the sheet (the app's message bar would sit behind it). */
    private fun say(act: AppCompatActivity, text: String) =
        android.widget.Toast.makeText(act, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun newPlaylist(act: AppCompatActivity, videoId: String, done: () -> Unit) {
        val d = act.resources.displayMetrics.density
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
        }
        val name = android.widget.EditText(act).apply {
            hint = "Name"
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(150))
        }
        box.addView(name)
        val privacies = listOf("PRIVATE" to "Private", "UNLISTED" to "Unlisted", "PUBLIC" to "Public")
        val group = android.widget.RadioGroup(act).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        privacies.forEachIndexed { i, (_, label) ->
            group.addView(android.widget.RadioButton(act).apply { id = 1000 + i; text = label })
        }
        group.check(1000)
        box.addView(group)
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("New playlist")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val title = name.text.toString().trim()
                if (title.isEmpty()) { Notify.show(act, "Give the playlist a name"); return@setPositiveButton }
                val privacy = privacies[(group.checkedRadioButtonId - 1000).coerceIn(0, 2)].first
                act.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.createPlaylist(title, privacy, videoId) }.getOrNull() != null }
                    done()
                    Notify.show(act, if (ok) "Saved to $title" else "Couldn't make the playlist. Try again")
                }
            }
            .show()
        name.requestFocus()
    }
}
