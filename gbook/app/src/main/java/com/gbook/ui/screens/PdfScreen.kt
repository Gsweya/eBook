package com.gbook.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.data.Book
import com.gbook.data.BookContent
import com.gbook.data.ReadingPosition
import com.gbook.ui.components.CapsuleDivider
import com.gbook.ui.components.CapsuleSegment
import com.gbook.ui.components.SegmentCapsule
import com.gbook.ui.components.IconCircle
import com.gbook.ui.components.PillButton
import com.gbook.ui.components.panel
import com.gbook.ui.components.TrackReadingTime
import com.gbook.ui.components.GoalBanner
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Thread-safe wrapper around PdfRenderer with a byte-bounded bitmap cache. */
private class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val count = renderer.pageCount
    val ratios: FloatArray = FloatArray(count) { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
    private val cache = object : LruCache<String, ImageBitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun render(index: Int, widthPx: Int): ImageBitmap = cache.get("$index@$widthPx") ?: withContext(Dispatchers.IO) {
        lock.withLock {
            renderer.openPage(index).use { p ->
                val w = widthPx.coerceIn(200, 2800)
                val h = (w / ratios[index]).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp.asImageBitmap().also { cache.put("$index@$widthPx", it) }
            }
        }
    }

    fun close() { runCatching { renderer.close(); pfd.close() } }
}

@Composable
fun PdfScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onOpenBook: (Book) -> Unit) {
    val data by vm.data.collectAsState()
    val book = data.books.firstOrNull { it.id == id } ?: return
    var textMode by rememberSaveable { mutableStateOf(false) }
    val progress by vm.importer.pdfProgress.collectAsState()

    if (textMode) {
        ReaderScreen(vm, id, null, null, onBack, onOpenBook, modeToggle = {
            IconCircle(Icons.Rounded.Description, "Original pages", { textMode = false }, size = 44.dp)
        })
        return
    }
    val reflowLabel = progress[id]?.let { "Reflow ${(it * 100).toInt()}%" } ?: "Reflow"
    PdfPages(vm, id, onBack, reflowLabel = reflowLabel, onReflow = {
        if (!book.textReady) vm.importer.extractPdf(book)
        textMode = true
    })
}

@OptIn(FlowPreview::class)
@Composable
private fun PdfPages(vm: AppViewModel, id: String, onBack: () -> Unit, reflowLabel: String, onReflow: () -> Unit) {
    val g = LocalG.current
    val book = vm.store.book(id) ?: return
    val doc = remember(id) { runCatching { PdfDoc(vm.store.bookFile(book)) }.getOrNull() }
    DisposableEffect(doc) { onDispose { doc?.close() } }
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Couldn't open this PDF", color = g.text) }
        return
    }
    val content by produceState<BookContent?>(null, book.textReady) { value = vm.store.content(id) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = book.position.pdfPage.coerceIn(0, doc.count - 1))
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var showContents by remember { mutableStateOf(false) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pageBg = if (g.dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EC)

    val pageActivity by remember(list) { derivedStateOf { list.firstVisibleItemIndex * 10 + list.firstVisibleItemScrollOffset / 400 } }
    TrackReadingTime(vm, id, pageActivity to chrome)

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.debounce(400).collect { p ->
            val pos = (vm.store.book(id)?.position ?: ReadingPosition()).copy(pdfPage = p)
            vm.savePosition(id, pos, (p + 1f) / doc.count)
        }
    }

    fun setZoom(z: Float, focusX: Float? = null, viewportW: Float = 0f) {
        val old = zoom
        val nz = z.coerceIn(1f, 5f)
        if (nz == old) return
        zoom = nz
        // Keep the focus point under the fingers while zooming horizontally.
        if (focusX != null) scope.launch {
            val target = ((hScroll.value + focusX) * nz / old - focusX).toInt()
            hScroll.scrollTo(target.coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize().background(pageBg)) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val c = ev.calculateCentroid()
                                setZoom(zoom * ev.calculateZoom(), c.x)
                                val pan = ev.calculatePan()
                                scope.launch { hScroll.scrollBy(-pan.x); list.scrollBy(-pan.y) }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { o -> setZoom(if (zoom > 1.2f) 1f else 2.5f, o.x) },
                    )
                },
        ) {
            val viewport = maxWidth
            val density = LocalDensity.current
            val itemWidth = viewport * zoom
            // Re-render at a resolution matching the zoom, bucketed to avoid thrashing.
            val bucket = (kotlin.math.ceil(zoom * 2) / 2f)
            val renderPx = with(density) { (viewport * bucket).toPx() }.toInt()
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = zoom > 1f)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.requiredWidth(itemWidth).fillMaxSize(),
                    contentPadding = PaddingValues(top = top + 64.dp, bottom = bottom + 96.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(doc.count) { i ->
                        var bmp by remember(i) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(i, renderPx) { bmp = doc.render(i, renderPx) }
                        Box(Modifier.fillMaxWidth().aspectRatio(doc.ratios[i]).background(Color.White)) {
                            bmp?.let { Image(it, "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Icons.Rounded.ArrowBackIosNew, "Back", onBack, size = 44.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PillButton(book.title, { showContents = true }, Modifier.widthIn(max = 220.dp), height = 44.dp)
                }
                Spacer(Modifier.width(8.dp))
                PillButton(reflowLabel, onReflow, icon = Icons.Rounded.TextFields, height = 44.dp)
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${list.firstVisibleItemIndex + 1} of ${doc.count}", fontSize = 16.sp, color = g.text,
                    modifier = Modifier.panel(CircleShape).padding(horizontal = 16.dp, vertical = 14.dp),
                )
                Spacer(Modifier.weight(1f))
                SegmentCapsule(height = 52.dp) {
                    CapsuleSegment({ setZoom(zoom - 0.5f, 0f) }, enabled = zoom > 1f) { Icon(Icons.Rounded.ZoomOut, "Zoom out", tint = g.text) }
                    CapsuleDivider()
                    Text("${(zoom * 100).toInt()}%", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = g.text, modifier = Modifier.padding(horizontal = 10.dp))
                    CapsuleDivider()
                    CapsuleSegment({ setZoom(zoom + 0.5f, 0f) }, enabled = zoom < 5f) { Icon(Icons.Rounded.ZoomIn, "Zoom in", tint = g.text) }
                }
                Spacer(Modifier.width(10.dp))
                IconCircle(Icons.Rounded.FormatListBulleted, "Contents", { showContents = true }, size = 52.dp)
            }
        }
        GoalBanner(vm, Modifier.align(Alignment.TopCenter))
    }
    if (showContents) {
        val chapters = content?.chapters.orEmpty()
        val current = chapters.indexOfLast { it.startPage in 0..list.firstVisibleItemIndex }.coerceAtLeast(0)
        ContentsSheet(
            vm, book, chapters, current, emptyList(), 0,
            subtitle = if (chapters.isEmpty()) "Preparing outline…" else "Page ${list.firstVisibleItemIndex + 1} of ${doc.count}",
            onChapter = { i -> showContents = false; scope.launch { list.scrollToItem(chapters[i].startPage.coerceAtLeast(0)) } },
            onHighlight = {},
            onDismiss = { showContents = false },
        )
    }
}
