package com.rainax.ytdownloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The comments sheet drawn with Jetpack Compose (it replaced sheet_comments.xml and item_comment.xml).
 * CommentsSheet keeps the loading, liking and posting; this file only shows [CommentsUi].
 */

/** What the comments sheet shows. The rows themselves live in CommentsSheet; [tick] redraws them. */
class CommentsUi {
    var count by mutableStateOf("")
    var sorts by mutableStateOf<List<String>>(emptyList())
    var sort by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var empty by mutableStateOf<String?>(null)
    var rows by mutableStateOf<List<YtComments.Item>>(emptyList())
    var tick by mutableIntStateOf(0)
    var signedIn by mutableStateOf(false)
    var myAvatar by mutableStateOf<String?>(null)
    var replyingTo by mutableStateOf<String?>(null)
    var input by mutableStateOf(TextFieldValue(""))
    var sending by mutableStateOf(false)
    /** Rich reader (likes, replies) works for this video. */
    var full by mutableStateOf(true)
    internal var toTop by mutableIntStateOf(0)
    internal var focusAsk by mutableIntStateOf(0)
    internal var hideAsk by mutableIntStateOf(0)

    fun scrollToTop() { toTop++ }
    /** Puts the cursor in the text box and opens the keyboard. */
    fun focusInput() { focusAsk++ }
    fun hideKeyboard() { hideAsk++ }
}

class CommentsActions(
    val sort: (Int) -> Unit,
    val nearEnd: () -> Unit,
    val like: (YtComments.Item) -> Unit,
    val dislike: (YtComments.Item) -> Unit,
    val reply: (YtComments.Item) -> Unit,
    val replies: (YtComments.Item) -> Unit,
    /** The text box was tapped while signed out. */
    val signIn: () -> Unit,
    val cancelReply: () -> Unit,
    val send: () -> Unit,
    /** Shows "N replies" / "Hide replies" under this comment (null = no button). */
    val repliesLabel: (YtComments.Item) -> String?,
    val canReply: (YtComments.Item) -> Boolean
)

private val EMOJIS = listOf("❤️", "😂", "🎉", "😢", "😮", "😅", "😊")
private val URL = Regex("https?://[^\\s<>\"]+")

/** Comment text with web links you can tap. */
private fun linked(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in URL.findAll(text)) {
        append(text.substring(at, m.range.first))
        val url = m.value.trimEnd('.', ',', ')', '!', '?')
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color)))) { append(url) }
        at = m.range.first + url.length
    }
    append(text.substring(at))
}

@Composable
internal fun CommentsScreen(ui: CommentsUi, a: CommentsActions) {
    val rx = LocalRx.current
    val state = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(ui.toTop) { if (ui.toTop > 0) state.scrollToItem(0) }
    LaunchedEffect(ui.focusAsk) {
        if (ui.focusAsk > 0) { runCatching { focus.requestFocus() }; keyboard?.show() }
    }
    LaunchedEffect(ui.hideAsk) {
        if (ui.hideAsk > 0) { keyboard?.hide(); focusManager.clearFocus() }
    }
    // more comments while scrolling
    LaunchedEffect(state) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to state.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (total > 0 && last >= total - 4) a.nearEnd() }
    }

    Column(Modifier.fillMaxSize().background(rx.bg).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("COMMENTS", color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display, letterSpacing = 0.02.em)
            Text(ui.count, Modifier.weight(1f).padding(start = 8.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        }
        if (ui.sorts.size >= 2) {
            LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                items(ui.sorts.size) { i ->
                    Box(Modifier.padding(end = 8.dp)) { RxChip(ui.sorts[i], ui.sort == i) { if (ui.sort != i) a.sort(i) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(rx.outline))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            @Suppress("UNUSED_VARIABLE") val t = ui.tick
            val rows = ui.rows
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()),
                state = state, contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(rows.size) { i -> CommentRow(rows[i], ui.full, a) }
            }
            if (ui.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = rx.accent)
            } else if (ui.empty != null && rows.isEmpty()) {
                Text(ui.empty.orEmpty(), Modifier.align(Alignment.Center).padding(24.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
            }
        }

        // replying to someone
        ui.replyingTo?.let { who ->
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(rx.surfaceAlt).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Replying to $who", Modifier.weight(1f), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(36.dp).clickable { a.cancelReply() }.semantics { contentDescription = "Cancel reply" }.padding(8.dp)) {
                    Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
        }

        // write a comment (signed in)
        Row(
            Modifier.fillMaxWidth().background(rx.card).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                RxNetImage(ui.myAvatar, Modifier.fillMaxSize(), circle = true, widthPx = 96)
            }
            val hint = when {
                !ui.signedIn -> "Sign in to comment"
                ui.replyingTo != null -> "Add a reply…"
                else -> "Add a comment…"
            }
            Box(
                Modifier.weight(1f).padding(start = 10.dp).heightIn(min = 40.dp).rxDrawableBg(R.drawable.bg_search)
                    .then(if (ui.signedIn) Modifier else Modifier.clickable { a.signIn() })
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (ui.input.text.isEmpty()) Text(hint, color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                if (ui.signedIn) {
                    BasicTextField(
                        value = ui.input,
                        onValueChange = { ui.input = it },
                        maxLines = 4,
                        textStyle = TextStyle(color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body),
                        cursorBrush = SolidColor(rx.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    )
                }
            }
            Box(
                Modifier.padding(start = 4.dp).size(44.dp).alpha(if (ui.sending) 0.4f else 1f)
                    .clickable(enabled = !ui.sending) { a.send() }.semantics { contentDescription = "Post comment" }.padding(10.dp)
            ) {
                Image(painterResource(R.drawable.ic_send), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.accent))
            }
        }

        // quick emoji while typing, like YouTube: tap one to put it where the cursor is
        if (focused && ui.signedIn) {
            Row(Modifier.fillMaxWidth().background(rx.card).padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
                EMOJIS.forEach { e ->
                    Box(
                        Modifier.weight(1f).height(44.dp).clickable {
                            val v = ui.input
                            val at = v.selection.start.coerceIn(0, v.text.length)
                            ui.input = TextFieldValue(v.text.substring(0, at) + e + v.text.substring(at), TextRange(at + e.length))
                        },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 24.sp) }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: YtComments.Item, full: Boolean, a: CommentsActions) {
    val rx = LocalRx.current
    Row(Modifier.fillMaxWidth().padding(start = if (c.isReply) 56.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
            RxNetImage(c.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 96)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(listOfNotNull(c.author.takeIf { it.isNotBlank() }, c.date).joinToString(" • "),
                color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
            Text(linked(c.text, rx.accent), Modifier.padding(top = 2.dp), color = rx.text, fontSize = 14.sp, lineHeight = 19.sp,
                fontFamily = RxFonts.body)
            // like / dislike / reply
            val rateable = !c.mine && full
            Row(Modifier.height(40.dp).padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(40.dp).alpha(if (rateable) 1f else 0.6f)
                        .clickable(enabled = rateable) { a.like(c) }.semantics { contentDescription = "Like" }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.ic_thumb_up), null, Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(if (c.likeState == "LIKE") rx.accent else rx.text))
                    if (!c.likeCount.isNullOrBlank()) {
                        Text(c.likeCount.orEmpty(), Modifier.padding(start = 6.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                    }
                }
                if (rateable) {
                    Box(Modifier.size(38.dp, 40.dp).clickable { a.dislike(c) }.semantics { contentDescription = "Dislike" }.padding(10.dp)) {
                        Image(painterResource(R.drawable.ic_thumb_down), null, Modifier.fillMaxSize(),
                            colorFilter = ColorFilter.tint(if (c.likeState == "DISLIKE") rx.accent else rx.text))
                    }
                }
                if (a.canReply(c)) {
                    Box(Modifier.padding(start = 8.dp).size(38.dp, 40.dp).clickable { a.reply(c) }.semantics { contentDescription = "Reply" }.padding(9.dp)) {
                        Image(painterResource(R.drawable.ic_reply), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
                    }
                }
            }
            a.repliesLabel(c)?.let { label ->
                Text(label, Modifier.padding(top = 6.dp).clickable { a.replies(c) }.padding(vertical = 4.dp),
                    color = rx.accent, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
            }
        }
    }
}
