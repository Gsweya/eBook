package com.gbook.ui.screens

import com.gbook.ui.components.plural
import com.gbook.ui.components.bottomBarClearance
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.GlassBackdrop
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.glass
import com.gbook.ui.components.pressable
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import com.gbook.ui.components.PrimaryButton
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
import androidx.compose.material.icons.rounded.AddReaction
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
import com.gbook.R
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.BookFormat
import com.gbook.data.Highlight
import com.gbook.ui.components.Avatar
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.ClassicNavBar
import com.gbook.ui.components.ReadingStatsCard
import com.gbook.ui.components.formatDuration
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

private enum class Tab(val label: String, val icon: ImageVector, val emoji: Int) {
    Library("Library", Icons.Rounded.LocalLibrary, R.drawable.noto3d_library),
    Feed("Feed", Icons.Rounded.DynamicFeed, R.drawable.noto3d_feed),
    Settings("Settings", Icons.Rounded.Settings, R.drawable.noto3d_settings),
    Search("Search", Icons.Rounded.Search, R.drawable.noto3d_search),
}

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val g = LocalG.current
    val haze = rememberHazeState()
    val data by vm.data.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    // Open settings sub-page, hoisted so the Library avatar can jump straight to the profile.
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var showThemes by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    // Nav bar labels fold away while scrolling down.
    var collapsed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importMany(uris) }
    val importBooks = { picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream")) }

    Box(Modifier.fillMaxSize().background(g.bg)) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            AnimatedContent(tab, label = "tab") { t ->
                when (t) {
                    Tab.Library -> LibraryTab(vm, nav, onImport = importBooks, onScrollDir = { collapsed = it })
                    Tab.Feed -> FeedTab(vm, nav, onGenerate = { showGenerate = true }, onScrollDir = { collapsed = it })
                    Tab.Settings -> SettingsTab(vm, settingsPage, { settingsPage = it }, onScrollDir = { collapsed = it })
                    Tab.Search -> SearchTab(vm, nav, onScrollDir = { collapsed = it })
                }
            }
        }
        // Fade content out under the status bar.
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(20.dp)
                .background(Brush.verticalGradient(listOf(g.bg, g.bg.copy(alpha = 0f)))),
        )
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(g.bg))
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
            if (tab == Tab.Library) Avatar(
                vm.avatarFile, data.settings.avatarVersion, data.settings.profileName, 44.dp,
                Modifier.align(Alignment.TopStart).clip(CircleShape).clickable { tab = Tab.Settings; settingsPage = "profile" },
            )
            when (tab) {
                Tab.Library -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconCircle(Icons.Rounded.Palette, "Themes", { showThemes = true }, size = 44.dp)
                    IconCircle(Icons.Rounded.Add, "Add books", importBooks, size = 44.dp)
                }
                Tab.Feed -> IconCircle(Icons.Rounded.AutoAwesome, "Create posts", { showGenerate = true }, size = 44.dp)
                else -> Unit
            }
        }
        val items = remember { Tab.entries.map { NavBarItem(it.label, it.icon, it.emoji) } }
        val onSelect = { i: Int ->
            if (Tab.entries[i] == Tab.Settings && tab == Tab.Settings) settingsPage = null
            tab = Tab.entries[i]; collapsed = false
        }
        val emoji = data.settings.navIcons == 1
        if (data.settings.navStyle == 1) {
            // Classic bar: Search is the last item, so it gets the separate round button.
            ClassicNavBar(items, tab.ordinal, onSelect, collapsed, haze, emoji, Modifier.align(Alignment.BottomCenter))
        } else {
            FloatingNavBar(
                items.dropLast(1), if (tab == Tab.Search) -1 else tab.ordinal, onSelect, collapsed, haze, emoji, Modifier.align(Alignment.BottomCenter),
                search = items.last(), onSearch = { tab = Tab.Search; collapsed = false },
            )
        }
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

/** What the Library tab shows, picked from the menu under its title. */
private enum class LibraryView(val title: String, val icon: ImageVector) {
    Overview("Library", Icons.Rounded.Dashboard),
    Books("All Books", Icons.Rounded.LocalLibrary),
    Collections("Collections", Icons.Rounded.CollectionsBookmark),
    Highlights("Highlights", Icons.Rounded.FormatQuote),
    Plans("Plans", Icons.Rounded.EventAvailable),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(vm: AppViewModel, nav: Nav, onImport: () -> Unit, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val books = remember(data.books) { data.books.sortedByDescending { maxOf(it.lastOpenedAt, it.addedAt) } }
    val plans = remember(data.plans, books) { data.plans.mapNotNull { p -> books.firstOrNull { it.id == p.bookId }?.let { p to it } } }
    val current = books.firstOrNull { it.lastOpenedAt > 0 }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableStateOf(LibraryView.Overview) }
    var showActivity by remember { mutableStateOf(false) }
    // Long-press a cover to start selecting books, e.g. to group them into a collection.
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    var addToCollection by remember { mutableStateOf<List<String>?>(null) }
    var newCollection by remember { mutableStateOf(false) }
    var pickPlanBook by remember { mutableStateOf(false) }
    var planBook by remember { mutableStateOf<Book?>(null) }
    var hlQuery by rememberSaveable { mutableStateOf("") }
    // Highlights grouped by book, the book with the newest highlight first.
    val hlGroups = remember(data.highlights, books) {
        val byId = books.associateBy { it.id }
        data.highlights.sortedByDescending { it.createdAt }.groupBy { it.bookId }.mapNotNull { (id, hs) -> byId[id]?.let { it to hs } }
    }
    BackHandler(selecting) { selected.clear() }
    BackHandler(!selecting && view != LibraryView.Overview) { view = LibraryView.Overview }
    addToCollection?.let { ids -> AddToCollectionSheet(vm, ids, onDismiss = { addToCollection = null; selected.clear() }) }
    if (newCollection) NameDialog("New Collection", "", "Create", onDismiss = { newCollection = false }) { name ->
        newCollection = false
        nav.push(Route.Collection(vm.createCollection(name)))
    }
    if (showActivity) ReadingActivitySheet(
        vm, onOpen = { b -> showActivity = false; vm.markOpened(b.id); nav.open(b) }, onDismiss = { showActivity = false },
    )
    if (pickPlanBook) PlanBookSheet(vm, books.filter { b -> b.progress < 0.995f && plans.none { it.second.id == b.id } }, onDismiss = { pickPlanBook = false }) {
        pickPlanBook = false; planBook = it
    }
    planBook?.let { PlanSheet(vm, it, onDismiss = { planBook = null }) }
    ScrollDirection(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, onScrollDir)
    LaunchedEffect(selecting) { if (selecting) onScrollDir(true) }

    val showBooks = view == LibraryView.Overview || view == LibraryView.Books
    Box(Modifier.fillMaxSize()) {
    GlassBackdrop(Modifier.fillMaxSize())
    // Six columns so book covers can take two each (three across) and collection cards three (two across).
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomBarClearance()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(6) }
        item(span = full) {
            Column(Modifier.statusBarsPadding().padding(top = 62.dp)) {
                LibraryViewPicker(
                    view,
                    mapOf(
                        LibraryView.Books to books.size, LibraryView.Collections to data.collections.size,
                        LibraryView.Highlights to data.highlights.size, LibraryView.Plans to plans.size,
                    ),
                    onPick = { selected.clear(); view = it; scope.launch { grid.scrollToItem(0) } },
                )
                when (view) {
                    LibraryView.Collections -> Text(plural(data.collections.size, "collection"), style = GType.secondary, color = g.text2)
                    LibraryView.Plans -> Text(plural(plans.size, "plan"), style = GType.secondary, color = g.text2)
                    LibraryView.Highlights -> Text(
                        "${data.highlights.size} saved across ${plural(hlGroups.size, "book")}", style = GType.secondary, color = g.text2,
                    )
                    else -> Unit
                }
            }
        }
        if (view == LibraryView.Overview) {
            item(span = full) {
                ReadingStatsCard(data.readingLog, data.books.sumOf { it.readingMs }, data.settings.dailyGoalMin, onClick = { showActivity = true })
            }
            if (plans.isNotEmpty()) item(span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Plans", style = GType.section, color = g.text, modifier = Modifier.padding(bottom = 2.dp))
                    plans.forEach { (p, b) -> PlanCard(vm, p, b, onClick = { nav.details(b.id) }, frosted = true) }
                }
            }
            if (current != null) {
                item(span = full) {
                    Column {
                        Text("Continue", style = GType.section, color = g.text, modifier = Modifier.padding(bottom = 12.dp))
                        ContinueCard(vm, current, onClick = { vm.markOpened(current.id); nav.open(current) })
                    }
                }
            }
            if (books.isNotEmpty()) item(span = full) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 12.dp)) {
                        Text("Collections", style = GType.section, color = g.text)
                        Spacer(Modifier.width(8.dp))
                        if (data.collections.isNotEmpty()) Text("${data.collections.size}", style = GType.secondary, color = g.text2, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    CollectionsRow(vm, data.collections, data.books, onOpen = { nav.push(Route.Collection(it.id)) }, onNew = { newCollection = true })
                }
            }
        }
        if (view == LibraryView.Collections) {
            val byId = books.associateBy { it.id }
            items(data.collections, key = { "c-" + it.id }, span = { GridItemSpan(3) }) { c ->
                CollectionCard(vm, c, c.bookIds.mapNotNull { byId[it] }, Modifier.animateItem()) { nav.push(Route.Collection(c.id)) }
            }
            item(key = "c-new", span = { GridItemSpan(3) }) { NewCollectionCard(Modifier.animateItem()) { newCollection = true } }
        }
        if (view == LibraryView.Highlights) {
            val term = hlQuery.trim()
            val bookHits = if (term.isEmpty()) hlGroups else hlGroups.filter { (b, _) ->
                b.title.contains(term, ignoreCase = true) || b.author.contains(term, ignoreCase = true)
            }
            val textHits = if (term.isEmpty()) emptyList() else hlGroups.flatMap { (b, hs) -> hs.filter { it.matches(term) }.map { it to b } }
            if (hlGroups.isNotEmpty()) item(span = full) { SearchField(hlQuery, { hlQuery = it }, "Search books or words in highlights") }
            when {
                hlGroups.isEmpty() -> item(span = full) {
                    EmptyState("No highlights yet", "Long-press text while reading, or arm the highlighter on the tracker wheel.", null) {}
                }
                bookHits.isEmpty() && textHits.isEmpty() -> item(span = full) {
                    EmptyState("No matches", "No book or highlight mentions “$term”.", null) {}
                }
            }
            items(bookHits, key = { "hb-" + it.first.id }, span = { GridItemSpan(6) }) { (b, hs) ->
                HighlightBookRow(vm, b, hs, onClick = { nav.push(Route.Highlights(b.id)) }, Modifier.animateItem())
            }
            if (textHits.isNotEmpty()) {
                item(span = full) { Text("In your highlights", style = GType.section, color = g.text, modifier = Modifier.padding(top = 6.dp)) }
                items(textHits, key = { "ht-" + it.first.id }, span = { GridItemSpan(6) }) { (h, b) ->
                    HighlightRow(vm, h, b, nav, Modifier.animateItem(), showBook = true)
                }
            }
        }
        if (view == LibraryView.Plans) {
            if (plans.isEmpty()) item(span = full) {
                EmptyState("No reading plans", "Pick a book and a finish date. GBook works out the daily pace and can remind you.", "Make a Plan") { pickPlanBook = true }
            } else {
                items(plans, key = { "p-" + it.first.bookId }, span = { GridItemSpan(6) }) { (p, b) ->
                    PlanCard(vm, p, b, onClick = { planBook = b }, Modifier.animateItem(), frosted = true)
                }
                item(span = full) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PillButton("New Plan", { pickPlanBook = true }, icon = Icons.Rounded.Add, height = 44.dp)
                    }
                }
            }
        }
        if (showBooks) item(span = full) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (view == LibraryView.Overview) {
                    Text("All Books", style = GType.section, color = g.text)
                    Spacer(Modifier.width(8.dp))
                    Text("${books.size}", style = GType.secondary, color = g.text2, modifier = Modifier.padding(bottom = 3.dp))
                } else Text(plural(books.size, "book"), style = GType.secondary, color = g.text2, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.weight(1f))
                if (books.isNotEmpty()) Text(
                    if (selecting) "Done" else "Select", color = g.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable { if (selecting) selected.clear() else selected.add(books.first().id) }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        if (showBooks && books.isEmpty()) {
            item(span = full) {
                EmptyState("Your library is empty", "Add EPUB or PDF books from your phone or Google Drive.", "Add Books", onImport)
            }
        }
        if (showBooks) items(books, key = { it.id }, span = { GridItemSpan(2) }) { b ->
            val on = b.id in selected
            Column(Modifier.animateItem()) {
                Box(
                    Modifier.combinedClickable(
                        onClick = {
                            if (selecting) { if (on) selected.remove(b.id) else selected.add(b.id) }
                            else { vm.markOpened(b.id); nav.open(b) }
                        },
                        onLongClick = { if (!on) selected.add(b.id) },
                    ),
                ) {
                    val scale by animateFloatAsState(if (on) 0.92f else 1f, label = "sel")
                    BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale })
                    if (selecting) SelectMark(on, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    else if (b.myReaction != null) {
                        Text(b.myReaction, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).panel(CircleShape, elevation = 2.dp).padding(4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = when {
                        b.progress >= 0.995f -> "Finished"
                        b.progress > 0f -> "${(b.progress * 100).toInt().coerceAtLeast(1)}%"
                        else -> "NEW"
                    }
                    Text(
                        label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (label == "NEW") g.accent else g.text2,
                    )
                    if (b.format == BookFormat.PDF) {
                        Spacer(Modifier.width(6.dp))
                        Text("PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = g.text2,
                            modifier = Modifier.background(g.fill, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.MoreHoriz, "Details", tint = g.text2,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { nav.details(b.id) })
                }
            }
        }
    }
    AnimatedVisibility(
        selecting, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
        enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
    ) {
        SelectionActions(selected.size, onCancel = { selected.clear() }) {
            PrimaryButton("Add to Collection", { addToCollection = selected.toList() }, icon = Icons.Rounded.CollectionsBookmark)
        }
    }
    }
}

/** The Library title doubles as a menu button: tap it to switch between the overview, books, collections and plans. */
@Composable
private fun LibraryViewPicker(view: LibraryView, counts: Map<LibraryView, Int>, onPick: (LibraryView) -> Unit) {
    val g = LocalG.current
    var open by remember { mutableStateOf(false) }
    val menu = remember { MutableTransitionState(false) }.apply { targetState = open }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val drop = with(LocalDensity.current) { 52.dp.roundToPx() }
    Box {
        Row(Modifier.pressable({ open = true }), verticalAlignment = Alignment.CenterVertically) {
            Text(view.title, style = GType.largeTitle, color = g.text)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(32.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown, "Choose what to show", tint = g.text,
                    modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
        if (menu.currentState || menu.targetState) Popup(
            offset = IntOffset(0, drop), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                menu,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(0.1f, 0f)),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(0.1f, 0f)),
            ) {
                Column(Modifier.padding(4.dp).width(260.dp).panel(RoundedCornerShape(24.dp), tint = g.sheet, elevation = 12.dp).padding(vertical = 6.dp)) {
                    LibraryView.entries.forEach { v ->
                        val on = v == view
                        Row(
                            Modifier.fillMaxWidth().clickable { open = false; if (!on) onPick(v) }.padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(v.icon, null, tint = if (on) g.accent else g.text2, modifier = Modifier.size(22.dp))
                            Text(
                                v.title, fontSize = 17.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = g.text,
                                modifier = Modifier.weight(1f).padding(start = 14.dp),
                            )
                            counts[v]?.let { Text("$it", fontSize = 15.sp, color = g.text2) }
                            Box(Modifier.padding(start = 10.dp).size(20.dp)) {
                                if (on) Icon(Icons.Rounded.Check, "Showing", tint = g.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Books that could get a reading plan; tapping one opens its plan sheet. */
@Composable
private fun PlanBookSheet(vm: AppViewModel, books: List<Book>, onDismiss: () -> Unit, onPick: (Book) -> Unit) {
    val g = LocalG.current
    GSheet(onDismiss, "Plan which book?", full = true, heightFraction = 0.8f) {
        if (books.isEmpty()) Text(
            "Every unfinished book already has a plan.", style = GType.secondary, color = g.text2,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(books, key = { it.id }) { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(b) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(56.dp), elevation = 3.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(b.title, fontSize = 16.sp, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            b.author + if (b.progress > 0f) " · ${(b.progress * 100).toInt()}%" else "",
                            fontSize = 14.sp, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Last-read book: a glass card washed very lightly with the cover's colour. */
@Composable
private fun ContinueCard(vm: AppViewModel, b: Book, onClick: () -> Unit) {
    val g = LocalG.current
    val wash = androidx.compose.ui.graphics.lerp(g.bg, Color(b.coverColor), 0.5f).copy(alpha = if (g.dark) 0.35f else 0.22f)
    Row(
        Modifier.fillMaxWidth().height(116.dp).glass(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(wash, Color.Transparent)))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(88.dp), elevation = 6.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, color = g.text, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.author, color = g.text2, fontSize = 15.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(g.fill)) {
                Box(Modifier.fillMaxWidth(b.progress.coerceAtLeast(0.01f)).height(4.dp).clip(CircleShape).background(g.accentBrush))
            }
            Text("${(b.progress * 100).toInt()}%" + (if (b.readingMs > 60_000) " · ${formatDuration(b.readingMs)} read" else "") + " · Tap to continue", color = g.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
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
