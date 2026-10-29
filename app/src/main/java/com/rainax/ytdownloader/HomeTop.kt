package com.rainax.ytdownloader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Home's top (brand bar, search box, title bar, the chips) and what floats over the list (search suggestions,
 * the loading spinner, the error message, the picks bar), drawn with Jetpack Compose. HomeScreen changes
 * [HomeChrome]; the buttons call [HomeChromeActions].
 */

/** One search suggestion: text, an optional small line and picture, and its icon (clock, search, trending). */
class Sug(val text: String, val sub: String? = null, val thumb: String? = null, val icon: Int = R.drawable.ic_search)

class HomeChrome {
    /** 0 = brand bar, 1 = search box, 2 = title bar (a list, playlist or channel). */
    var bar by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var titleDownload by mutableStateOf(false)
    var dark by mutableStateOf(false)

    var chipsShown by mutableStateOf(true)
    var chips by mutableStateOf<List<String>>(emptyList())
    var chipSel by mutableIntStateOf(0)

    /** The search box's text (with the cursor). */
    var input by mutableStateOf(TextFieldValue(""))
    var search: String
        get() = input.text
        set(v) { input = TextFieldValue(v, TextRange(v.length)) }
    var suggestOpen by mutableStateOf(false)
    /** Bumped to put the cursor in the box (and show the keyboard) / take it out (and hide the keyboard). */
    var focusReq by mutableIntStateOf(0)
    var unfocusReq by mutableIntStateOf(0)

    var stubs by mutableStateOf<List<Sug>>(emptyList())
    var sugItems by mutableStateOf<List<Sug>>(emptyList())
    var showClear by mutableStateOf(false)
    var mayLike by mutableStateOf<List<Sug>>(emptyList())

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var errorOffline by mutableStateOf(false)
    /** Videos picked with + (0 = no bar). */
    var picks by mutableIntStateOf(0)

    fun setSuggestions(list: List<Sug>, mayLike: List<Sug> = emptyList(), showClear: Boolean = false, stubs: List<Sug> = emptyList()) {
        this.stubs = stubs
        this.sugItems = list
        this.showClear = showClear
        this.mayLike = mayLike
    }
}

class HomeChromeActions(
    val menu: () -> Unit,
    val theme: (android.view.View) -> Unit,
    val openSearch: () -> Unit,
    val voice: () -> Unit,
    val back: () -> Unit,
    val titleDownload: () -> Unit,
    val chip: (Int) -> Unit,
    val typed: (String) -> Unit,
    val focused: () -> Unit,
    val submit: (String) -> Unit,
    val clearText: () -> Unit,
    val fill: (String) -> Unit,
    val clearHistory: () -> Unit,
    val removeRecent: (String) -> Unit,
    val retry: () -> Unit,
    val netSettings: () -> Unit,
    val picksView: () -> Unit,
    val picksClear: () -> Unit,
    val picksDownload: () -> Unit
)

@Composable
internal fun HomeTopBar(c: HomeChrome, a: HomeChromeActions) {
    val rx = LocalRx.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth()) {
        if (c.bar == 0) {
            // layout A "Clean feed": the large bold title on the left, round white buttons on the right
            // (theme, search — hold for voice — and the menu ≡; your picture is on the tab bar's You)
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Home", Modifier.weight(1f), color = rx.text, fontSize = 34.sp, lineHeight = 40.sp, fontFamily = ModernFonts.family,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em, maxLines = 1)
                RoundTopButton("Theme", onClick = { a.theme(view) }, onPlaced = { r -> ThemeAnchor.rect = r }) {
                    Image(painterResource(if (c.dark) R.drawable.ic_moon else R.drawable.ic_brightness), null, Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(rx.text))
                }
                RoundTopButton("Search", onClick = { a.openSearch() }, onLong = { a.voice() }) {
                    Image(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text))
                }
                RoundTopButton("Menu", onClick = { a.menu() }) {
                    Image(painterResource(R.drawable.ic_menu), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text))
                }
            }
        } else
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            when (c.bar) {
                1 -> SearchBar(c, a)
                else -> Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TopIcon(R.drawable.ic_back, "Back") { a.back() }
                    Text(c.title, Modifier.weight(1f).padding(start = 4.dp), color = rx.text, fontSize = 22.sp,
                        fontFamily = rxBodyFont(), fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (c.titleDownload) {
                        Button(
                            onClick = { a.titleDownload() }, modifier = Modifier.height(40.dp),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                        ) {
                            Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                            Text("Download all", Modifier.padding(start = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = rxBodyFont())
                        }
                    }
                }
            }
        }
        if (c.chipsShown && c.chips.isNotEmpty()) {
            val chipList = rememberLazyListState()
            LaunchedEffect(c.chipSel, c.chips) { if (c.chipSel in c.chips.indices) chipList.animateScrollToItem((c.chipSel - 1).coerceAtLeast(0)) }
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), state = chipList,
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(c.chips) { i, label ->
                    RxChip(label, selected = i == c.chipSel) { if (i != c.chipSel) { Haptics.tick(view); a.chip(i) } }
                }
            }
        }
    }
}

/** A 44dp round white button for Home's title row (layout A). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundTopButton(
    label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null,
    onPlaced: ((android.graphics.Rect) -> Unit)? = null, content: @Composable () -> Unit
) {
    val rx = LocalRx.current
    Box(
        Modifier.padding(start = 8.dp).size(44.dp)
            .then(if (onPlaced != null) Modifier.onGloballyPositioned { c ->
                val b = c.boundsInWindow()
                onPlaced(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
            } else Modifier)
            .clip(CircleShape).background(rx.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun TopIcon(icon: Int, label: String, onClick: () -> Unit) {
    val rx = LocalRx.current
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Image(painterResource(icon), label, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
    }
}

/** The glowing search box with Clear and the red mic. */
@Composable
private fun SearchBar(c: HomeChrome, a: HomeChromeActions) {
    val rx = LocalRx.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(c.focusReq) { if (c.focusReq > 0) { runCatching { focus.requestFocus() }; keyboard?.show() } }
    LaunchedEffect(c.unfocusReq) { if (c.unfocusReq > 0) { focusManager.clearFocus(); keyboard?.hide() } }
    // the box eases in when it opens
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "searchIn")
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp).alpha(fade), verticalAlignment = Alignment.CenterVertically) {
        TopIcon(R.drawable.ic_back, "Back") { a.back() }
        Row(
            Modifier.weight(1f).height(48.dp).padding(end = 8.dp).then(
                Modifier.background(rx.surface, RoundedCornerShape(50))
            ).padding(start = 18.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_search), null, Modifier.padding(end = 10.dp).size(20.dp), colorFilter = ColorFilter.tint(rx.accentText))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (c.input.text.isEmpty()) Text("Search YouTube or paste a link", color = rx.text2, fontSize = 14.sp, fontFamily = rxBodyFont(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = c.input,
                    onValueChange = { v -> val changed = v.text != c.input.text; c.input = v; if (changed) a.typed(v.text) },
                    singleLine = true,
                    textStyle = TextStyle(color = rx.text, fontSize = 14.sp, fontFamily = rxBodyFont()),
                    cursorBrush = SolidColor(rx.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { a.submit(c.input.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { if (it.isFocused) a.focused() }
                )
            }
            if (c.input.text.isNotEmpty()) {
                Box(
                    Modifier.padding(start = 4.dp).size(30.dp).rxDrawableBg(R.drawable.bg_clear_circle).clickable { a.clearText() }
                        .semantics { contentDescription = "Clear" }.padding(8.dp)
                ) { Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2)) }
            } else {
                Box(
                    Modifier.padding(start = 6.dp).size(38.dp).then(
                        Modifier.background(rx.accent, CircleShape)
                    ).clickable { a.voice() }
                        .semantics { contentDescription = "Search with your voice" }.padding(9.dp)
                ) { Image(painterResource(R.drawable.ic_mic), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.onAccent)) }
            }
        }
    }
}

/** What floats over Home's list: suggestions, the spinner, the error, the picks bar. */
@Composable
internal fun HomeOverlay(c: HomeChrome, a: HomeChromeActions) {
    val rx = LocalRx.current
    Box(Modifier.fillMaxSize()) {
        if (c.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = rx.accent)
        val err = c.error
        if (err != null && !c.suggestOpen) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_film_reel), null, Modifier.size(56.dp), colorFilter = ColorFilter.tint(rx.accentText))
                Text(err, Modifier.padding(top = 12.dp), color = rx.text2, fontSize = 14.sp, fontFamily = rxBodyFont(), textAlign = TextAlign.Center)
                Button(
                    onClick = { a.retry() }, modifier = Modifier.padding(top = 16.dp), shape = rxShape(24),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.accentText)
                ) { Text("Try again", fontFamily = rxBodyFont(), fontWeight = FontWeight.Bold) }
                if (c.errorOffline) {
                    TextButton(onClick = { a.netSettings() }) { Text("Internet settings", color = rx.accentText, fontFamily = rxBodyFont(), fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (c.picks > 0 && !c.suggestOpen) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().height(60.dp)
                    .then(Modifier.background(rx.card, RoundedCornerShape(24.dp))).padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (c.picks == 1) "1 selected · View" else "${c.picks} selected · View", Modifier.weight(1f).clickable { a.picksView() },
                    color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = rxBodyFont())
                TextButton(onClick = { a.picksClear() }) { Text("Clear", color = rx.accentText, fontFamily = rxBodyFont(), fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { a.picksDownload() }, modifier = Modifier.height(44.dp), shape = rxShape(24),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                ) {
                    Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                    Text(if (c.picks > 1) "Download ${c.picks}" else "Download", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontFamily = rxBodyFont())
                }
            }
        }
        if (c.suggestOpen) Suggestions(c, a)
    }
}

/** Search suggestions, like YouTube. Empty box: recent searches, "Clear search history", then "You may like". */
@Composable
private fun Suggestions(c: HomeChrome, a: HomeChromeActions) {
    val rx = LocalRx.current
    LazyColumn(
        Modifier.fillMaxSize().background(rx.bg)
            // the list takes every touch (nothing reaches the videos underneath)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        if (c.stubs.isNotEmpty()) {
            item(key = "stubs") {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
                    Text(rxHeadCase("Recent searches"), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), color = rx.text2, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.em)
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        itemsIndexed(c.stubs) { _, s -> RecentStub(s, a) }
                    }
                }
            }
        }
        itemsIndexed(c.sugItems, key = { i, s -> "s$i:" + s.text }) { _, s -> SugRow(s, a) }
        if (c.showClear) {
            item(key = "clear") {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { a.clearHistory() }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_delete), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.accentText))
                        Text(rxHeadCase("Clear search history"), Modifier.padding(start = 20.dp), color = rx.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.em, fontFamily = rxBodyFont())
                    }
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().height(1.dp).background(rx.divider))
                }
            }
        }
        if (c.mayLike.isNotEmpty()) {
            item(key = "mayLike") {
                Text(rxHeadCase("You may like"), Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), color = rx.text, fontSize = 24.sp,
                    fontFamily = rxHeadFont())
            }
            itemsIndexed(c.mayLike, key = { i, s -> "m$i:" + s.text }) { _, s -> SugRow(s, a) }
        }
    }
}

/** A recent search as a ticket stub: tap searches, hold for Search / Edit / Remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentStub(s: Sug, a: HomeChromeActions) {
    val rx = LocalRx.current
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.padding(horizontal = 4.dp).height(44.dp).widthIn(max = 180.dp).rxDrawableBg(R.drawable.bg_recent_stub)
                .combinedClickable(onClick = { a.submit(s.text) }, onLongClick = { Haptics.press(view); menu = true })
                .semantics { contentDescription = "Search ${s.text}. Hold for edit or remove" }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) { Text(s.text, color = rx.text, fontSize = 14.sp, fontFamily = rxBodyFont(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        RecentMenu(menu, { menu = false }, s.text, a)
    }
}

@Composable
private fun RecentMenu(open: Boolean, close: () -> Unit, q: String, a: HomeChromeActions) {
    DropdownMenu(expanded = open, onDismissRequest = close) {
        DropdownMenuItem(text = { Text("Search", fontFamily = rxBodyFont()) }, onClick = { close(); a.submit(q) })
        DropdownMenuItem(text = { Text("Edit", fontFamily = rxBodyFont()) }, onClick = { close(); a.fill(q) })
        DropdownMenuItem(text = { Text("Remove", fontFamily = rxBodyFont()) }, onClick = { close(); a.removeRecent(q) })
    }
}

/** One suggestion: its icon, the words (and a small line), a picture, and ↖ to keep typing from it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SugRow(s: Sug, a: HomeChromeActions) {
    val rx = LocalRx.current
    val view = LocalView.current
    val recent = s.icon == R.drawable.ic_history
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .combinedClickable(onClick = { a.submit(s.text) }, onLongClick = if (recent) ({ Haptics.press(view); menu = true }) else null)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(s.icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                Text(s.text, color = rx.text, fontSize = 16.sp, fontFamily = rxBodyFont(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!s.sub.isNullOrBlank()) Text(s.sub, color = rx.text2, fontSize = 14.sp, fontFamily = rxBodyFont(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.thumb != null) {
                Box(Modifier.padding(start = 10.dp).size(72.dp, 40.dp).rxDrawableBg(R.drawable.bg_thumb)) {
                    RxNetImage(s.thumb, Modifier.fillMaxSize(), widthPx = 240)
                }
            }
            Box(Modifier.padding(start = 4.dp).size(44.dp).clickable { a.fill(s.text) }.semantics { contentDescription = "Edit ${s.text}" }.padding(11.dp)) {
                Image(painterResource(R.drawable.ic_north_west), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
            }
        }
        if (recent) RecentMenu(menu, { menu = false }, s.text, a)
    }
}

/** Where Home's round Theme button sits in the window, so the theme menu opens right under it. */
internal object ThemeAnchor { var rect: android.graphics.Rect? = null }
