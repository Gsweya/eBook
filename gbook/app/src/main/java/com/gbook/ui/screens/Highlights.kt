package com.gbook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.Highlight
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.GlassBackdrop
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.bottomBarClearance
import com.gbook.ui.components.glass
import com.gbook.ui.components.plural
import com.gbook.ui.components.pressable
import com.gbook.ui.theme.AppSans
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif

/** Whether [h] or the book it's from matches a search [term] (already trimmed). */
fun Highlight.matches(term: String) = text.contains(term, ignoreCase = true) || note?.contains(term, ignoreCase = true) == true

/** Rounded search box. */
@Composable
fun SearchField(query: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val focusManager = LocalFocusManager.current
    Row(
        modifier.fillMaxWidth().height(48.dp).glass(CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = g.text2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = g.text2, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                query, onChange, singleLine = true,
                textStyle = TextStyle(color = g.text, fontSize = 16.sp, fontFamily = AppSans), cursorBrush = SolidColor(g.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Rounded.Cancel, "Clear", tint = g.text2, modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onChange("") })
    }
}

/** A book in the Library's Highlights view: cover, title, how many highlights, and the newest one. */
@Composable
fun HighlightBookRow(vm: AppViewModel, book: Book, highlights: List<Highlight>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    Row(
        modifier.fillMaxWidth().pressable(onClick).glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(72.dp), elevation = 3.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = GType.navTitle, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plural(highlights.size, "highlight"), style = GType.caption, color = g.text2)
            highlights.firstOrNull()?.let { h ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(HighlightColors[h.color % HighlightColors.size]))
                    Text(
                        "“${h.text}”", fontFamily = QuoteSerif, fontSize = 15.sp, color = g.text.copy(alpha = 0.85f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = g.text2, modifier = Modifier.padding(start = 6.dp))
    }
}

/** One highlight: colour bar, quote, note and reaction. Tapping opens the reader at it. */
@Composable
fun HighlightRow(vm: AppViewModel, h: Highlight, book: Book, nav: Nav, modifier: Modifier = Modifier, showBook: Boolean = false) {
    val g = LocalG.current
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).clickable {
            vm.markOpened(book.id)
            nav.push(Route.Reader(book.id, h.chapter, h.block))
        }.padding(16.dp),
    ) {
        Box(Modifier.width(4.dp).height(48.dp).clip(CircleShape).background(HighlightColors[h.color % HighlightColors.size]))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showBook) Text(book.title, style = GType.caption, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
            Text("“${h.text}”", fontFamily = QuoteSerif, fontSize = 17.sp, lineHeight = 24.sp, color = g.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (!h.note.isNullOrBlank()) Text(h.note, style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 6.dp))
        }
        // Reaction: shows the chosen emoji, or an add-reaction button; tap to pick.
        var picking by remember { mutableStateOf(false) }
        Box(Modifier.padding(start = 8.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (h.reaction != null) g.accent.copy(alpha = 0.15f) else g.fill)
                    .clickable { picking = true },
                contentAlignment = Alignment.Center,
            ) {
                if (h.reaction != null) Text(h.reaction, fontSize = 18.sp)
                else Icon(Icons.Rounded.AddReaction, "React", tint = g.text2, modifier = Modifier.size(20.dp))
            }
            if (picking) ReactionPicker(
                onPick = { vm.reactHighlight(h.id, it); picking = false }, onDismiss = { picking = false },
                alignment = Alignment.BottomEnd, offsetY = -120,
            )
        }
    }
}

/** Every highlight in one book, newest first, with a search box for words in them. */
@Composable
fun BookHighlightsScreen(vm: AppViewModel, id: String, nav: Nav) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val book = data.books.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val all = remember(data.highlights, id) { data.highlights.filter { it.bookId == id }.sortedByDescending { it.createdAt } }
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val shown = if (term.isEmpty()) all else all.filter { it.matches(term) }

    Box(Modifier.fillMaxSize().background(g.bg)) {
        GlassBackdrop(Modifier.fillMaxSize())
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomBarClearance()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 62.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(84.dp), elevation = 6.dp)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("Highlights", style = GType.eyebrow, color = g.text2)
                            Text(book.title, style = GType.section, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(plural(all.size, "highlight"), style = GType.secondary, color = g.text2)
                        }
                    }
                    if (all.isNotEmpty()) SearchField(query, { query = it }, "Search these highlights", Modifier.padding(top = 18.dp))
                }
            }
            if (all.isEmpty()) item {
                EmptyState("No highlights yet", "Long-press text while reading, or arm the highlighter on the tracker wheel.", null) {}
            } else if (shown.isEmpty()) item {
                EmptyState("No matches", "None of these highlights mention “$term”.", null) {}
            }
            items(shown, key = { it.id }) { h -> HighlightRow(vm, h, book, nav, Modifier.animateItem()) }
        }
        IconCircle(
            Icons.Rounded.ArrowBackIosNew, "Back", nav.pop,
            Modifier.statusBarsPadding().padding(start = 16.dp, top = 6.dp), size = 44.dp,
        )
    }
}
