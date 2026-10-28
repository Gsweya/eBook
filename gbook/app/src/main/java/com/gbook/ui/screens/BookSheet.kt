package com.gbook.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.BookFormat
import com.gbook.data.ReactionEmojis
import com.gbook.data.ReadThrough
import com.gbook.data.readThrough
import com.gbook.data.dueLabel
import com.gbook.data.planStatus
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.formatDuration
import com.gbook.ui.components.plural
import com.gbook.ui.components.pressable
import com.gbook.ui.components.rememberFileBitmap
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.launch

/**
 * A book's page, as a full-height sheet over the current screen with the cover blurred behind it: the cover,
 * rating and reactions, Read, then cards for highlights, reading plan, collections, posts, Drive and removal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSheet(vm: AppViewModel, id: String, nav: Nav, onDismiss: () -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val book = data.books.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
    val highlights = data.highlights.count { it.bookId == id }
    val plan = data.plans.firstOrNull { it.bookId == id }
    val inCollections = data.collections.filter { id in it.bookIds }.map { it.name }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (book.format == BookFormat.PDF) "application/pdf" else "application/epub+zip")) {
        it?.let { uri -> vm.exportBook(book, uri) }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    /** Slides the sheet away, then runs [then] (e.g. pushing a screen) once it's gone. */
    fun close(then: () -> Unit = {}) = scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); then() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = g.sheet,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Box(Modifier.fillMaxSize()) {
            // The cover, blurred and faded into the sheet colour, as the backdrop of the top half.
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                val bmp = rememberFileBitmap(vm.coverPath(book), 400)
                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(48.dp))
                else Box(Modifier.matchParentSize().background(Color(book.coverColor)))
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0f to g.sheet.copy(alpha = 0.45f), 0.55f to g.sheet.copy(alpha = 0.8f), 1f to g.sheet),
                    ),
                )
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.padding(top = 10.dp).width(40.dp).height(5.dp).clip(CircleShape).background(g.text.copy(alpha = 0.25f)))
                BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.padding(top = 22.dp).height(230.dp), elevation = 20.dp)
                Text(
                    book.title, color = g.text, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 22.dp),
                )
                Text(
                    book.author, color = g.text2, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val meta = buildList {
                    add(if (book.format == BookFormat.PDF) "PDF" else "Book")
                    if (book.pageCount > 0) add("${book.pageCount} pages")
                    add("${(book.progress * 100).toInt()}% read")
                    if (book.readingMs > 60_000) add(formatDuration(book.readingMs))
                }.joinToString(" · ")
                Text(meta, color = g.text2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                StarRating(book.rating, { vm.rateBook(book.id, it) }, Modifier.padding(top = 18.dp))
                Text(if (book.rating == 0) "Tap a star to rate" else "Your rating", color = g.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReactionEmojis.forEach { e ->
                        val mine = book.myReaction == e
                        Text(
                            e, fontSize = 22.sp,
                            modifier = Modifier.clip(CircleShape).background(if (mine) g.accent.copy(alpha = 0.22f) else g.fill)
                                .clickable { vm.reactBook(book.id, e) }.padding(8.dp),
                        )
                    }
                }
                PrimaryButton(
                    if (book.progress > 0f) "Continue Reading" else "Read",
                    onClick = { close { vm.markOpened(book.id); nav.open(book) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
                readThrough(book, data.bookDays)?.let { ReadThroughCard(it, Modifier.padding(top = 16.dp)) }
                val cards = listOf<@Composable (Modifier) -> Unit>(
                    { m -> OptionCard(Icons.Rounded.FormatQuote, "Highlights", if (highlights > 0) plural(highlights, "highlight") else "None yet", m) { close { nav.push(Route.Highlights(book.id)) } } },
                    { m -> OptionCard(Icons.Rounded.EventAvailable, "Reading plan", plan?.let { p -> planStatus(p, book).let { if (it.done) "Done" else it.dueLabel() } } ?: "Set a goal", m) { showPlan = true } },
                    { m -> OptionCard(Icons.Rounded.CollectionsBookmark, "Collections", if (inCollections.isEmpty()) "Add to one" else inCollections.joinToString(", "), m) { showCollections = true } },
                    { m -> OptionCard(Icons.Rounded.AutoAwesome, "Create posts", plural(data.posts.count { it.bookId == id }, "post"), m) { showGenerate = true } },
                    { m -> OptionCard(Icons.Rounded.CloudUpload, "Save to Drive", "Export the file", m) { save.launch(book.title + if (book.format == BookFormat.PDF) ".pdf" else ".epub") } },
                    { m -> OptionCard(Icons.Rounded.DeleteOutline, "Remove", "From your library", m, tint = g.destructive) { confirmDelete = true } },
                )
                Column(Modifier.padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                    }
                }
            }
        }
    }
    if (showGenerate) GeneratePostsSheet(vm, onDismiss = { showGenerate = false }, preselect = book.id)
    if (showPlan) PlanSheet(vm, book, onDismiss = { showPlan = false })
    if (showCollections) AddToCollectionSheet(vm, listOf(book.id), onDismiss = { showCollections = false })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        shape = RoundedCornerShape(34.dp),
        containerColor = g.sheet,
        title = { Text("Remove “${book.title}”?") },
        text = { Text("Its highlights and posts are removed too. Back up to Drive first if you want to keep them.") },
        confirmButton = { TextButton({ confirmDelete = false; close { vm.deleteBook(book.id) } }) { Text("Remove", color = g.destructive) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = g.text) } },
    )
}

/** Five stars; tapping one rates the book that many, tapping the current rating again clears it. */
@Composable
private fun StarRating(rating: Int, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in 1..5) {
            val on = n <= rating
            Icon(
                if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder, "$n star${if (n > 1) "s" else ""}",
                tint = if (on) Color(0xFFF5B83D) else g.text2.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp).clip(CircleShape).pressable({ onRate(n) }).padding(4.dp),
            )
        }
    }
}

/** One of the book's options, as a solid card: icon, label and its current value. */
@Composable
private fun OptionCard(icon: ImageVector, label: String, value: String, modifier: Modifier, tint: Color = LocalG.current.accent, onClick: () -> Unit) {
    val g = LocalG.current
    val shape = RoundedCornerShape(22.dp)
    // In dark mode the sheet is already the surface colour, so cards step up to the lighter fill.
    Column(modifier.pressable(onClick).height(118.dp).clip(shape).background(if (g.dark) g.fill else g.surface).border(0.7.dp, g.separator, shape).padding(16.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(label, color = if (tint == g.destructive) tint else g.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = g.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How long the book took (or has taken so far): days from start to finish, the dates, and the time spent reading it. */
@Composable
private fun ReadThroughCard(r: ReadThrough, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(if (g.dark) g.fill else g.surface).border(0.7.dp, g.separator, shape).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(g.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(if (r.finished) Icons.Rounded.Flag else Icons.Rounded.Schedule, null, tint = g.accent, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(r.headline(), color = g.text, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(r.dateRange(), color = g.text2, fontSize = 14.sp)
            }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth()) {
            ReadStat(formatDuration(r.readingMs), "reading time", Modifier.weight(1f))
            ReadStat("${r.readingDays}", if (r.readingDays == 1) "day read" else "days read", Modifier.weight(1f))
            ReadStat(if (r.readingDays > 0) formatDuration(r.readingMs / r.readingDays) else "–", "a reading day", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReadStat(value: String, label: String, modifier: Modifier) {
    val g = LocalG.current
    Column(modifier) {
        Text(value, color = g.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = g.text2, fontSize = 13.sp, maxLines = 1)
    }
}
