package com.rainax.ytdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rainax.ytdownloader.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.rainax.ytdownloader.MainActivity.Companion.MAX_SHARED_LINKS
import com.rainax.ytdownloader.MainActivity.Companion.MODE_ALL
import com.rainax.ytdownloader.MainActivity.Companion.MODE_QUICK
import com.rainax.ytdownloader.MainActivity.Companion.MODE_SUBS

// Social tab, copied links and the download sheet (part of MainActivity).
// These are MainActivity's own functions, kept in their own file so each part stays small.

internal fun MainActivity.setupSocial() {
    statusSaver = StatusSaver(
        this, statusUi,
        pickFolder = { initial ->
            try { statusFolderPicker.launch(initial) } catch (e: Exception) { message("This phone has no folder picker") }
        },
        askFileAccess = { requestFileAccess(thenInstallAsk = false) }
    )
    statusSaver.setup()
    // the page itself is a Compose screen (SocialScreen.kt)
    so.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    so.setContent { RxTheme { SocialScreen(this, social, statusUi) } }
}

/** Social page: a video link is on the clipboard → a ticket "Copied · TikTok · Get it". */
internal fun MainActivity.refreshClipStrip() {
    if (isFinishing || isDestroyed) return
    val url = clipText()?.let { extractUrls(it).firstOrNull() }
    val typed = extractUrls(social.input).firstOrNull()
    social.clipUrl = if (url != null && url != clipStripDone && url != typed) url else null
}

internal fun MainActivity.clipText(): String? = runCatching {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
}.getOrNull()

/** Video sites whose copied links RAINAX offers to download. */
internal fun MainActivity.isVideoLink(url: String): Boolean {
    val u = url.lowercase()
    return FastExtractor.supports(u) || isPlaylistUrl(u) || listOf(
        "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "soundcloud.com", "bandcamp.com",
        "x.com/", "twitter.com/"
    ).any { u.contains(it) }
}

/** A video link was copied in another app: offer to download it (once per link). */
internal fun MainActivity.checkClipboard() {
    if (isFinishing || isDestroyed || !AppPrefs.clipDetect(this) || clipDialog?.isShowing == true || permDialog?.isShowing == true ||
        sheet?.isShowing == true || video.fullscreen
    ) return
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val desc = cm.primaryClipDescription ?: return
    // reading the text shows "pasted" on new Android versions: only when something new was copied
    if (desc.timestamp == lastClipStamp) return
    lastClipStamp = desc.timestamp
    if (!desc.hasMimeType("text/*")) return
    val url = clipText()?.let { t -> extractUrls(t).firstOrNull { isVideoLink(it) } } ?: return
    if (url == AppPrefs.clipSeen(this)) return
    AppPrefs.setClipSeen(this, url)
    val site = when {
        FastExtractor.supports(url) || isPlaylistUrl(url) -> "YouTube"
        url.contains("tiktok", true) -> "TikTok"
        url.contains("instagram", true) -> "Instagram"
        url.contains("facebook", true) || url.contains("fb.watch", true) -> "Facebook"
        else -> Uri.parse(url).host?.removePrefix("www.") ?: "Link"
    }
    clipDialog = RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
        .setIcon(R.drawable.ic_link)
        .setTitle("$site link copied")
        .setMessage(url)
        .setPositiveButton("Add to downloads") { _, _ -> showDownloadSheet(listOf(url)) }
        .setNegativeButton("Not now", null)
        .show()
}

internal fun MainActivity.extractUrls(text: String): List<String> =
    LinkText.find(text, MAX_SHARED_LINKS)

/** Links shared from other apps (Share > RAINAX). */
internal fun MainActivity.handleIntent(intent: Intent?) {
    // "N new WhatsApp statuses saved" tapped: the Social page at the status saver
    if (intent?.getBooleanExtra(StatusAutoSave.EXTRA_OPEN_STATUS, false) == true) {
        intent.removeExtra(StatusAutoSave.EXTRA_OPEN_STATUS)
        b.bottomNav.selectedItemId = R.id.nav_social
        so.post {
            social.showStatus++
            statusSaverOrNull()?.selectTab(2)
        }
        return
    }
    if (intent?.action != Intent.ACTION_SEND) return
    val urls = extractUrls(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
    if (urls.isNotEmpty()) {
        showDownloadSheet(urls)
    }
}

internal fun MainActivity.showDownloadSheet(
    urls: List<String>, preferAudio: Boolean = false, knownTitle: String? = null,
    /** shown by a page over the app (the browser) instead of the message bar, which would sit behind it */
    onAdded: ((String) -> Unit)? = null
) {
    sheet?.dismiss()
    val ui = DlSheetUi()
    // what the sheet's buttons do (filled in below, once the functions they call exist)
    var onClose = {}; var onBack = {}; var onTab: (Int) -> Unit = {}; var onRetry = {}; var onDownload = {}
    var choiceAction: (FormatChoice) -> Unit = {}
    var navAction: (String) -> Unit = {}
    val (dialog, sheetView) = DlSheet.create(this, ui, DlSheetActions(
        close = { onClose() }, back = { onBack() }, tab = { onTab(it) }, pick = { choiceAction(it) },
        more = { navAction("all") }, nav = { navAction(it) }, retry = { onRetry() }, download = { onDownload() }
    ))
    sheet = dialog

    val single = urls.size == 1
    val firstUrl = urls[0]
    val cookies = urls.associateWith<String, String?> { null }

    var current: PreviewState? = null
    var mode = MODE_QUICK
    // Video or Audio tab, each with its own selected format (the bar shows the one of the open tab)
    var tabKind = if (preferAudio) KIND_AUDIO else KIND_VIDEO
    var selVideo: String? = null
    var selAudio: String? = if (preferAudio) "audio:m4a" else null
    var selectedSub: String? = null
    fun selectedSpecNow(): String? = if (tabKind == KIND_AUDIO) selAudio else selVideo
    fun select(spec: String?) { if (tabKind == KIND_AUDIO) selAudio = spec else selVideo = spec }

    var cardsShown = false
    var downloading = false
    onClose = { dialog.dismiss() }

    fun group(list: List<FormatChoice>, detailed: Boolean): List<FormatRow> {
        val out = mutableListOf<FormatRow>()
        val mine = list.filter { it.kind == tabKind }
        if (mine.isNotEmpty()) {
            out += FormatRow.Section(if (tabKind == KIND_AUDIO) "Audio" else "Video")
            mine.forEach { out += FormatRow.Choice(it, it.spec == selectedSpecNow(), detailed) }
        }
        return out
    }

    fun specHeight(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

    /**
     * The card's words: "High Quality" / "720p" for video, "Original · Instant" / "M4A" for sound. The same words
     * as every other download sheet (an upright video is named by its short side: 1080x1920 is 1080p, not 2K;
     * this sheet used to read the height and showed upright videos as 2K or 5K).
     */
    fun cardOf(c: FormatChoice, recommended: Boolean): DlCard.Format {
        val (tag, q) = DlCardText.of(c)
        return DlCard.Format(c, tag, q, c.size.ifBlank { "Size unknown" }, recommended, c.spec == selectedSpecNow())
    }

    /** The suggested card of a list: 720p (or nearest) for video, M4A for sound. */
    fun recommendedOf(list: List<FormatChoice>): FormatChoice? = DlCardText.recommended(list, tabKind)

    /** "720p (280.0 MB)" for the bar. */
    fun shortLabel(c: FormatChoice): String {
        val card = cardOf(c, false)
        return if (c.size.isBlank()) card.quality else "${card.quality} (${c.size})"
    }

    // Several videos (picked with +) and playlists: each video's own formats are looked up (4 at a time),
    // and only the formats those videos really have are shown, with their sizes added up.
    // A playlist uses its first videos and scales their sizes to the whole list (shown with ≈).
    val found = java.util.concurrent.ConcurrentHashMap<String, PreviewState>()
    var lookups: Job? = null
    var sampled: List<PlaylistEntry> = emptyList()
    var renderNow: () -> Unit = {}
    // many picked videos: sizes come from the first few and are scaled up (≈), so YouTube isn't asked
    // about hundreds of videos at once (that can get the connection blocked for a while)
    val sampleUrls = if (single) urls else urls.take(Picks.SAMPLE)
    val sampledMany = sampleUrls.size < urls.size
    // many lookups finish close together: draw once per frame, not once per video
    var renderQueued = false
    val renderSoon = {
        if (!renderQueued) {
            renderQueued = true
            b.root.post { renderQueued = false; if (sheet === dialog) renderNow() }
        }
    }

    fun lookUp(list: List<String>) {
        lookups?.cancel()
        lookups = lifecycleScope.launch {
            list.forEach { u ->
                launch {
                    found[u] = vm.infoFor(u)
                    renderSoon()
                }
            }
        }
    }

    fun heightOf(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

    /** What [spec] downloads for one video: that format, else its nearest lower video quality. */
    fun bytesFor(st: PreviewState, spec: String): Long {
        st.all.firstOrNull { it.spec == spec }?.let { return it.bytes }
        if (!spec.startsWith("video")) return 0
        val want = heightOf(spec).takeIf { it > 0 } ?: Int.MAX_VALUE
        val videos = st.all.filter { it.kind == KIND_VIDEO }
        return (videos.filter { heightOf(it.spec) <= want }.maxByOrNull { heightOf(it.spec) }
            ?: videos.minByOrNull { heightOf(it.spec) })?.bytes ?: 0
    }

    /** The formats these videos have, each with the total size ([scale] > 1: a playlist estimate). */
    fun combine(states: List<PreviewState>, scale: Double): Pair<List<FormatChoice>, List<FormatChoice>> {
        val ok = states.filter { it.error == null && it.all.isNotEmpty() }
        if (ok.isEmpty()) return emptyList<FormatChoice>() to emptyList()
        val bySpec = LinkedHashMap<String, FormatChoice>()
        ok.forEach { st -> st.all.forEach { c -> bySpec.putIfAbsent(c.spec, c) } }
        val all = bySpec.values.map { c ->
            val sum = (ok.sumOf { bytesFor(it, c.spec) } * scale).toLong()
            c.copy(size = if (sum > 0) (if (scale > 1.01) "≈ " else "") + formatSize(sum) else "", bytes = sum)
        }.sortedWith(compareBy<FormatChoice>({ if (it.kind == KIND_AUDIO) 0 else 1 }, { heightOf(it.spec) }))
        val videos = all.filter { it.kind == KIND_VIDEO }
        val quickVideo = listOfNotNull(
            videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 360) },
            videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 720) },
            videos.minByOrNull { kotlin.math.abs(heightOf(it.spec) - 1080) }
        ).distinctBy { it.spec }.sortedBy { heightOf(it.spec) }
        return (all.filter { it.kind == KIND_AUDIO } + quickVideo) to all
    }

    fun render() {
        val p = current
        val isPlaylist = single && p?.playlist?.isNotEmpty() == true
        if (isPlaylist && sampled.isEmpty()) {
            sampled = p!!.playlist.take(6)
            lookUp(sampled.map { it.url })
        }
        val waiting = when {
            !single -> sampleUrls.count { found[it] == null }
            isPlaylist -> sampled.count { found[it.url] == null }
            else -> 0
        }
        val (quick, all) = when {
            !single -> {
                val read = sampleUrls.mapNotNull { found[it] }
                val good = read.count { it.error == null && it.all.isNotEmpty() }
                combine(read, if (sampledMany && good > 0) urls.size.toDouble() / good else 1.0)
            }
            isPlaylist -> {
                val list = p!!.playlist
                val known = list.filter { it.seconds > 0 }
                val avg = if (known.isNotEmpty()) known.sumOf { it.seconds } / known.size else 240L
                fun len(e: PlaylistEntry) = if (e.seconds > 0) e.seconds else avg
                val done = sampled.filter { found[it.url]?.let { st -> st.error == null } == true }
                val scale = if (done.isEmpty()) 1.0
                else list.sumOf { len(it) }.toDouble() / done.sumOf { len(it) }.coerceAtLeast(1)
                combine(done.mapNotNull { found[it.url] }, scale.coerceAtLeast(1.0))
            }
            p == null || p.loading || p.error != null -> emptyList<FormatChoice>() to emptyList()
            else -> p.quick to p.all
        }
        val loading = (single && !isPlaylist && (p == null || p.loading)) || (waiting > 0 && quick.isEmpty()) ||
            (single && p == null)
        val error = when {
            single && !isPlaylist -> p?.error
            !single && waiting == 0 && quick.isEmpty() -> "Couldn't read these videos. Check your internet, then tap Retry."
            isPlaylist && waiting == 0 && quick.isEmpty() -> "Couldn't read this playlist's videos. Tap Retry."
            else -> null
        }
        val subs = if (single && !isPlaylist) p?.subtitles.orEmpty() else emptyList()

        // a link with only sound (or only video): that tab, and no tab switch
        val hasVideo = (quick + all).any { it.kind == KIND_VIDEO }
        val hasAudio = (quick + all).any { it.kind == KIND_AUDIO }
        if (!hasVideo && hasAudio) tabKind = KIND_AUDIO
        if (!hasAudio && hasVideo) tabKind = KIND_VIDEO
        val tabQuick = quick.filter { it.kind == tabKind }
        val tabAll = all.filter { it.kind == tabKind }

        // picked while sizes were loading, but this video has no such format: select the closest real one,
        // and nothing picked yet: the recommended one (so DOWNLOAD works at once)
        val sel = selectedSpecNow()
        if (tabQuick.isNotEmpty() && (sel == null || (tabQuick + tabAll).none { it.spec == sel })) {
            select(if (sel == null || tabKind == KIND_AUDIO) recommendedOf(tabQuick)?.spec else {
                val want = specHeight(sel).takeIf { it > 0 } ?: Int.MAX_VALUE
                (tabQuick.filter { specHeight(it.spec) <= want }.maxByOrNull { specHeight(it.spec) } ?: recommendedOf(tabQuick))?.spec
            })
        }
        val selectedSpec = selectedSpecNow()

        // header: picture, title, a short line under it
        val title = when {
            !single -> "Download ${urls.size} videos"
            else -> (p?.takeIf { !it.loading }?.title ?: knownTitle)?.takeIf { it.isNotBlank() } ?: "Download"
        }
        ui.heading = when (mode) {
            MODE_ALL -> "More formats"
            MODE_SUBS -> "Subtitles/CC"
            else -> title
        }
        ui.back = mode != MODE_QUICK
        ui.showThumb = mode == MODE_QUICK
        ui.thumbUrl = p?.thumbUrl ?: youtubeThumb(firstUrl) ?: found[firstUrl]?.thumbUrl
        ui.duration = if (single && !isPlaylist) p?.subtitle?.let { Regex("(\\d+:)?\\d{1,2}:\\d{2}$").find(it.trim())?.value } else null
        ui.sub = when {
            mode != MODE_QUICK -> title
            !single && waiting > 0 -> "Getting sizes…  ${sampleUrls.size - waiting} of ${sampleUrls.size}"
            !single && sampledMany -> "They download one after another  •  sizes estimated from ${sampleUrls.size} videos"
            !single -> sampleUrls.count { found[it]?.error != null }.let { bad ->
                if (bad > 0) "They download one after another  •  $bad couldn't be read" else "They download one after another"
            }
            loading -> if (p?.title != null || knownTitle != null) "Getting sizes…" else "Fetching info…"
            error != null -> Uri.parse(firstUrl).host.orEmpty()
            isPlaylist -> "${p!!.subtitle.orEmpty()}  •  whole playlist".trimStart(' ', '•') + if (waiting > 0) "  •  getting sizes…" else ""
            else -> "Choose a format to download"
        }
        ui.loading = loading || waiting > 0

        // Video / Audio tabs
        ui.showTabs = mode != MODE_SUBS && ((hasVideo && hasAudio) || (single && !isPlaylist && loading))
        ui.tabKind = tabKind
        ui.error = error

        if (mode == MODE_QUICK) {
            // the cards: this tab's main formats, then "More Formats"
            val rec = recommendedOf(tabQuick)?.spec
            val list = tabQuick.map { cardOf(it, it.spec == rec && tabQuick.size > 1) }.toMutableList<DlCard>()
            val moreCount = tabAll.count { a -> tabQuick.none { it.spec == a.spec } }
            if (tabAll.isNotEmpty() && (moreCount > 0 || (tabKind == KIND_VIDEO && subs.isNotEmpty()))) {
                list += DlCard.More(if (moreCount > 0) "View all ${tabAll.size}" else "Subtitles")
            }
            val showing = if (loading || error != null) emptyList() else list
            // the cards rise in when they first appear
            if (showing.isNotEmpty() && !cardsShown) { cardsShown = true; ui.riseKey++ }
            ui.cards = showing
        }
        ui.listMode = mode != MODE_QUICK
        val selChoice = (quick + all).firstOrNull { it.spec == selectedSpec }
        ui.selectedLabel = when {
            selChoice != null -> shortLabel(selChoice) + if (tabKind == KIND_VIDEO && selectedSub != null) "  •  CC" else ""
            loading || waiting > 0 -> "Getting sizes…"
            else -> "Nothing selected"
        }

        val rows: List<FormatRow> = when (mode) {
            MODE_SUBS -> {
                val list = mutableListOf<FormatRow>()
                list += FormatRow.Choice(FormatChoice("", "None", kind = KIND_NONE), selectedSub == null, false)
                subs.forEach {
                    list += FormatRow.Choice(FormatChoice(it.code, it.label, kind = KIND_NONE), selectedSub == it.code, false)
                }
                list
            }
            MODE_ALL -> {
                val list = group(all, true).toMutableList()
                if (subs.isNotEmpty() && tabKind == KIND_VIDEO) {
                    val label = subs.firstOrNull { it.code == selectedSub }?.label ?: "Off"
                    list += FormatRow.Nav("subs", "Subtitles/CC", label, R.drawable.ic_cc)
                }
                list
            }
            else -> emptyList()
        }
        if (mode != MODE_QUICK) ui.rows = rows
        // only formats that are really shown can be downloaded
        ui.canDownload = !downloading && selectedSpec != null && (quick + all).any { it.spec == selectedSpec }
        ui.tall = mode != MODE_QUICK
    }

    renderNow = { render() }
    choiceAction = { c ->
        if (mode == MODE_SUBS) {
            selectedSub = c.spec.ifEmpty { null }
            mode = MODE_ALL
        } else {
            select(c.spec)
        }
        render()
    }
    // switching Video / Audio: the other cards rise in
    onTab = { k -> if (tabKind != k) { tabKind = k; ui.riseKey++; render() } }
    navAction = { id ->
        mode = if (id == "all") MODE_ALL else MODE_SUBS
        render()
    }
    onBack = {
        mode = if (mode == MODE_SUBS) MODE_ALL else MODE_QUICK
        render()
    }
    onRetry = {
        when {
            !single -> { found.clear(); lookUp(sampleUrls) }
            current?.playlist?.isNotEmpty() == true -> { found.clear(); sampled = emptyList() }
            else -> vm.fetchInfo(firstUrl, cookies[firstUrl])
        }
        render()
    }
    onDownload = download@{
        if (downloading) return@download
        val spec = selectedSpecNow() ?: return@download
        val p = current
        val sub = if (spec.startsWith("video")) selectedSub else null
        val items = when {
            !single -> urls.map { EnqueueItem(it, found[it]?.title.orEmpty(), cookies[it], found[it]?.thumbUrl ?: youtubeThumb(it)) }
            p != null && p.playlist.isNotEmpty() ->
                p.playlist.map { EnqueueItem(it.url, it.title, cookies[firstUrl], it.thumbUrl) }
            // never save a status text like "Connecting…" as the title (blank = looked up by the service)
            else -> listOf(
                EnqueueItem(firstUrl, p?.takeIf { !it.loading }?.title ?: knownTitle.orEmpty(), cookies[firstUrl], youtubeThumb(firstUrl))
            )
        }
        vm.enqueue(items, spec, sub)
        downloading = true                               // (one tap: the ticket is being torn off)
        ui.canDownload = false
        // (no battery question: downloads run as a foreground service with a notification, which
        // Android keeps running; the optional setting is in Settings for phones that still stop them)
        val what = if (items.size == 1) "Added to downloads" else "Added ${items.size} items to downloads"
        DlSheet.tear(ui, sheetView) {
            if (isFinishing || isDestroyed) return@tear
            dialog.dismiss()
            if (onAdded != null) { onAdded(what); return@tear }
            Snackbar.make(b.root, what, Snackbar.LENGTH_LONG)
                .setAnchorView(snackAnchor())
                .setAction("View") { b.bottomNav.selectedItemId = R.id.nav_downloads }
                .show()
        }
    }

    var collectJob: Job? = null
    if (!single) lookUp(sampleUrls)
    if (single) {
        vm.fetchInfo(firstUrl, cookies[firstUrl])
        collectJob = lifecycleScope.launch {
            vm.preview.collect { current = it; render() }
        }
    }
    render()

    dialog.setOnDismissListener {
        collectJob?.cancel()
        lookups?.cancel()
        // A newer sheet may already be open (dismiss runs later): only clean up our own
        if (sheet === dialog) {
            sheet = null
            vm.clearPreview()
        }
    }
    dialog.show()
}
