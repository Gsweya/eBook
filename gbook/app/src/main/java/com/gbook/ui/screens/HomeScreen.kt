package com.gbook.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.DynamicFeed
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.BookFormat
import com.gbook.data.Highlight
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.FloatingNavBar
import com.gbook.ui.components.NavBarItem
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.panel
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Rounded.LocalLibrary),
    Feed("Feed", Icons.Rounded.DynamicFeed),
    Highlights("Highlights", Icons.Rounded.FormatQuote),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val g = LocalG.current
    val haze = rememberHazeState()
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var showThemes by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    // Nav bar labels fold away while scrolling down.
    var collapsed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) }
    }
    val importBooks = { picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream")) }

    Box(Modifier.fillMaxSize().background(g.bg)) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            AnimatedContent(tab, label = "tab") { t ->
                when (t) {
                    Tab.Library -> LibraryTab(vm, nav, onImport = importBooks, onScrollDir = { collapsed = it })
                    Tab.Feed -> FeedTab(vm, nav, onGenerate = { showGenerate = true }, onScrollDir = { collapsed = it })
                    Tab.Highlights -> HighlightsTab(vm, nav, onScrollDir = { collapsed = it })
                    Tab.Settings -> SettingsTab(vm, onScrollDir = { collapsed = it })
                }
            }
        }
        // Fade content out under the status bar.
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(20.dp)
                .background(Brush.verticalGradient(listOf(g.bg, g.bg.copy(alpha = 0f)))),
        )
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(g.bg))
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
            when (tab) {
                Tab.Library -> IconCircle(Icons.Rounded.Palette, "Themes", { showThemes = true }, size = 44.dp)
                Tab.Feed -> IconCircle(Icons.Rounded.AutoAwesome, "Create posts", { showGenerate = true }, size = 44.dp)
                else -> Unit
            }
        }
        FloatingNavBar(
            items = Tab.entries.map { NavBarItem(it.label, it.icon) },
            selected = tab.ordinal,
            onSelect = { tab = Tab.entries[it]; collapsed = false },
            onAdd = importBooks,
            collapsed = collapsed,
            haze = haze,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (showThemes) ThemeSheet(vm, onDismiss = { showThemes = false })
    if (showGenerate) GeneratePostsSheet(vm, onDismiss = { showGenerate = false })
}

@Composable
private fun LargeTitle(text: String, subtitle: String? = null) {
    val g = LocalG.current
    Column(Modifier.padding(start = 20.dp, end = 72.dp, top = 4.dp, bottom = 12.dp)) {
        Text(text, style = GType.largeTitle, color = g.text)
        if (subtitle != null) Text(subtitle, style = GType.secondary, color = g.text2)
    }
}

/** Reports scroll direction so the tab bar can collapse. */
@Composable
fun ScrollDirection(index: Int, offset: Int, onScrollDir: (Boolean) -> Unit) {
    var last by remember { mutableIntStateOf(0) }
    val pos = index * 10_000 + offset
    androidx.compose.runtime.LaunchedEffect(pos) {
        if (pos > last + 40) onScrollDir(true) else if (pos < last - 40 || pos == 0) onScrollDir(false)
        last = pos
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(vm: AppViewModel, nav: Nav, onImport: () -> Unit, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val books = remember(data.books) { data.books.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) } }
    val current = books.firstOrNull { it.lastOpenedAt > 0 }
    val grid = rememberLazyGridState()
    ScrollDirection(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, onScrollDir)

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.statusBarsPadding().padding(top = 48.dp)) {
                Text("Library", style = GType.largeTitle, color = g.text)
            }
        }
        if (current != null) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    Text("Continue", style = GType.section, color = g.text, modifier = Modifier.padding(bottom = 12.dp))
                    ContinueCard(vm, current, onClick = { vm.markOpened(current.id); nav.open(current) })
                }
            }
        }
        item(span = { GridItemSpan(3) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("All Books", style = GType.section, color = g.text)
                Spacer(Modifier.width(8.dp))
                Text("${books.size}", style = GType.secondary, color = g.text2, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (books.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                EmptyState("Your library is empty", "Add EPUB or PDF books from your phone or Google Drive.", "Add Books", onImport)
            }
        }
        items(books, key = { it.id }) { b ->
            Column(Modifier.animateItem()) {
                Box(Modifier.combinedClickable(onClick = { vm.markOpened(b.id); nav.open(b) }, onLongClick = { nav.push(Route.Detail(b.id)) })) {
                    BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.fillMaxWidth())
                    if (b.myReaction != null) {
                        Text(b.myReaction, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).panel(CircleShape, elevation = 2.dp).padding(4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = when {
                        b.progress >= 0.995f -> "Finished"
                        b.progress > 0f -> "${(b.progress * 100).toInt().coerceAtLeast(1)}%"
                        else -> "NEW"
                    }
                    Text(
                        label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (label == "NEW") g.accent else g.text2,
                    )
                    if (b.format == BookFormat.PDF) {
                        Spacer(Modifier.width(6.dp))
                        Text("PDF", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = g.text2,
                            modifier = Modifier.background(g.fill, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.MoreHoriz, "Details", tint = g.text2,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { nav.push(Route.Detail(b.id)) })
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(vm: AppViewModel, b: Book, onClick: () -> Unit) {
    val c = Color(b.coverColor)
    Row(
        Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.78f))))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(88.dp), elevation = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, color = Color.White, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.author, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(b.progress.coerceAtLeast(0.01f)).height(4.dp).background(Color.White))
            }
            Text("${(b.progress * 100).toInt()}% · Tap to continue", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = GType.section, color = g.text)
        Text(body, style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) com.gbook.ui.components.PrimaryButton(action, onAction)
    }
}

@Composable
private fun HighlightsTab(vm: AppViewModel, nav: Nav, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val groups = remember(data.highlights, data.books) {
        data.highlights.sortedByDescending { it.createdAt }.groupBy { it.bookId }
            .mapNotNull { (id, hs) -> data.books.firstOrNull { it.id == id }?.let { it to hs } }
    }
    val list = rememberLazyListState()
    ScrollDirection(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, onScrollDir)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Box(Modifier.statusBarsPadding().padding(top = 48.dp)) {
                LargeTitle("Highlights", "${data.highlights.size} saved across ${groups.size} books")
            }
        }
        if (groups.isEmpty()) item {
            EmptyState("No highlights yet", "Long-press any sentence while reading, drag to select, then pick a colour.", null) {}
        }
        groups.forEach { (book, hs) ->
            item(key = "h-" + book.id) {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(44.dp), elevation = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = GType.navTitle, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${hs.size} highlights", style = GType.caption, color = g.text2)
                    }
                }
            }
            items(hs, key = { it.id }) { h -> HighlightRow(vm, h, book, nav) }
        }
    }
}

@Composable
private fun HighlightRow(vm: AppViewModel, h: Highlight, book: Book, nav: Nav) {
    val g = LocalG.current
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(g.grouped).clickable {
                vm.markOpened(book.id)
                nav.push(Route.Reader(book.id, h.chapter, h.block))
            }.padding(16.dp),
    ) {
        Box(Modifier.width(4.dp).height(48.dp).clip(CircleShape).background(HighlightColors[h.color % HighlightColors.size]))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("“${h.text}”", fontFamily = QuoteSerif, fontSize = 17.sp, lineHeight = 24.sp, color = g.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (!h.note.isNullOrBlank()) Text(h.note, style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
