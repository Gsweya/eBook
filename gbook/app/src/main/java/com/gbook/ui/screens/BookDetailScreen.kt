package com.gbook.ui.screens

import com.gbook.ui.components.plural
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif

/** Book page tinted by the cover's dominant colour (spec 3.4). */
@Composable
fun BookDetailScreen(vm: AppViewModel, id: String, nav: Nav) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val book = data.books.firstOrNull { it.id == id } ?: return
    val highlights = remember(data.highlights) { data.highlights.filter { it.bookId == id }.sortedBy { it.chapter * 100_000 + it.block } }
    val posts = data.posts.count { it.bookId == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if (book.format == BookFormat.PDF) "application/pdf" else "application/epub+zip")) {
        it?.let { uri -> vm.exportBook(book, uri) }
    }
    val tint = Color(book.coverColor)
    val onTint = Color.White.copy(alpha = 0.16f)

    Box(Modifier.fillMaxSize().background(tint)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.width(200.dp), elevation = 24.dp)
                Spacer(Modifier.height(24.dp))
                Text(book.title, color = Color.White, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
                Text(book.author, color = Color.White.copy(alpha = 0.8f), fontSize = 19.sp, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                // Reactions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReactionEmojis.forEach { e ->
                        val mine = book.myReaction == e
                        Text(
                            e, fontSize = 22.sp,
                            modifier = Modifier.clip(CircleShape).background(if (mine) Color.White.copy(alpha = 0.45f) else onTint)
                                .clickable { vm.reactBook(book.id, e) }.padding(8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(onTint).padding(16.dp)) {
                    val meta = buildList {
                        add(if (book.format == BookFormat.PDF) "PDF" else "Book")
                        if (book.pageCount > 0) add("${book.pageCount} pages")
                        if (book.chapterCount > 0) add("${book.chapterCount} chapters")
                        add("${(book.progress * 100).toInt()}% read")
                        if (book.readingMs > 0) add(com.gbook.ui.components.formatDuration(book.readingMs) + " reading")
                        if (book.pagesRead > 0) add("${book.pagesRead} pages read")
                    }.joinToString(" · ")
                    Text(meta, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 14.dp))
                    PrimaryButton(
                        if (book.progress > 0f) "Continue Reading" else "Read",
                        onClick = { vm.markOpened(book.id); nav.open(book) },
                        modifier = Modifier.fillMaxWidth(), container = Color.White, content = Color.Black,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Create Posts", { showGenerate = true }, Modifier.weight(1f), icon = Icons.Rounded.AutoAwesome, tint = onTint, contentColor = Color.White)
                    PillButton("Save to Drive", { save.launch(book.title + if (book.format == BookFormat.PDF) ".pdf" else ".epub") }, Modifier.weight(1f), icon = Icons.Rounded.CloudUpload, tint = onTint, contentColor = Color.White)
                }
                val plan = data.plans.firstOrNull { it.bookId == book.id }
                if (plan != null) PlanCard(vm, plan, book, onClick = { showPlan = true }, Modifier.padding(top = 12.dp))
                else PillButton("Make a reading plan", { showPlan = true }, Modifier.fillMaxWidth().padding(top = 10.dp), icon = Icons.Rounded.EventAvailable, tint = onTint, contentColor = Color.White)
                val inCollections = data.collections.filter { book.id in it.bookIds }.map { it.name }
                PillButton(
                    if (inCollections.isEmpty()) "Add to Collection" else "In " + inCollections.joinToString(", "),
                    { showCollections = true }, Modifier.fillMaxWidth().padding(top = 10.dp),
                    icon = Icons.Rounded.CollectionsBookmark, tint = onTint, contentColor = Color.White,
                )
            }
            Spacer(Modifier.height(28.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(g.bg)
                    .padding(24.dp).navigationBarsPadding(),
            ) {
                Text("Highlights", fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = g.text)
                Text("${plural(highlights.size, "highlight")} · ${plural(posts, "post")} in your Feed", fontSize = 16.sp, color = g.text2, modifier = Modifier.padding(bottom = 12.dp))
                if (highlights.isEmpty()) Text("Long-press text while reading to highlight it.", fontSize = 16.sp, color = g.text2)
                highlights.forEach { h ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(g.grouped)
                            .clickable { nav.push(Route.Reader(book.id, h.chapter, h.block)) }.padding(14.dp),
                    ) {
                        Box(Modifier.width(4.dp).height(36.dp).clip(CircleShape).background(HighlightColors[h.color % HighlightColors.size]))
                        Spacer(Modifier.width(12.dp))
                        Text(h.text, fontFamily = QuoteSerif, fontSize = 16.sp, color = g.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        h.reaction?.let { Text(it, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.clip(CircleShape).clickable { confirmDelete = true }.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.DeleteOutline, null, tint = g.destructive)
                    Text("Remove from Library", color = g.destructive, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(40.dp))
            }
        }
        IconCircle(
            Icons.Rounded.Close, "Close", nav.pop,
            Modifier.statusBarsPadding().padding(start = 16.dp, top = 6.dp), size = 44.dp,
            tint = Color.White.copy(alpha = 0.22f), iconTint = Color.White,
        )
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
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteBook(book.id); nav.pop() }) { Text("Remove", color = g.destructive) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = g.text) } },
    )
}
