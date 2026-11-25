package com.gbook.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import com.gbook.ui.theme.BarIconsOver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * A book's pictures full screen, opened by tapping one. Pinch or double-tap to zoom, drag to look around a
 * zoomed picture, swipe sideways for the chapter's other pictures, and swipe up or down (or Back) to close.
 * Tapping shows or hides the bar with Close, Share and Save.
 */
@Composable
fun ImageViewer(paths: List<String>, start: Int, shareText: String, onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The viewer paints its own black, which fades as it is swiped away; the dialog's dimming would stay.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BarIconsOver(Color.Black)

        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val pager = rememberPagerState(start.coerceIn(0, paths.lastIndex)) { paths.size }
        var chrome by remember { mutableStateOf(true) }
        var zoomedPage by remember { mutableStateOf<Int?>(null) }
        var dragY by remember { mutableFloatStateOf(0f) }
        var note by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(note) { if (note != null) { delay(2200); note = null } }

        fun save() = scope.launch {
            val f = File(paths[pager.currentPage])
            note = if (saveImageFile(ctx, f)) "Saved to Pictures/GBook" else "Couldn't save the image"
        }
        val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) save() else note = "GBook needs storage access to save images"
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = constraints.maxHeight.toFloat()
            val dismissAt = with(LocalDensity.current) { 110.dp.toPx() }
            val fade = (1f - abs(dragY) / (h * 0.55f)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade)))

            HorizontalPager(
                pager,
                userScrollEnabled = zoomedPage != pager.currentPage,
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationY = dragY
                    val s = 1f - 0.12f * (1f - fade)
                    scaleX = s; scaleY = s
                },
            ) { i ->
                ZoomableImage(
                    path = paths[i],
                    active = pager.settledPage == i,
                    onTap = { chrome = !chrome },
                    onZoomed = { z -> zoomedPage = if (z) i else zoomedPage.takeUnless { it == i } },
                    onDrag = { dy -> dragY += dy },
                    onDragEnd = { velocity ->
                        scope.launch {
                            if (abs(dragY) > dismissAt || abs(velocity) > 1800f) {
                                val target = if ((if (abs(velocity) > 1800f) velocity else dragY) > 0) h else -h
                                animate(dragY, target, animationSpec = tween(200)) { v, _ -> dragY = v }
                                onDismiss()
                            } else {
                                animate(dragY, 0f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { v, _ -> dragY = v }
                            }
                        }
                    },
                )
            }

            AnimatedVisibility(chrome && dragY == 0f, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val glass = Color.White.copy(alpha = 0.16f)
                    IconCircle(Icons.Rounded.Close, "Close", onDismiss, size = 44.dp, tint = glass, iconTint = Color.White)
                    Spacer(Modifier.weight(1f))
                    if (paths.size > 1) Text(
                        "${pager.currentPage + 1} of ${paths.size}", color = Color.White.copy(alpha = 0.85f),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconCircle(Icons.Rounded.IosShare, "Share image", {
                        runCatching { shareImageFile(ctx, File(paths[pager.currentPage]), shareText) }
                            .onFailure { note = "Couldn't share the image" }
                    }, size = 44.dp, tint = glass, iconTint = Color.White)
                    IconCircle(Icons.Rounded.Download, "Save image", {
                        val allowed = Build.VERSION.SDK_INT >= 29 ||
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        if (allowed) save() else storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }, size = 44.dp, tint = glass, iconTint = Color.White)
                }
            }

            AnimatedVisibility(
                note != null, enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            ) {
                Text(
                    note.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Decodes [path] no larger than [maxDim] on its longer side (in powers of two, as the decoder samples). */
private fun decodeImage(path: String, maxDim: Int): ImageBitmap? {
    if (!File(path).exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun ZoomableImage(
    path: String,
    /** Whether this is the picture settled on screen; the others go back to fitting the screen. */
    active: Boolean,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    /** Vertical drag of an unzoomed picture, which closes the viewer if it goes far enough. */
    onDrag: (Float) -> Unit,
    onDragEnd: (velocity: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The reader's copy shows at once; a sharper one, enough to zoom into, replaces it when decoded.
    val preview = rememberFileBitmap(path, 1200)
    val screen = LocalWindowInfo.current.containerSize
    val maxDim = (max(screen.width, screen.height) * 2).coerceIn(1600, 4096)
    val sharp by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { decodeImage(path, maxDim) }.getOrNull() } }
    val bmp = sharp ?: preview

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    val zoomed = scale > 1.01f
    LaunchedEffect(zoomed) { onZoomed(zoomed) }

    /** The picture's size when fitted to the screen, before zooming. */
    fun fitted(): Size {
        val b = bmp ?: return Size.Zero
        if (box.width == 0 || box.height == 0) return Size.Zero
        val k = minOf(box.width / b.width.toFloat(), box.height / b.height.toFloat())
        return Size(b.width * k, b.height * k)
    }
    /** Keeps the zoomed picture covering the screen: it can't be dragged past its own edges. */
    fun clamp(o: Offset, s: Float): Offset {
        val f = fitted()
        val mx = ((f.width * s - box.width) / 2f).coerceAtLeast(0f)
        val my = ((f.height * s - box.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun animateTo(s: Float, o: Offset) = scope.launch {
        val s0 = scale; val o0 = offset
        animate(0f, 1f, animationSpec = tween(280, easing = FastOutSlowInEasing)) { t, _ ->
            scale = lerp(s0, s, t); offset = lerp(o0, o, t)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1.01f) animateTo(1f, Offset.Zero)
                        else {
                            // Zoom in on the point tapped.
                            val c = p - Offset(size.width / 2f, size.height / 2f)
                            animateTo(DOUBLE_TAP_ZOOM, clamp(c * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM))
                        }
                    },
                )
            }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 0: not yet known; 1: zoom and pan; 2: drag to close; 3: a sideways swipe, left to the pager.
                    var mode = 0
                    var travel = Offset.Zero
                    val velocity = VelocityTracker()
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.none { it.pressed }) break
                        if (mode == 0 && e.changes.any { it.isConsumed }) break
                        val zoom = e.calculateZoom()
                        val pan = e.calculatePan()
                        if (mode == 0) {
                            travel += pan
                            val fingers = e.changes.count { it.pressed }
                            if (fingers >= 2 && zoom != 1f) mode = 1
                            else if (travel.getDistance() > viewConfiguration.touchSlop) mode = when {
                                scale > 1.01f -> 1
                                abs(travel.y) > abs(travel.x) -> 2
                                else -> 3
                            }
                        }
                        when (mode) {
                            1 -> {
                                val centroid = e.calculateCentroid(useCurrent = false) - Offset(size.width / 2f, size.height / 2f)
                                val ns = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                offset = clamp((offset - centroid) * (ns / scale) + centroid + pan, ns)
                                scale = ns
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            2 -> {
                                onDrag(pan.y)
                                e.changes.firstOrNull { it.pressed }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            3 -> break
                        }
                    }
                    if (mode == 2) onDragEnd(velocity.calculateVelocity().y)
                    if (mode == 1 && scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
        else Image(
            bmp, "Picture from the book", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}
