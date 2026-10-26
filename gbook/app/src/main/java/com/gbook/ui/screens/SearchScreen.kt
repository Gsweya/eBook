package com.gbook.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.BookFormat
import com.gbook.ui.components.BookCover
import com.gbook.ui.theme.AppSans
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import java.text.Normalizer

/** Accent- and case-insensitive form for matching ("Café" matches "cafe"). */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Search tab: find books by title or author, collections by name, and highlights by their text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchTab(vm: AppViewModel, nav: Nav, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val list = rememberLazyListState()
    ScrollDirection(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, onScrollDir)
    LaunchedEffect(Unit) { if (q.isEmpty()) focus.requestFocus() }

    val term = fold(q.trim())
    val books = remember(term, data.books) {
        if (term.isEmpty()) data.books.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }.take(8)
        else data.books.filter { fold(it.title).contains(term) || fold(it.author).contains(term) }
            .sortedBy { if (fold(it.title).startsWith(term)) 0 else if (fold(it.title).contains(term)) 1 else 2 }
    }
    val collections = remember(term, data.collections) {
        if (term.isEmpty()) data.collections else data.collections.filter { fold(it.name).contains(term) }
    }
    val highlights = remember(term, data.highlights) {
        if (term.length < 2) emptyList() else data.highlights.filter { fold(it.text).contains(term) || fold(it.note.orEmpty()).contains(term) }.take(30)
    }
    val open = { b: Book -> focusManager.clearFocus(); vm.markOpened(b.id); nav.open(b) }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 48.dp, start = 20.dp, end = 20.dp)) {
                Text("Search", style = GType.largeTitle, color = g.text, modifier = Modifier.padding(bottom = 12.dp))
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(g.fill).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = g.text2)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (q.isEmpty()) Text("Books, authors, collections, highlights", color = g.text2, fontSize = 17.sp, maxLines = 1)
                        BasicTextField(
                            q, { q = it }, singleLine = true,
                            textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = AppSans), cursorBrush = SolidColor(g.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (q.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Clear", tint = g.text2, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { q = "" })
                }
            }
        }
        if (term.isNotEmpty() && books.isEmpty() && collections.isEmpty() && highlights.isEmpty()) item {
            EmptyState("No results", "Nothing in your library matches “${q.trim()}”.", null) {}
        }
        if (collections.isNotEmpty()) {
            item { SearchSection("Collections") }
            items(collections, key = { "c-" + it.id }) { c ->
                val first = c.bookIds.firstNotNullOfOrNull { id -> data.books.firstOrNull { it.id == id } }
                ResultRow(
                    onClick = { focusManager.clearFocus(); nav.push(Route.Collection(c.id)) },
                    leading = {
                        if (first != null) BookCover(vm.coverPath(first), first.title, first.author, first.coverColor, Modifier.height(56.dp), elevation = 3.dp)
                        else Box(Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(g.fill), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.CollectionsBookmark, null, tint = g.text2)
                        }
                    },
                    title = highlight(c.name, term), subtitle = "Collection · ${c.bookIds.size} books",
                )
            }
        }
        if (books.isNotEmpty()) {
            item { SearchSection(if (term.isEmpty()) "Recently Opened" else "Books") }
            items(books, key = { "b-" + it.id }) { b ->
                ResultRow(
                    onClick = { open(b) },
                    onLongClick = { focusManager.clearFocus(); nav.push(Route.Detail(b.id)) },
                    leading = { BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(56.dp), elevation = 3.dp) },
                    title = highlight(b.title, term),
                    subtitle = listOfNotNull(
                        b.author,
                        if (b.format == BookFormat.PDF) "PDF" else null,
                        when { b.progress >= 0.995f -> "Finished"; b.progress > 0f -> "${(b.progress * 100).toInt()}%"; else -> null },
                    ).joinToString(" · "),
                )
            }
        }
        if (highlights.isNotEmpty()) {
            item { SearchSection("In Your Highlights") }
            items(highlights, key = { "h-" + it.id }) { h ->
                val b = data.books.firstOrNull { it.id == h.bookId } ?: return@items
                Column(
                    Modifier.fillMaxWidth().clickable { focusManager.clearFocus(); vm.markOpened(b.id); nav.push(Route.Reader(b.id, h.chapter, h.block)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(b.title, style = GType.caption, color = g.text2, maxLines = 1)
                    Text(snippet(h.text, term), fontFamily = QuoteSerif, fontSize = 16.sp, color = g.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
            }
        }
    }
}

@Composable
private fun SearchSection(title: String) {
    Text(title, style = GType.section, color = LocalG.current.text, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: androidx.compose.ui.text.AnnotatedString,
    subtitle: String,
    onLongClick: (() -> Unit)? = null,
) {
    val g = LocalG.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 17.sp, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 14.sp, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [text] with the (folded) match of [term] in bold. */
private fun highlight(text: String, term: String) = buildAnnotatedString {
    val i = if (term.isEmpty()) -1 else fold(text).indexOf(term)
    // Folding can change length for some scripts; only bold when offsets still line up.
    if (i < 0 || fold(text).length != text.length) { append(text); return@buildAnnotatedString }
    append(text.substring(0, i))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i, i + term.length)) }
    append(text.substring(i + term.length))
}

/** Part of a highlight around the match, so long highlights still show why they matched. */
private fun snippet(text: String, term: String): androidx.compose.ui.text.AnnotatedString {
    val i = fold(text).indexOf(term)
    if (i < 0 || fold(text).length != text.length) return highlight(text, term)
    val s = (i - 50).coerceAtLeast(0)
    return highlight((if (s > 0) "…" else "") + text.substring(s), term)
}
