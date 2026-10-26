package com.gbook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.gbook.AppViewModel
import com.gbook.Nav
import com.gbook.Route
import com.gbook.data.Book
import com.gbook.data.Post
import com.gbook.data.PostKind
import com.gbook.data.ReactionEmojis
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.GSwitch
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.panel
import com.gbook.ui.components.shareImage
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FeedFilter(val label: String) { All("All"), Quotes("My Highlights"), Ideas("Key Ideas"), Recaps("Recaps"), Saved("Saved") }

@Composable
fun FeedTab(vm: AppViewModel, nav: Nav, onGenerate: () -> Unit, onScrollDir: (Boolean) -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    var filter by rememberSaveable { mutableStateOf(FeedFilter.All) }
    val posts = remember(data.posts, filter) {
        data.posts.filter {
            when (filter) {
                FeedFilter.All -> true
                FeedFilter.Quotes -> it.kind == PostKind.QUOTE
                FeedFilter.Ideas -> it.kind == PostKind.IDEA
                FeedFilter.Recaps -> it.kind == PostKind.RECAP
                FeedFilter.Saved -> it.saved
            }
        }
    }
    val books = remember(data.books) { data.books.associateBy { it.id } }
    val list = rememberLazyListState()
    var last by remember { mutableStateOf(0) }
    LaunchedEffect(list.firstVisibleItemIndex) {
        onScrollDir(list.firstVisibleItemIndex > last); last = list.firstVisibleItemIndex
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 48.dp, end = 20.dp)) {
                Text("Feed", style = GType.largeTitle, color = g.text)
                Text("Bite-sized ideas from your books", style = GType.secondary, color = g.text2)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FeedFilter.entries) { f ->
                    val on = f == filter
                    Text(
                        f.label, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = if (on) g.onPrimary else g.text,
                        modifier = Modifier.clip(CircleShape).background(if (on) g.primary else g.fill)
                            .clickable { filter = f }.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
        if (posts.isEmpty()) item {
            EmptyState(
                if (filter == FeedFilter.All) "Your feed is empty" else "Nothing here yet",
                "Turn chapters and highlights into posts you can scroll, react to and share.",
                "Create Posts", onGenerate,
            )
        }
        items(posts, key = { it.id }) { p ->
            val b = books[p.bookId] ?: return@items
            PostCard(vm, p, b, onOpen = { vm.markOpened(b.id); nav.push(Route.Reader(b.id, p.chapter)) })
        }
    }
}

/** Instagram-style post: header, 4:5 slide carousel, action row, reactions. */
@Composable
fun PostCard(vm: AppViewModel, post: Post, book: Book, onOpen: () -> Unit) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { post.slides.size }
    val layer = rememberGraphicsLayer()
    var burst by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(burst) { if (burst) { delay(700); burst = false } }

    Column(Modifier.padding(bottom = 22.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(40.dp), elevation = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(post.chapterTitle.ifBlank { book.author }, fontSize = 13.sp, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                when (post.kind) { PostKind.QUOTE -> "Highlight"; PostKind.IDEA -> "Key idea"; PostKind.RECAP -> "Recap" },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = g.text2,
                modifier = Modifier.background(g.fill, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Box(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(22.dp))
                .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                .pointerInput(post.id) { detectTapGestures(onDoubleTap = { vm.likePost(post.id); burst = true }) },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page -> PostSlide(vm, post, book, page) }
            androidx.compose.animation.AnimatedVisibility(burst, Modifier.align(Alignment.Center), enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)), exit = fadeOut()) {
                Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(110.dp))
            }
        }
        if (post.slides.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
                repeat(post.slides.size) { i ->
                    Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 7.dp else 6.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) g.accent else g.text2.copy(alpha = 0.4f)))
                }
            }
        }
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val heart by animateFloatAsState(if (post.liked) 1.15f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "heart")
            ActionIcon(if (post.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", if (post.liked) Color(0xFFFF3B30) else g.text, Modifier.scale(heart)) { vm.togglePostLike(post.id) }
            Box {
                if (post.myReaction != null) {
                    Text(post.myReaction, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { picker = true }.padding(10.dp))
                } else ActionIcon(Icons.Rounded.AddReaction, "React", g.text) { picker = true }
                if (picker) ReactionPicker(onPick = { vm.reactPost(post.id, it); picker = false }, onDismiss = { picker = false })
            }
            ActionIcon(Icons.Rounded.IosShare, "Share", g.text) {
                scope.launch {
                    val bmp = layer.toImageBitmap().asAndroidBitmap()
                    shareImage(ctx, bmp, "“${post.slides.getOrElse(pager.currentPage) { post.slides.first() }}” — ${book.title}, ${book.author} · via GBook")
                }
            }
            Spacer(Modifier.weight(1f))
            ActionIcon(Icons.Rounded.DeleteOutline, "Remove post", g.text2) { vm.deletePost(post.id) }
            ActionIcon(if (post.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Save", g.text) { vm.togglePostSave(post.id) }
        }
        val summary = buildList {
            if (post.liked) add("Liked by you")
            if (post.reactions.isNotEmpty()) add(post.reactions.entries.joinToString(" ") { "${it.key} ${it.value}" })
        }.joinToString("  ·  ")
        if (summary.isNotEmpty()) Text(summary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.text, modifier = Modifier.padding(horizontal = 18.dp))
        Text(
            "${book.title} · tap the header to read this chapter",
            fontSize = 13.sp, color = g.text2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Icon(icon, desc, tint = tint, modifier = modifier.clip(CircleShape).clickable(onClick = onClick).padding(10.dp).size(26.dp))
}

@Composable
fun ReactionPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Popup(alignment = Alignment.BottomStart, offset = androidx.compose.ui.unit.IntOffset(0, -140), onDismissRequest = onDismiss) {
        Row(Modifier.panel(CircleShape).padding(horizontal = 8.dp, vertical = 4.dp)) {
            ReactionEmojis.forEach { e ->
                Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { onPick(e) }.padding(8.dp))
            }
        }
    }
}

private class SlideStyle(val bg: Brush, val text: Color, val accent: Color)

private fun slideStyle(i: Int, cover: Color): SlideStyle = when (i % 6) {
    0 -> SlideStyle(Brush.verticalGradient(listOf(Color(0xFFF6EBD4), Color(0xFFF0E1C2))), Color(0xFF3B2F1E), Color(0xFFB0662B))
    1 -> SlideStyle(Brush.verticalGradient(listOf(Color(0xFF242426), Color(0xFF111112))), Color.White, Color(0xFFF28C28))
    2 -> SlideStyle(Brush.linearGradient(listOf(cover.copy(alpha = 1f), Color(0xFF111111))), Color.White, Color.White.copy(alpha = 0.7f))
    3 -> SlideStyle(Brush.verticalGradient(listOf(Color.White, Color(0xFFF3F3F6))), Color.Black, Color(0xFF8A8A8E))
    4 -> SlideStyle(Brush.linearGradient(listOf(Color(0xFFF28C28), Color(0xFFE8693A))), Color.White, Color.White.copy(alpha = 0.75f))
    else -> SlideStyle(Brush.verticalGradient(listOf(Color(0xFF5A5A5C), Color(0xFF3F3F41))), Color(0xFFE5E5E7), Color(0xFFB8B8BA))
}

@Composable
private fun PostSlide(vm: AppViewModel, post: Post, book: Book, page: Int) {
    val s = slideStyle(post.style, Color(book.coverColor))
    val text = post.slides[page]
    val isRecapTitle = post.kind == PostKind.RECAP && page == 0
    val eyebrow = when {
        post.kind == PostKind.QUOTE && page == 1 -> "MY NOTE"
        post.kind == PostKind.QUOTE -> "HIGHLIGHT"
        post.kind == PostKind.IDEA -> "KEY IDEA"
        isRecapTitle -> "CHAPTER RECAP"
        else -> "KEY POINT $page OF ${post.slides.size - 1}"
    }
    Column(Modifier.fillMaxSize().background(s.bg).padding(28.dp)) {
        Text(eyebrow, color = s.accent, style = GType.eyebrow)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (isRecapTitle) {
                Column {
                    Text(text, color = s.text, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp)
                    Text("Swipe for the key points  →", color = s.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
                }
            } else {
                Column {
                    if (post.kind != PostKind.RECAP) Text("“", color = s.accent, fontFamily = DisplaySerif, fontSize = 72.sp, lineHeight = 60.sp)
                    val size = when {
                        text.length < 90 -> 28.sp; text.length < 160 -> 24.sp; text.length < 260 -> 21.sp; text.length < 420 -> 18.sp; else -> 16.sp
                    }
                    Text(
                        text, color = s.text, fontFamily = QuoteSerif, fontSize = size, lineHeight = size * 1.3f,
                        textAlign = TextAlign.Start, overflow = TextOverflow.Ellipsis, maxLines = 16,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(38.dp), elevation = 2.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, color = s.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book.author, color = s.text.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
            Text("GBook", color = s.accent, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun GeneratePostsSheet(vm: AppViewModel, onDismiss: () -> Unit, preselect: String? = null) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val pdfProgress by vm.importer.pdfProgress.collectAsState()
    var selected by remember { mutableStateOf(setOfNotNull(preselect).ifEmpty { data.books.map { it.id }.toSet() }) }
    var fromHighlights by remember { mutableStateOf(true) }
    var ideas by remember { mutableStateOf(true) }
    var recaps by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    GSheet(onDismiss, "Create Posts", full = true) {
        Text(
            "GBook picks the most important sentences in each chapter and your highlights, then turns them into posts you can scroll through to remember the ideas.",
            style = GType.secondary, color = g.text2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column(Modifier.padding(16.dp).clip(RoundedCornerShape(22.dp)).background(g.surface)) {
            OptionRow("From my highlights", "${data.highlights.count { it.bookId in selected }} highlights", fromHighlights) { fromHighlights = it }
            HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
            OptionRow("Key ideas", "The strongest sentence of each chapter", ideas) { ideas = it }
            HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
            OptionRow("Chapter recaps", "Swipeable carousel per chapter", recaps) { recaps = it }
        }
        Text("BOOKS", style = GType.caption, color = g.text2, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
        LazyColumn(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).weight(1f, fill = false).clip(RoundedCornerShape(22.dp)).background(g.surface)) {
            items(data.books, key = { it.id }) { b ->
                val preparing = pdfProgress[b.id]
                val on = b.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = b.textReady) { selected = if (on) selected - b.id else selected + b.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(vm.coverPath(b), b.title, b.author, b.coverColor, Modifier.height(48.dp), elevation = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (!b.textReady) "Preparing text… ${((preparing ?: 0f) * 100).toInt()}%" else b.author,
                            fontSize = 13.sp, color = g.text2, maxLines = 1,
                        )
                    }
                    Icon(
                        if (on && b.textReady) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (on && b.textReady) g.accent else g.text2.copy(alpha = 0.5f),
                    )
                }
            }
        }
        PrimaryButton(
            if (working) "Creating…" else "Create Posts",
            onClick = {
                if (working) return@PrimaryButton
                working = true
                scope.launch {
                    val ids = selected.filter { id -> data.books.firstOrNull { it.id == id }?.textReady == true }
                    val n = vm.generatePosts(ids, fromHighlights, ideas, recaps)
                    vm.toast(if (n == 0) "No new posts — try other books or options" else "Added $n posts to your Feed")
                    onDismiss()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun OptionRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = g.text)
            if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = g.text2)
        }
        GSwitch(checked, onChange)
    }
}
