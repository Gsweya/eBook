package com.gbook.ui.screens

import com.gbook.ui.components.bottomBarClearance
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.BookCollection
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.panel
import com.gbook.ui.theme.AppSans
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.LocalG

/** Library row of collection cards, ending with a "New Collection" card. */
@Composable
fun CollectionsRow(vm: AppViewModel, collections: List<BookCollection>, books: List<Book>, onOpen: (BookCollection) -> Unit, onNew: () -> Unit) {
    val byId = remember(books) { books.associateBy { it.id } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
        items(collections, key = { it.id }) { c ->
            CollectionCard(vm, c, c.bookIds.mapNotNull { byId[it] }, Modifier.width(150.dp)) { onOpen(c) }
        }
        item(key = "new") { NewCollectionCard(Modifier.width(150.dp), onNew) }
    }
}

/** Fanned stack of up to three covers over a tinted tile, with the collection's name and size. */
@Composable
fun CollectionCard(vm: AppViewModel, c: BookCollection, books: List<Book>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val g = LocalG.current
    Column(modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(22.dp)).background(g.grouped)
                .border(0.75.dp, g.separator, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val shown = books.take(3)
            if (shown.isEmpty()) Icon(Icons.Rounded.CollectionsBookmark, null, tint = g.text2, modifier = Modifier.size(36.dp))
            shown.asReversed().forEachIndexed { i, b ->
                val k = shown.size - 1 - i
                BookCover(
                    vm.coverPath(b), b.title, b.author, b.coverColor,
                    Modifier.height(96.dp).offset(x = (k - (shown.size - 1) / 2f).times(22).dp)
                        .graphicsLayer { rotationZ = (k - (shown.size - 1) / 2f) * 7f },
                    elevation = 6.dp,
                )
            }
        }
        Text(c.name, style = GType.navTitle, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Text(if (books.size == 1) "1 book" else "${books.size} books", style = GType.caption, color = g.text2, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NewCollectionCard(modifier: Modifier, onClick: () -> Unit) {
    val g = LocalG.current
    Column(modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(22.dp))
                .border(1.5.dp, g.separator, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(g.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = g.accent)
            }
        }
        Text("New Collection", style = GType.navTitle, color = g.text, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Text("Group your books", style = GType.caption, color = g.text2, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A collection's books, with rename, add books, select-to-remove and delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(vm: AppViewModel, id: String, nav: Nav) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val c = data.collections.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val books = remember(c, data.books) { c.bookIds.mapNotNull { bid -> data.books.firstOrNull { it.id == bid } } }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()

    Box(Modifier.fillMaxSize().background(g.bg)) {
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomBarClearance()),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column(Modifier.statusBarsPadding().padding(top = 62.dp)) {
                    Text(c.name, style = GType.largeTitle, color = g.text, modifier = Modifier.clickable { renaming = true })
                    Text(if (books.size == 1) "1 book" else "${books.size} books", style = GType.secondary, color = g.text2)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Add Books", { adding = true }, icon = Icons.Rounded.Add, height = 42.dp)
                        PillButton("Rename", { renaming = true }, icon = Icons.Rounded.Edit, height = 42.dp)
                    }
                }
            }
            if (books.isEmpty()) item(span = { GridItemSpan(3) }) {
                EmptyState("Nothing here yet", "Add books from your library to this collection.", "Add Books") { adding = true }
            }
            items(books, key = { it.id }) { b ->
                val on = b.id in selected
                Column(Modifier.animateItem()) {
                    Box(
                        Modifier.combinedClickable(
                            onClick = { if (selecting) { if (on) selected.remove(b.id) else selected.add(b.id) } else { vm.markOpened(b.id); nav.open(b) } },
                            onLongClick = { if (!on) selected.add(b.id) },
                        ),
                    ) {
                        BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.fillMaxWidth().graphicsLayer { val k = if (on) 0.92f else 1f; scaleX = k; scaleY = k })
                        if (selecting) SelectMark(on, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Text(b.title, style = GType.caption, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Rounded.ArrowBackIosNew, "Back", nav.pop, size = 44.dp)
            Spacer(Modifier.weight(1f))
            IconCircle(Icons.Rounded.DeleteOutline, "Delete collection", { confirmDelete = true }, size = 44.dp, iconTint = g.destructive)
        }
        if (selecting) SelectionActions(
            count = selected.size, Modifier.align(Alignment.BottomCenter),
            onCancel = { selected.clear() },
        ) {
            PrimaryButton("Remove", { vm.removeFromCollection(c.id, selected.toList()); selected.clear() }, icon = Icons.Rounded.RemoveCircleOutline)
        }
    }
    if (renaming) NameDialog("Rename Collection", c.name, "Save", onDismiss = { renaming = false }) { vm.renameCollection(c.id, it); renaming = false }
    if (adding) BookPickerSheet(vm, title = "Add to “${c.name}”", initial = c.bookIds, onDismiss = { adding = false }) { ids ->
        vm.addToCollection(c.id, ids - c.bookIds.toSet())
        vm.removeFromCollection(c.id, c.bookIds - ids.toSet())
        adding = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        shape = RoundedCornerShape(34.dp),
        containerColor = g.sheet,
        title = { Text("Delete “${c.name}”?") },
        text = { Text("The books stay in your library; only the collection is removed.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteCollection(c.id); nav.pop() }) { Text("Delete", color = g.destructive) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = g.text) } },
    )
}

/** Round check badge shown on covers while selecting. */
@Composable
fun SelectMark(on: Boolean, modifier: Modifier = Modifier) {
    val g = LocalG.current
    Box(
        modifier.size(26.dp).clip(CircleShape).background(if (on) g.accent else Color.Black.copy(alpha = 0.28f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Rounded.Check, "Selected", tint = g.onAccent, modifier = Modifier.size(16.dp)) }
}

/** Floating bar shown while books are selected: count, cancel and the screen's actions. */
@Composable
fun SelectionActions(count: Int, modifier: Modifier = Modifier, onCancel: () -> Unit, actions: @Composable () -> Unit) {
    val g = LocalG.current
    Row(
        modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().panel(RoundedCornerShape(30.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$count selected", style = GType.navTitle, color = g.text)
            Text("Cancel", color = g.accent, fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(vertical = 2.dp))
        }
        actions()
    }
}

/** Adds [bookIds] to collections: tap a collection to add (or remove) them, or make a new one. */
@Composable
fun AddToCollectionSheet(vm: AppViewModel, bookIds: List<String>, onDismiss: () -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    var naming by remember { mutableStateOf(false) }
    GSheet(onDismiss, if (bookIds.size == 1) "Add to Collection" else "Add ${bookIds.size} Books") {
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(g.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = g.accent)
                    }
                    Text("New Collection…", fontSize = 17.sp, color = g.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
                }
            }
            items(data.collections, key = { it.id }) { c ->
                val all = bookIds.all { it in c.bookIds }
                HorizontalDivider(Modifier.padding(start = 82.dp), color = g.separator)
                Row(
                    Modifier.fillMaxWidth().clickable { if (all) vm.removeFromCollection(c.id, bookIds) else vm.addToCollection(c.id, bookIds) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val first = c.bookIds.firstNotNullOfOrNull { id -> data.books.firstOrNull { it.id == id } }
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (first != null) BookCover(vm.coverPath(first), first.title, first.author, first.coverColor, Modifier.height(44.dp), elevation = 2.dp)
                        else Icon(Icons.Rounded.CollectionsBookmark, null, tint = g.text2)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(c.name, fontSize = 17.sp, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.bookIds.size} books", fontSize = 14.sp, color = g.text2)
                    }
                    if (all) Icon(Icons.Rounded.Check, "In collection", tint = g.accent)
                }
            }
        }
    }
    if (naming) NameDialog("New Collection", "", "Create", onDismiss = { naming = false }) { name ->
        vm.createCollection(name, bookIds); naming = false
        vm.toast("Added to “${name.trim()}”"); onDismiss()
    }
}

/** Multi-select list of every book in the library; returns the chosen ids. */
@Composable
fun BookPickerSheet(vm: AppViewModel, title: String, initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    GSheet(onDismiss, title, full = true, heightFraction = 0.9f, trailing = {
        Text(
            "Done", color = g.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable { onDone(chosen.toList()) }.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(data.books.sortedBy { it.title.lowercase() }, key = { it.id }) { b ->
                val on = b.id in chosen
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) chosen.remove(b.id) else chosen.add(b.id) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(56.dp), elevation = 3.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(b.title, fontSize = 16.sp, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(b.author, fontSize = 14.sp, color = g.text2, maxLines = 1)
                    }
                    SelectMark(on)
                }
            }
        }
    }
}

/** Small dialog asking for a name. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val g = LocalG.current
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(34.dp),
        containerColor = g.sheet,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(g.fill).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (text.isEmpty()) Text("Name", color = g.text2, fontSize = 17.sp)
                BasicTextField(
                    text, { text = it.take(48) }, singleLine = true,
                    textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = AppSans), cursorBrush = SolidColor(g.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm, color = if (text.isNotBlank()) g.accent else g.text2, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = g.text) } },
    )
}
