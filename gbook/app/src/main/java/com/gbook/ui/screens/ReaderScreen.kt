package com.gbook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.gbook.data.ReactionEmojis
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.ui.graphics.drawscope.DrawScope
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import com.gbook.ui.components.rememberHaptics
import com.gbook.ui.components.TrackReadingTime
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlin.math.roundToInt
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.gbook.ui.theme.ReadingFonts
import com.gbook.ui.theme.readingFont
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.FormatLineSpacing
import androidx.compose.material.icons.rounded.FormatIndentIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.data.Book
import com.gbook.data.BookContent
import com.gbook.data.Chapter
import com.gbook.data.Highlight
import com.gbook.data.ReaderSettings
import com.gbook.data.ReadingPosition
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.CapsuleDivider
import com.gbook.ui.components.CapsuleSegment
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.GSlider
import com.gbook.ui.components.GSwitch
import com.gbook.ui.components.SegmentCapsule
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.panel
import com.gbook.ui.components.rememberFileBitmap
import com.gbook.ui.components.shareText
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import com.gbook.ui.theme.QuoteSerif
import com.gbook.ui.theme.ReaderThemes
import com.gbook.ui.theme.SelectionColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A caret position: character offset [off] inside paragraph [block] of the current chapter. */
private data class TextPos(val block: Int, val off: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos) = compareValuesBy(this, other, { it.block }, { it.off })
}

/** Active selection from [start] to [end] (exclusive); may span paragraphs. */
private data class Sel(val start: TextPos, val end: TextPos) {
    fun rangeIn(b: Int, len: Int): IntRange? {
        if (b < start.block || b > end.block) return null
        val s = if (b == start.block) start.off.coerceIn(0, len) else 0
        val e = if (b == end.block) end.off.coerceIn(0, len) else len
        return if (e > s) s until e else null
    }
}

/** Live layout of an on-screen paragraph, used to hit-test selection drags across paragraphs. */
private class ParaRef {
    var layout: TextLayoutResult? = null
    var coords: LayoutCoordinates? = null
}

private val HandleBlue = Color(0xFF3A7BF2)

const val MIN_FONT = 12f
const val MAX_FONT = 40f

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(
    vm: AppViewModel,
    bookId: String,
    startChapter: Int?,
    startBlock: Int?,
    onBack: () -> Unit,
    modeToggle: (@Composable () -> Unit)? = null,
) {
    val data by vm.data.collectAsState()
    val book = data.books.firstOrNull { it.id == bookId } ?: return
    val content by produceState<BookContent?>(null, bookId, book.textReady) { value = vm.store.content(bookId) }
    val c = content
    val rt = ReaderThemes[data.settings.theme.coerceIn(0, ReaderThemes.lastIndex)]
    val dark = LocalG.current.dark
    val page = if (dark) rt.pageDark else rt.page
    val ink = if (dark) rt.textDark else rt.text
    if (c == null || c.chapters.isEmpty()) {
        val progress by vm.importer.pdfProgress.collectAsState()
        Box(Modifier.fillMaxSize().background(page), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = ink)
                val p = progress[bookId]
                Text(
                    if (p != null) "Preparing text for reading… ${(p * 100).toInt()}%" else if (c != null) "This book has no readable text" else "Opening…",
                    color = ink.copy(alpha = 0.6f), modifier = Modifier.padding(top = 16.dp),
                )
                if (modeToggle != null) Box(Modifier.padding(top = 24.dp)) { modeToggle() }
            }
            IconCircle(Icons.Rounded.ArrowBackIosNew, "Back", onBack, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), size = 44.dp)
        }
        return
    }
    ReaderContent(vm, book, c.chapters, data.settings, data.highlights, startChapter, startBlock, onBack, modeToggle, page, ink, dark)
}

@OptIn(FlowPreview::class)
@Composable
private fun ReaderContent(
    vm: AppViewModel,
    book: Book,
    chapters: List<Chapter>,
    s: ReaderSettings,
    allHighlights: List<Highlight>,
    startChapter: Int?,
    startBlock: Int?,
    onBack: () -> Unit,
    modeToggle: (@Composable () -> Unit)?,
    page: Color,
    ink: Color,
    dark: Boolean,
) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rt = ReaderThemes[s.theme.coerceIn(0, ReaderThemes.lastIndex)]

    var chapter by rememberSaveable { mutableIntStateOf((startChapter ?: book.position.chapter).coerceIn(0, chapters.lastIndex)) }
    val initialConsumed = remember { BooleanArray(1) }
    val listState = remember(chapter) {
        if (!initialConsumed[0]) {
            initialConsumed[0] = true
            if (startChapter != null) LazyListState(startBlock ?: 0, 0) else LazyListState(book.position.item, book.position.offset)
        } else LazyListState()
    }
    var pendingJump by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(listState, pendingJump) {
        pendingJump?.let { listState.scrollToItem(it); pendingJump = null }
    }
    val ch = chapters[chapter]
    fun goTo(chapterIdx: Int, block: Int = 0) {
        val target = chapterIdx.coerceIn(0, chapters.lastIndex)
        if (target == chapter) scope.launch { listState.scrollToItem(block) } else { chapter = target; pendingJump = block }
    }

    var chrome by rememberSaveable { mutableStateOf(false) }
    var sel by remember(chapter) { mutableStateOf<Sel?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var activeHl by remember { mutableStateOf<Highlight?>(null) }
    var noteFor by remember { mutableStateOf<Highlight?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var contentsTab by remember { mutableStateOf<Int?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf<String?>(null) }
    var sizeHud by remember { mutableStateOf(0) }
    // Selection loupe source point in reader coordinates; Unspecified hides it.
    var loupe by remember { mutableStateOf(Offset.Unspecified) }
    val rootCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val pageLayer = rememberGraphicsLayer()
    val paras = remember(chapter) { HashMap<Int, ParaRef>() }
    // Word range under the initial long-press; dragging extends the selection outward from it.
    var anchor by remember { mutableStateOf<Sel?>(null) }
    // Point being dragged (reader coordinates) and how to re-apply it, so edge auto-scroll can keep extending.
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    val dragMove = remember { arrayOfNulls<(Offset) -> Unit>(1) }

    val chHighlights = remember(allHighlights, book.id, chapter) {
        val m = HashMap<Int, MutableList<Highlight>>()
        allHighlights.filter { it.bookId == book.id && it.chapter == chapter }
            .forEach { h -> for (b in h.block..h.lastBlock) m.getOrPut(b) { mutableListOf() } += h }
        m
    }
    val bookHighlights = remember(allHighlights, book.id) { allHighlights.filter { it.bookId == book.id } }

    // Reading-time estimate, like Apple's "N pages left in chapter".
    val wordsAfter = remember(ch) {
        val w = ch.blocks.map { b -> if (b.type == 0) b.text.count { it == ' ' } + 1 else 0 }
        IntArray(w.size + 1).also { arr -> for (i in w.indices.reversed()) arr[i] = arr[i + 1] + w[i] }
    }
    val minutesLeft by remember(listState, wordsAfter) {
        derivedStateOf { (wordsAfter[listState.firstVisibleItemIndex.coerceIn(0, wordsAfter.lastIndex)] / 230f).let { kotlin.math.ceil(it).toInt() } }
    }
    val bookProgress by remember(listState, chapter, ch) {
        derivedStateOf {
            val frac = (listState.firstVisibleItemIndex.toFloat() / ch.blocks.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            ((chapter + frac) / chapters.size).coerceIn(0f, 1f)
        }
    }

    val scrollActivity by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex * 10 + listState.firstVisibleItemScrollOffset / 300 } }
    TrackReadingTime(vm, book.id, Triple(chapter, scrollActivity, chrome))

    LaunchedEffect(listState, chapter) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(400)
            .collect { (i, o) ->
                val pos = (vm.store.book(book.id)?.position ?: ReadingPosition()).copy(chapter = chapter, item = i, offset = o)
                vm.savePosition(book.id, pos, bookProgress)
            }
    }

    fun changeFont(delta: Float) {
        val n = (s.fontSize + delta).coerceIn(MIN_FONT, MAX_FONT)
        if (n != s.fontSize) {
            haptics.tap()
            vm.updateSettings { it.copy(fontSize = n) }
            sizeHud++
        }
    }

    val family = readingFont(s.font).family
    val weight = if (s.bold || rt.bold) FontWeight.SemiBold else FontWeight.Normal
    val body = TextStyle(
        color = ink, fontSize = s.fontSize.sp, lineHeight = (s.fontSize * s.lineHeight).sp, fontFamily = family,
        fontWeight = weight, textAlign = if (s.justify) TextAlign.Justify else TextAlign.Start,
        hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph, textIndent = TextIndent(firstLine = (s.fontSize * 1.4f).sp),
    )
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun root() = rootCoords[0]?.takeIf { it.isAttached }

    /** Nearest paragraph caret to [p] (reader coordinates) plus the loupe point: centre of that line. */
    fun probe(p: Offset): Pair<TextPos, Offset>? {
        val r = root() ?: return null
        var best: Pair<TextPos, Offset>? = null
        var bestDist = Float.MAX_VALUE
        for ((i, ref) in paras) {
            val l = ref.layout ?: continue
            val c = ref.coords?.takeIf { it.isAttached } ?: continue
            val local = c.localPositionOf(r, p)
            val h = c.size.height.toFloat()
            val d = if (local.y < 0) -local.y else if (local.y > h) local.y - h else 0f
            if (d >= bestDist) continue
            bestDist = d
            val y = local.y.coerceIn(0f, h - 1f)
            val line = l.getLineForVerticalPosition(y)
            val x = local.x.coerceIn(l.getLineLeft(line), l.getLineRight(line))
            best = TextPos(i, l.getOffsetForPosition(Offset(x, y))) to
                r.localPositionOf(c, Offset(x, (l.getLineTop(line) + l.getLineBottom(line)) / 2))
        }
        return best
    }

    /** Caret line for a selection end in reader coordinates: x at the caret, spanning the line. */
    fun caretRect(pos: TextPos, isStart: Boolean): Rect? {
        val ref = paras[pos.block] ?: return null
        val l = ref.layout ?: return null
        val c = ref.coords?.takeIf { it.isAttached } ?: return null
        val r = root() ?: return null
        val len = l.layoutInput.text.length
        if (len == 0) return null
        val ci = (if (isStart) pos.off else pos.off - 1).coerceIn(0, len - 1)
        val box = l.getBoundingBox(ci)
        val line = l.getLineForOffset(ci)
        val tl = r.localPositionOf(c, Offset(if (isStart) box.left else box.right, l.getLineTop(line)))
        return Rect(tl.x, tl.y, tl.x, tl.y + l.getLineBottom(line) - l.getLineTop(line))
    }

    fun textOf(b: Int) = ch.blocks[b].text

    fun extendFromAnchor(p: Offset) {
        val (pos, lp) = probe(p) ?: return
        loupe = lp
        val a = anchor ?: return
        val w = wordAt(textOf(pos.block), pos.off)
        val next = Sel(minOf(a.start, TextPos(pos.block, w.first)), maxOf(a.end, TextPos(pos.block, w.last)))
        if (next != sel) { haptics.tick(); sel = next }
    }

    fun moveHandle(isStart: Boolean, p: Offset) {
        val (pos, lp) = probe(p) ?: return
        loupe = lp
        val cur = sel ?: return
        val w = wordAt(textOf(pos.block), pos.off)
        // Handles snap to whole words and never cross each other.
        val next = if (isStart) TextPos(pos.block, w.first).takeIf { it < cur.end }?.let { cur.copy(start = it) }
        else TextPos(pos.block, w.last).takeIf { it > cur.start }?.let { cur.copy(end = it) }
        if (next != null && next != cur) { haptics.tick(); sel = next }
    }

    fun endDrag() {
        selecting = false; loupe = Offset.Unspecified; dragPoint = Offset.Unspecified; dragMove[0] = null
    }

    // Auto-scroll while a selection drag sits near the top or bottom edge, extending as text moves under it.
    LaunchedEffect(selecting) {
        if (!selecting) return@LaunchedEffect
        val edge = with(density) { 96.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        val topEdge = with(density) { (top + 56.dp).toPx() } + edge
        while (true) {
            withFrameNanos { }
            val p = dragPoint
            val h = root()?.size?.height ?: continue
            if (!p.isSpecified) continue
            val bottomEdge = h - with(density) { (bottom + 96.dp).toPx() } - edge
            val step = when {
                p.y > bottomEdge -> ((p.y - bottomEdge) / edge).coerceAtMost(1f) * maxStep
                p.y < topEdge -> -((topEdge - p.y) / edge).coerceAtMost(1f) * maxStep
                else -> 0f
            }
            if (step != 0f && listState.scrollBy(step) != 0f) dragMove[0]?.invoke(p)
        }
    }

    Box(
        Modifier.fillMaxSize().background(page)
            .onGloballyPositioned { rootCoords[0] = it }
    ) {
        Box(
            Modifier.fillMaxSize()
                // Keep a display list of the page so the loupe can redraw it magnified.
                .drawWithContent { pageLayer.record { this@drawWithContent.drawContent() }; drawLayer(pageLayer) }
                .pointerInput(Unit) { pinchToResize { changeFont(it) } }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { if (sel != null || activeHl != null) { sel = null; activeHl = null } else chrome = !chrome })
                },
                contentPadding = PaddingValues(start = s.margin.dp, end = s.margin.dp, top = top + 56.dp, bottom = bottom + 96.dp),
            ) {
                itemsIndexed(ch.blocks, key = { i, _ -> "$chapter-$i" }) { i, b ->
                    when (b.type) {
                        1 -> Text(
                            b.text,
                            style = TextStyle(
                                color = ink, fontFamily = family, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                fontSize = (s.fontSize * if (b.level <= 2) 1.5f else 1.2f).sp,
                                lineHeight = (s.fontSize * if (b.level <= 2) 1.9f else 1.55f).sp,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = if (i == 0) 8.dp else 28.dp, bottom = 16.dp),
                        )
                        2 -> {
                            val bmp = rememberFileBitmap(vm.imagePath(book.id, b.src.orEmpty()), 1200)
                            // heightIn before aspectRatio lets tall images shrink in width instead of overflowing.
                            if (bmp != null) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Image(
                                    bmp, null, contentScale = ContentScale.Fit,
                                    modifier = Modifier.heightIn(max = 520.dp).aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1)),
                                )
                            }
                        }
                        else -> {
                            val ref = remember { ParaRef() }
                            DisposableEffect(ref, i) {
                                paras[i] = ref
                                onDispose { if (paras[i] === ref) paras.remove(i) }
                            }
                            val hls = chHighlights[i].orEmpty()
                            Paragraph(
                                text = b.text, style = body, ref = ref,
                                highlights = hls.map { Triple(it.rangeIn(i, b.text.length), it.color, it.style) },
                                reactions = hls.mapNotNull { h -> h.reaction?.takeIf { h.lastBlock == i }?.let { h.rangeIn(i, b.text.length).last to it } },
                                selection = sel?.rangeIn(i, b.text.length),
                                searchTerm = searchTerm,
                                onTap = { off ->
                                    val hit = hls.firstOrNull { off in it.rangeIn(i, b.text.length) }
                                    when {
                                        hit != null -> { activeHl = hit; sel = null }
                                        sel != null || activeHl != null -> { sel = null; activeHl = null }
                                        else -> chrome = !chrome
                                    }
                                },
                                onLong = { w ->
                                    val p = root()?.windowToLocal(w)
                                    val hit = p?.let { probe(it) }
                                    if (p != null && hit != null) {
                                        val word = wordAt(textOf(hit.first.block), hit.first.off)
                                        haptics.select()
                                        anchor = Sel(TextPos(hit.first.block, word.first), TextPos(hit.first.block, word.last))
                                        sel = anchor; activeHl = null; selecting = true; chrome = false
                                        loupe = hit.second; dragPoint = p; dragMove[0] = { extendFromAnchor(it) }
                                    }
                                },
                                onDrag = { w -> root()?.windowToLocal(w)?.let { p -> dragPoint = p; extendFromAnchor(p) } },
                                onEnd = { endDrag() },
                                modifier = Modifier.fillMaxWidth().padding(bottom = (s.fontSize * 0.45f).dp),
                            )
                        }
                    }
                }
                item(key = "end-$chapter") {
                    ChapterEnd(
                        next = chapters.getOrNull(chapter + 1)?.title, ink = ink,
                        onNext = { goTo(chapter + 1) },
                        onPosts = { showGenerate = true },
                    )
                }
            }
        }

        // Quiet chrome: running header and reading time, like Apple Books with controls hidden.
        // Fade the page under the status bar and the running header/footer so text never collides with them.
        Box(Modifier.fillMaxWidth().height(top + 64.dp).background(Brush.verticalGradient(0f to page, 0.72f to page, 1f to page.copy(alpha = 0f))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom + 76.dp).background(Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.45f to page, 1f to page)))
        if (sel != null) for (isStart in listOf(true, false)) {
            SelectionHandle(
                isStart = isStart,
                caret = {
                    // Reading the scroll position re-places the handle on every scroll frame.
                    listState.firstVisibleItemIndex; listState.firstVisibleItemScrollOffset
                    sel?.let { caretRect(if (isStart) it.start else it.end, isStart) }
                },
                rootCoords = { root() },
                onStart = { selecting = true; dragMove[0] = { moveHandle(isStart, it) } },
                onMove = { p -> dragPoint = p; moveHandle(isStart, p) },
                onEnd = { endDrag() },
            )
        }
        AnimatedVisibility(!chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    ch.title, color = ink.copy(alpha = 0.45f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp, start = 64.dp, end = 64.dp),
                )
                Text(
                    if (minutesLeft <= 1) "${(bookProgress * 100).toInt()}%" else "$minutesLeft min left in chapter · ${(bookProgress * 100).toInt()}%",
                    color = ink.copy(alpha = 0.45f), fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                )
            }
        }
        AnimatedVisibility(chrome && sel == null && activeHl == null, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Icons.Rounded.ArrowBackIosNew, "Back", onBack, size = 44.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PillButton(ch.title, { contentsTab = 0 }, Modifier.widthIn(max = 240.dp), height = 44.dp)
                }
                Spacer(Modifier.width(8.dp))
                modeToggle?.invoke()
                if (modeToggle != null) Spacer(Modifier.width(8.dp))
                IconCircle(Icons.Rounded.TextFields, "Themes & Settings", { showSettings = true }, size = 44.dp)
            }
        }
        AnimatedVisibility(
            chrome && sel == null && activeHl == null, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Chapter ${chapter + 1} of ${chapters.size} · ${(bookProgress * 100).toInt()}%",
                    color = g.text, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp).panel(CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Icons.Rounded.ChevronLeft, "Previous chapter", { goTo(chapter - 1) }, size = 52.dp, enabled = chapter > 0)
                    Spacer(Modifier.weight(1f))
                    SegmentCapsule(height = 52.dp) {
                        CapsuleSegment({ changeFont(-1f) }, enabled = s.fontSize > MIN_FONT) { Text("A", fontSize = 16.sp, color = g.text, fontWeight = FontWeight.Medium) }
                        CapsuleDivider()
                        Text("${s.fontSize.toInt()}", fontSize = 15.sp, color = g.text2, modifier = Modifier.padding(horizontal = 12.dp))
                        CapsuleDivider()
                        CapsuleSegment({ changeFont(1f) }, enabled = s.fontSize < MAX_FONT) { Text("A", fontSize = 26.sp, color = g.text, fontWeight = FontWeight.Medium) }
                    }
                    Spacer(Modifier.width(10.dp))
                    IconCircle(Icons.Rounded.FormatListBulleted, "Menu", { showMenu = true }, size = 52.dp)
                    Spacer(Modifier.weight(1f))
                    IconCircle(Icons.Rounded.ChevronRight, "Next chapter", { goTo(chapter + 1) }, size = 52.dp, enabled = chapter < chapters.lastIndex)
                }
            }
        }

        // Selection / highlight actions
        val selText = sel?.let { ss ->
            (ss.start.block..ss.end.block)
                .mapNotNull { b -> ch.blocks[b].takeIf { it.type == 0 }?.let { blk -> ss.rangeIn(b, blk.text.length)?.let { blk.text.substring(it) } } }
                .joinToString("\n\n")
        }
        AnimatedVisibility(
            (sel != null && !selecting) || activeHl != null, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
        ) {
            val existing = activeHl
            SelectionBar(
                activeColor = existing?.color,
                isExisting = existing != null,
                style = existing?.style ?: s.highlightStyle,
                onStyle = { st ->
                    if (existing != null) { vm.updateHighlight(existing.id) { it.copy(style = st) }; activeHl = existing.copy(style = st) }
                    else vm.updateSettings { it.copy(highlightStyle = st) }
                },
                // The bar stays open on the new highlight so a reaction or note can follow.
                onColor = { color ->
                    if (existing != null) { vm.updateHighlight(existing.id) { it.copy(color = color) }; activeHl = existing.copy(color = color) }
                    else sel?.let { ss -> activeHl = vm.addHighlight(book.id, chapter, ss.start.block, ss.start.off, ss.end.block, ss.end.off, selText!!, color, ch.title, style = s.highlightStyle); sel = null }
                },
                reaction = existing?.reaction,
                onReact = { emoji ->
                    existing?.let { h ->
                        haptics.tap()
                        vm.reactHighlight(h.id, emoji)
                        activeHl = h.copy(reaction = if (h.reaction == emoji) null else emoji)
                    }
                },
                onNote = {
                    noteFor = existing ?: sel?.let { ss -> vm.addHighlight(book.id, chapter, ss.start.block, ss.start.off, ss.end.block, ss.end.off, selText!!, 0, ch.title, style = s.highlightStyle) }
                    sel = null; activeHl = null
                },
                onPost = {
                    if (existing != null) vm.postFromHighlight(existing, ch.title)
                    else sel?.let { ss -> vm.addHighlight(book.id, chapter, ss.start.block, ss.start.off, ss.end.block, ss.end.off, selText!!, 0, ch.title, style = s.highlightStyle, makePost = true); vm.toast("Highlighted and posted to your Feed") }
                    sel = null; activeHl = null
                },
                onCopy = {
                    clipboard.setText(AnnotatedString(existing?.text ?: selText.orEmpty()))
                    vm.toast("Copied"); sel = null; activeHl = null
                },
                onShare = {
                    shareText(ctx, "“${existing?.text ?: selText}”\n— ${book.title}, ${book.author}")
                    sel = null; activeHl = null
                },
                onDelete = { existing?.let { vm.deleteHighlight(it.id) }; activeHl = null },
            )
        }
        if (loupe.isSpecified) Loupe(pageLayer, loupe, page)
        if (sizeHud > 0) {
            LaunchedEffect(sizeHud) { delay(900); sizeHud = 0 }
            Text(
                "${s.fontSize.toInt()} pt", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = g.text,
                modifier = Modifier.align(Alignment.Center).panel(RoundedCornerShape(24.dp)).padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        if (showMenu) ReaderMenu(
            progress = bookProgress, highlightCount = bookHighlights.size,
            onDismiss = { showMenu = false },
            onContents = { showMenu = false; contentsTab = 0 },
            onHighlights = { showMenu = false; contentsTab = 1 },
            onSearch = { showMenu = false; showSearch = true },
            onSettings = { showMenu = false; showSettings = true },
            onShare = { showMenu = false; shareText(ctx, "I'm reading “${book.title}” by ${book.author} on GBook") },
            onPosts = { showMenu = false; showGenerate = true },
        )
    }

    if (showSettings) ReaderSettingsSheet(vm, s, dark, onDismiss = { showSettings = false })
    contentsTab?.let { tab ->
        ContentsSheet(
            vm, book, chapters, chapter, bookHighlights, tab,
            subtitle = "Chapter ${chapter + 1} of ${chapters.size}",
            onChapter = { goTo(it); contentsTab = null; chrome = false },
            onHighlight = { goTo(it.chapter, it.block); contentsTab = null; chrome = false },
            onDismiss = { contentsTab = null },
        )
    }
    if (showSearch) SearchSheet(chapters, onDismiss = { showSearch = false }) { chIdx, block, term ->
        searchTerm = term; showSearch = false; chrome = false; goTo(chIdx, block)
    }
    if (showGenerate) GeneratePostsSheet(vm, onDismiss = { showGenerate = false }, preselect = book.id)
    noteFor?.let { h ->
        NoteDialog(initial = h.note.orEmpty(), quote = h.text, onDismiss = { noteFor = null }) { note ->
            vm.updateHighlight(h.id) { it.copy(note = note) }; noteFor = null
        }
    }
}

/** Barrel-distorts the loupe like a convex lens: strongest magnification in the middle, a slight chromatic
 *  fringe and darkening towards the rim, and a soft specular highlight near the top. */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float bulge;
half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = (coord - c) / c;
    // Superellipse norm so the lens fills the oval capsule, not just its inscribed ellipse.
    float r = min(pow(pow(abs(p.x), 3.0) + pow(abs(p.y), 3.0), 1.0 / 3.0), 1.0);
    float k = 1.0 - bulge * (1.0 - r * r);
    float2 q = c + p * k * c;
    float2 fringe = p * c * 0.006 * r * r * r;
    half4 col = content.eval(q);
    col.r = content.eval(q + fringe).r;
    col.b = content.eval(q - fringe).b;
    col.rgb *= 1.0 - 0.20 * smoothstep(0.6, 1.0, r);
    float spec = 1.0 - smoothstep(0.0, 0.55, length((p - float2(-0.22, -0.62)) * float2(0.75, 1.7)));
    col.rgb += 0.18 * spec;
    return col;
}
"""

/** iOS-style text loupe: an oval glass lens floating above the finger, magnifying the page around [src]. */
@Composable
private fun Loupe(layer: GraphicsLayer, src: Offset, page: Color) {
    val w = 176.dp; val h = 72.dp; val zoom = 1.25f
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RuntimeShader(LENS_SHADER) else null }
    val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.6f)))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = constraints.maxWidth
        Box(
            Modifier
                .offset {
                    val x = (src.x - w.toPx() / 2).coerceIn(8.dp.toPx(), maxW - w.toPx() - 8.dp.toPx())
                    IntOffset(x.roundToInt(), (src.y - 74.dp.toPx() - h.toPx() / 2).roundToInt().coerceAtLeast(0))
                }
                .size(w, h)
                .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.35f))
                .border(1.5.dp, rim, CircleShape)
                .graphicsLayer {
                    shape = CircleShape; clip = true
                    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                        shader.setFloatUniform("size", size.width, size.height)
                        shader.setFloatUniform("bulge", 0.32f)
                        renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
                    }
                }
                .background(page)
                .drawBehind {
                    translate(size.width / 2, size.height / 2) {
                        scale(zoom, pivot = Offset.Zero) { translate(-src.x, -src.y) { drawLayer(layer) } }
                    }
                },
        )
    }
}

/** iOS selection handle: a caret bar with a round knob above the start or below the end; drag to adjust. */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    caret: () -> Rect?,
    rootCoords: () -> LayoutCoordinates?,
    onStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val touchW = 48.dp; val pad = 22.dp; val knob = 12.dp
    Box(
        Modifier
            .offset {
                caret()?.let { IntOffset((it.left - touchW.toPx() / 2).roundToInt(), (it.top - pad.toPx()).roundToInt()) }
                    ?: IntOffset(-10_000, -10_000)
            }
            .layout { m, _ ->
                val w = touchW.roundToPx()
                val ht = ((caret()?.height ?: 0f) + 2 * pad.toPx()).roundToInt().coerceAtLeast(1)
                val pl = m.measure(Constraints.fixed(w, ht))
                layout(w, ht) { pl.place(0, 0) }
            }
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(isStart) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val root = rootCoords() ?: return@awaitEachGesture
                    val me = coords[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val c0 = caret() ?: return@awaitEachGesture
                    // Track the caret line, not the fingertip, so the handle doesn't jump when grabbed.
                    val grab = Offset(c0.left, c0.center.y) - root.localPositionOf(me, down.position)
                    onStart()
                    try {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            if (c.positionChanged()) coords[0]?.takeIf { it.isAttached }?.let { onMove(root.localPositionOf(it, c.position) + grab) }
                            c.consume()
                        }
                    } finally { onEnd() }
                }
            }
            .drawBehind {
                val r = caret() ?: return@drawBehind
                val x = size.width / 2
                val t = pad.toPx(); val b = t + r.height
                drawLine(HandleBlue, Offset(x, t), Offset(x, b), 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(HandleBlue, knob.toPx() / 2, Offset(x, if (isStart) t - knob.toPx() / 2 + 1f else b + knob.toPx() / 2 - 1f))
            },
    )
}

/** Two-finger pinch steps the font size; consumes events so the list doesn't scroll meanwhile. */
private suspend fun PointerInputScope.pinchToResize(onStep: (Float) -> Unit) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var acc = 1f
    do {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        if (ev.changes.count { it.pressed } >= 2) {
            acc *= ev.calculateZoom()
            if (acc > 1.10f) { onStep(1f); acc = 1f } else if (acc < 0.91f) { onStep(-1f); acc = 1f }
            ev.changes.forEach { it.consume() }
        }
    } while (ev.changes.any { it.pressed })
}

private fun wordAt(text: String, offset: Int): IntRange {
    val i = offset.coerceIn(0, text.length)
    var s = i; var e = i
    while (s > 0 && !text[s - 1].isWhitespace()) s--
    while (e < text.length && !text[e].isWhitespace()) e++
    if (s == e && e < text.length) e++
    return s..e
}

@Composable
private fun Paragraph(
    text: String,
    style: TextStyle,
    ref: ParaRef,
    highlights: List<Triple<IntRange, Int, Int>>,
    /** Emoji to show just after the given character offset (the end of a reacted highlight). */
    reactions: List<Pair<Int, String>>,
    selection: IntRange?,
    searchTerm: String?,
    onTap: (Int) -> Unit,
    onLong: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val measurer = rememberTextMeasurer()
    val annotated = remember(text, highlights, selection, searchTerm) {
        buildAnnotatedString {
            append(text)
            highlights.forEach { (r, color, hlStyle) ->
                if (hlStyle == 0 && !r.isEmpty()) addStyle(SpanStyle(background = HighlightColors[color % HighlightColors.size].copy(alpha = 0.38f)), r.first, r.last + 1)
            }
            if (!searchTerm.isNullOrBlank()) {
                var idx = text.indexOf(searchTerm, ignoreCase = true)
                while (idx >= 0) {
                    addStyle(SpanStyle(background = SelectionColor.copy(alpha = 0.55f)), idx, idx + searchTerm.length)
                    idx = text.indexOf(searchTerm, idx + searchTerm.length, ignoreCase = true)
                }
            }
            selection?.let { addStyle(SpanStyle(background = SelectionColor.copy(alpha = 0.7f)), it.first, it.last + 1) }
        }
    }
    Text(
        annotated, style = style, onTextLayout = { ref.layout = it },
        modifier = modifier.onGloballyPositioned { ref.coords = it }
            .drawBehind {
                val l = ref.layout ?: return@drawBehind
                highlights.forEach { (r, color, hlStyle) ->
                    if (hlStyle == 1 && !r.isEmpty()) underline(l, r, HighlightColors[color % HighlightColors.size], 2.4.dp.toPx(), 3.5.dp.toPx())
                }
            }
            .drawWithContent {
                drawContent()
                val l = ref.layout ?: return@drawWithContent
                // Reaction sits like a small superscript at the end of the highlight.
                reactions.forEach { (end, emoji) ->
                    val len = l.layoutInput.text.length
                    if (len == 0) return@forEach
                    val ci = end.coerceIn(0, len - 1)
                    val m = measurer.measure(emoji, TextStyle(fontSize = (style.fontSize.value * 0.8f).sp))
                    val x = (l.getBoundingBox(ci).right - m.size.width * 0.15f).coerceAtMost(size.width - m.size.width)
                    val y = l.getLineTop(l.getLineForOffset(ci)) - m.size.height * 0.35f
                    drawText(m, topLeft = Offset(x, y))
                }
            }
            .pointerInput(text) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var up: PointerInputChange? = null
                val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation(); true }
                if (finished == true) {
                    val u = up
                    if (u != null) { u.consume(); ref.layout?.let { tap(it.getOffsetForPosition(u.position)) } }
                    return@awaitEachGesture
                }
                val c0 = ref.coords?.takeIf { it.isAttached } ?: return@awaitEachGesture
                long(c0.localToWindow(down.position))
                try {
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { c.consume(); break }
                        if (c.positionChanged()) ref.coords?.takeIf { it.isAttached }?.let { drag(it.localToWindow(c.position)) }
                        c.consume()
                    }
                } finally { end() }
            }
        },
    )
}

@Composable
private fun ChapterEnd(next: String?, ink: Color, onNext: () -> Unit, onPosts: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⁂", color = ink.copy(alpha = 0.4f), fontSize = 22.sp)
        Spacer(Modifier.height(24.dp))
        if (next != null) {
            Text("NEXT CHAPTER", color = ink.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(next, color = ink, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            PrimaryButton("Continue Reading", onNext, icon = Icons.Rounded.ChevronRight)
        } else {
            Text("The End", color = ink, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Turn this book into posts", onPosts, icon = Icons.Rounded.AutoAwesome)
        }
    }
}

/** Draws a rounded underline below every line segment of [r], following the text layout. */
private fun DrawScope.underline(l: TextLayoutResult, r: IntRange, color: Color, thickness: Float, gap: Float) {
    val len = l.layoutInput.text.length
    if (len == 0) return
    val first = r.first.coerceIn(0, len - 1)
    val last = r.last.coerceIn(0, len - 1)
    for (line in l.getLineForOffset(first)..l.getLineForOffset(last)) {
        val a = maxOf(first, l.getLineStart(line))
        val b = minOf(last, l.getLineEnd(line, visibleEnd = true) - 1)
        if (b < a) continue
        val y = l.getLineBaseline(line) + gap
        drawLine(color, Offset(l.getBoundingBox(a).left, y), Offset(l.getBoundingBox(b).right, y), thickness, StrokeCap.Round)
    }
}

@Composable
private fun SelectionBar(
    activeColor: Int?,
    isExisting: Boolean,
    style: Int,
    onStyle: (Int) -> Unit,
    onColor: (Int) -> Unit,
    reaction: String?,
    onReact: (String) -> Unit,
    onNote: () -> Unit,
    onPost: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val g = LocalG.current
    Column(
        Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().panel(RoundedCornerShape(30.dp)).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Reactions appear once the highlight exists.
        AnimatedVisibility(isExisting) {
            Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReactionEmojis.forEach { e ->
                    val on = e == reaction
                    Text(
                        e, fontSize = 24.sp,
                        modifier = Modifier.clip(CircleShape)
                            .background(if (on) g.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .border(if (on) 1.5.dp else 0.dp, if (on) g.accent else Color.Transparent, CircleShape)
                            .clickable { onReact(e) }.padding(horizontal = 8.dp, vertical = 4.dp)
                            .graphicsLayer { val k = if (on) 1.12f else 1f; scaleX = k; scaleY = k },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Marker or underline for this highlight.
            Row(Modifier.height(34.dp).clip(CircleShape).background(g.fill).padding(2.dp)) {
                listOf(Icons.Rounded.BorderColor to "Marker", Icons.Rounded.FormatUnderlined to "Underline").forEachIndexed { i, (icon, label) ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (style == i) g.accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onStyle(i) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, label, tint = if (style == i) g.accent else g.text2, modifier = Modifier.size(18.dp)) }
                }
            }
            HighlightColors.forEachIndexed { i, col ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(col)
                        .then(if (activeColor == i) Modifier.border(3.dp, g.text, CircleShape) else Modifier)
                        .clickable { onColor(i) },
                )
            }
            if (isExisting) Icon(Icons.Rounded.DeleteOutline, "Delete highlight", tint = g.destructive, modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDelete).padding(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(Icons.Rounded.EditNote, "Note", onNote)
            BarAction(Icons.Rounded.FormatQuote, "Post", onPost)
            BarAction(Icons.Rounded.ContentCopy, "Copy", onCopy)
            BarAction(Icons.Rounded.IosShare, "Share", onShare)
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val g = LocalG.current
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = g.text, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 12.sp, color = g.text)
    }
}

/** Floating capsule menu anchored bottom-right (spec 3.8: Contents · %, Highlights, Search, Themes). */
@Composable
private fun ReaderMenu(
    progress: Float,
    highlightCount: Int,
    onDismiss: () -> Unit,
    onContents: () -> Unit,
    onHighlights: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onShare: () -> Unit,
    onPosts: () -> Unit,
) {
    val g = LocalG.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(16.dp).width(290.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MenuRow("Contents · ${(progress * 100).toInt()}%", Icons.Rounded.FormatListBulleted, onContents, primary = true)
            MenuRow(if (highlightCount > 0) "Bookmarks & Highlights · $highlightCount" else "Bookmarks & Highlights", Icons.Rounded.FormatQuote, onHighlights)
            MenuRow("Search Book", Icons.Rounded.Search, onSearch)
            MenuRow("Themes & Settings", Icons.Rounded.TextFields, onSettings)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Share", onShare, Modifier.weight(1f), icon = Icons.Rounded.IosShare, height = 56.dp)
                PillButton("Posts", onPosts, Modifier.weight(1f), icon = Icons.Rounded.AutoAwesome, height = 56.dp)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, primary: Boolean = false) {
    val g = LocalG.current
    val fg = if (primary) g.onPrimary else g.text
    Row(
        Modifier.fillMaxWidth().height(58.dp)
            .then(if (primary) Modifier.clip(CircleShape).background(g.primary.copy(alpha = 0.92f)) else Modifier.panel(CircleShape))
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(icon, null, tint = fg)
    }
}

@Composable
fun ContentsSheet(
    vm: AppViewModel,
    book: Book,
    chapters: List<Chapter>,
    current: Int,
    highlights: List<Highlight>,
    initialTab: Int,
    subtitle: String,
    onChapter: (Int) -> Unit,
    onHighlight: (Highlight) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = LocalG.current
    var tab by remember { mutableIntStateOf(initialTab) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (initialTab == 0) (current - 2).coerceAtLeast(0) else 0)
    GSheet(onDismiss, "", full = true, heightFraction = 0.9f) {
        Row(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(88.dp), elevation = 6.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(book.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 16.sp, color = g.text2)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(40.dp).clip(CircleShape).background(g.fill).padding(3.dp)) {
            listOf("Contents", "Highlights · ${highlights.size}").forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxSize().clip(CircleShape).background(if (tab == i) g.surface else Color.Transparent).clickable { tab = i },
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.text) }
            }
        }
        LazyColumn(state = list, modifier = Modifier.padding(top = 8.dp).weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tab == 0) itemsIndexed(chapters) { i, c ->
                Row(
                    Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(if (i == current) g.fill else Color.Transparent)
                        .clickable { onChapter(i) }.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.title, fontSize = 18.sp, fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium, color = g.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (c.startPage >= 0) "p. ${c.startPage + 1}" else "${i + 1}", fontSize = 16.sp, color = g.text2)
                }
            } else {
                if (highlights.isEmpty()) item {
                    Text("Long-press a word, drag to select, then pick a colour.", color = g.text2, modifier = Modifier.padding(24.dp))
                }
                items(highlights.sortedBy { it.chapter * 100_000 + it.block }, key = { it.id }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { onHighlight(h) }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(HighlightColors[h.color % HighlightColors.size]))
                            Text(chapters.getOrNull(h.chapter)?.title.orEmpty(), fontSize = 13.sp, color = g.text2, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false), maxLines = 1)
                            h.reaction?.let { Text(it, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp)) }
                        }
                        Text(h.text, fontFamily = QuoteSerif, fontSize = 17.sp, color = g.text, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        if (!h.note.isNullOrBlank()) Text("✎ ${h.note}", fontSize = 14.sp, color = g.text2, modifier = Modifier.padding(top = 4.dp))
                    }
                    HorizontalDivider(Modifier.padding(start = 24.dp), color = g.separator)
                }
            }
        }
    }
}

@Composable
private fun SearchSheet(chapters: List<Chapter>, onDismiss: () -> Unit, onResult: (Int, Int, String) -> Unit) {
    val g = LocalG.current
    var q by remember { mutableStateOf("") }
    val results by produceState(emptyList<Triple<Int, Int, AnnotatedString>>(), q) {
        if (q.trim().length < 2) { value = emptyList(); return@produceState }
        delay(220)
        value = withContext(Dispatchers.Default) {
            val term = q.trim()
            buildList {
                chapters.forEachIndexed { ci, c ->
                    c.blocks.forEachIndexed { bi, b ->
                        val idx = b.text.indexOf(term, ignoreCase = true)
                        if (idx >= 0 && size < 200) {
                            val s = (idx - 60).coerceAtLeast(0); val e = (idx + term.length + 90).coerceAtMost(b.text.length)
                            add(Triple(ci, bi, buildAnnotatedString {
                                if (s > 0) append("…")
                                append(b.text.substring(s, idx))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = SelectionColor.copy(alpha = 0.5f))) { append(b.text.substring(idx, idx + term.length)) }
                                append(b.text.substring(idx + term.length, e))
                                if (e < b.text.length) append("…")
                            }))
                        }
                    }
                }
            }
        }
    }
    GSheet(onDismiss, "Search Book", full = true, heightFraction = 0.9f) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(g.fill).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = g.text2)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("In this book", color = g.text2, fontSize = 17.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = g.text, fontSize = 17.sp), cursorBrush = SolidColor(g.accent), modifier = Modifier.fillMaxWidth())
            }
        }
        if (q.trim().length >= 2) Text("${results.size}${if (results.size >= 200) "+" else ""} results", fontSize = 13.sp, color = g.text2, modifier = Modifier.padding(start = 24.dp, top = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results) { (ci, bi, snippet) ->
                Column(Modifier.fillMaxWidth().clickable { onResult(ci, bi, q.trim()) }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(chapters[ci].title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 1)
                    Text(snippet, fontFamily = QuoteSerif, fontSize = 16.sp, color = g.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                HorizontalDivider(Modifier.padding(start = 24.dp), color = g.separator)
            }
        }
    }
}

@Composable
fun ReaderSettingsSheet(vm: AppViewModel, s: ReaderSettings, dark: Boolean, onDismiss: () -> Unit) {
    val g = LocalG.current
    val activity = LocalContext.current as? android.app.Activity
    var brightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f)
    }
    GSheet(onDismiss, "Themes & Settings") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SegmentCapsule(Modifier.weight(1f), height = 52.dp, tint = g.fill) {
                    CapsuleSegment({ vm.updateSettings { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(MIN_FONT)) } }, Modifier.weight(1f)) { Text("A", fontSize = 17.sp, color = g.text) }
                    CapsuleDivider()
                    Text("${s.fontSize.toInt()}", fontSize = 15.sp, color = g.text2, modifier = Modifier.padding(horizontal = 10.dp))
                    CapsuleDivider()
                    CapsuleSegment({ vm.updateSettings { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(MAX_FONT)) } }, Modifier.weight(1f)) { Text("A", fontSize = 27.sp, color = g.text) }
                }
            }
            FontPicker(s.font) { id -> vm.updateSettings { it.copy(font = id) } }
            SettingSlider(Icons.Rounded.LightMode, brightness, 0.02f..1f) { v ->
                brightness = v
                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v } }
            }
            SettingSlider(Icons.Rounded.FormatLineSpacing, s.lineHeight, 1.2f..2.2f) { v -> vm.updateSettings { it.copy(lineHeight = v) } }
            SettingSlider(Icons.Rounded.FormatIndentIncrease, s.margin.toFloat(), 8f..56f) { v -> vm.updateSettings { it.copy(margin = v.toInt()) } }
            LazyVerticalGrid(
                GridCells.Fixed(3), Modifier.padding(top = 8.dp).height(232.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                userScrollEnabled = false,
            ) {
                gridItemsIndexed(ReaderThemes) { i, t ->
                    val bg = if (dark) t.pageDark else t.page
                    val fg = if (dark) t.textDark else t.text
                    Column(
                        Modifier.height(110.dp).clip(RoundedCornerShape(22.dp)).background(bg)
                            .border(if (s.theme == i) 3.dp else 0.7.dp, if (s.theme == i) g.text else g.separator, RoundedCornerShape(22.dp))
                            .clickable { vm.updateSettings { it.copy(theme = i) } },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Aa", fontFamily = readingFont(s.font).family, fontSize = 32.sp, color = fg, fontWeight = if (t.bold) FontWeight.Black else FontWeight.Normal)
                        Text(t.name, fontSize = 15.sp, color = fg, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Column(Modifier.padding(vertical = 14.dp).clip(RoundedCornerShape(22.dp)).background(g.surface)) {
                OptionRow("Bold Text", null, s.bold) { on -> vm.updateSettings { it.copy(bold = on) } }
                HorizontalDivider(Modifier.padding(start = 20.dp), color = g.separator)
                OptionRow("Justify Text", null, s.justify) { on -> vm.updateSettings { it.copy(justify = on) } }
            }
            Text(
                "Tip: pinch the page with two fingers to change text size.",
                fontSize = 13.sp, color = g.text2, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

/** Font row that expands inline into a list, each name set in its own typeface (spec frame 165). */
@Composable
fun FontPicker(selected: String, onPick: (String) -> Unit) {
    val g = LocalG.current
    var open by rememberSaveable { mutableStateOf(false) }
    val current = readingFont(selected)
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(g.surface).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Aa", fontFamily = current.family, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = g.text)
            Text("Font", fontSize = 17.sp, color = g.text, modifier = Modifier.padding(start = 14.dp).weight(1f))
            Text(current.name, fontSize = 17.sp, color = g.text2)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = g.text2, modifier = Modifier.padding(start = 4.dp).rotate(arrow))
        }
        if (open) ReadingFonts.forEach { f ->
            HorizontalDivider(Modifier.padding(start = 18.dp), color = g.separator)
            Row(
                Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.name, fontFamily = f.family, fontSize = 20.sp, color = g.text, modifier = Modifier.weight(1f))
                Text("like ${f.like}", fontSize = 13.sp, color = g.text2)
                Box(Modifier.padding(start = 10.dp).size(22.dp)) {
                    if (f.id == selected) Icon(Icons.Rounded.Check, "Selected", tint = g.text)
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(icon: androidx.compose.ui.graphics.vector.ImageVector, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = g.text2, modifier = Modifier.size(22.dp))
        GSlider(value, range, onChange, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun NoteDialog(initial: String, quote: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val g = LocalG.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(34.dp),
        containerColor = g.sheet,
        title = { Text("Note") },
        text = {
            Column {
                Text("“$quote”", fontFamily = QuoteSerif, color = g.text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 12.dp), placeholder = { Text("What does this mean to you?") }, minLines = 3)
            }
        },
        confirmButton = { TextButton({ onSave(text.trim()) }) { Text("Save", color = g.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = g.text) } },
    )
}
