package com.rainax.ytdownloader

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        val rows = mutableListOf<RxOption>()
        rows += RxOption("Download", R.drawable.ic_download) { download(item) }
        if (id != null && !item.isPlaylist) {
            rows += RxOption("Save to Watch later", R.drawable.ic_watch_later) { saveWatchLater(act, id, signIn) }
            rows += RxOption("Save to playlist", R.drawable.ic_playlist_add) { saveSheet(act, id, signIn) }
        }
        rows += RxOption("Share", R.drawable.ic_share) { share(act, item) }
        if (onHidden != null && id != null) {
            rows += RxOption("Not interested", R.drawable.ic_not_interested) { notInterested(act, item, false, onHidden) }
            if (item.uploader.isNotBlank()) {
                rows += RxOption("Don't recommend channel", R.drawable.ic_remove) { notInterested(act, item, true, onHidden) }
            }
        }
        RxOptions.sheet(act, item.title.ifBlank { "Video" }, rows)
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
        val ui = SavePlaylistUi()
        val dialog = RxSheetDialog(act)
        val view = ComposeView(act)
        fun toggle(o: YtAccount.PlaylistOption) {
            val want = !o.has
            o.has = want
            ui.tick++
            act.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { YtAccount.setInPlaylist(o.id, videoId, want) }.isSuccess }
                if (!ok) { o.has = !want; ui.tick++ }
                val where = if (o.id == "WL") "Watch later" else o.title
                say(act, if (!ok) "Couldn't change $where. Try again" else if (want) "Saved to $where" else "Removed from $where")
            }
        }
        view.setContent { RxTheme { SavePlaylistSheet(ui, { toggle(it) }, { newPlaylist(act, videoId) { dialog.dismiss() } }) } }
        dialog.setContentView(view)
        dialog.show()
        act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtAccount.saveOptions(videoId) } }
            r.onSuccess {
                ui.list = it
                if (it.isEmpty()) ui.message = "No playlists yet. Make one below."
            }.onFailure {
                ui.list = emptyList()
                ui.message = "Couldn't load your playlists. Check your internet and try again."
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
