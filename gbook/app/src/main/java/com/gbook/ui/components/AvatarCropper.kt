package com.gbook.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f

/**
 * Returns a launcher that picks a photo, opens [AvatarCropper] on it and hands the cropped square to [onCropped].
 * Call it from composition; the cropper shows itself while a photo is being cropped.
 */
@Composable
fun rememberAvatarPicker(onCropped: (Bitmap) -> Unit): () -> Unit {
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked = it }
    picked?.let { uri -> AvatarCropper(uri, onDone = { picked = null; onCropped(it) }, onCancel = { picked = null }) }
    return { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Full-screen crop: pinch and drag the photo under a circular window, then save that circle as a 512px square. */
@Composable
fun AvatarCropper(uri: Uri, onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val src by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { decodeUpright(ctx, uri) }.getOrNull() } }
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bmp = src
            if (bmp == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else CropArea(bmp, onCancel, onDone)
        }
    }
}

@Composable
private fun CropArea(bmp: Bitmap, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    val g = LocalG.current
    val image = remember(bmp) { bmp.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat(); val vh = constraints.maxHeight.toFloat()
        val d = min(vw, vh) - with(density) { 48.dp.toPx() }
        // Scale at which the photo's short side just covers the circle.
        val cover = d / min(bmp.width, bmp.height)
        fun clamp(o: Offset, z: Float): Offset {
            val s = cover * z
            val mx = max(0f, (bmp.width * s - d) / 2); val my = max(0f, (bmp.height * s - d) / 2)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(bmp) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val z = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                        off = clamp((off + pan) * (z / zoom), z); zoom = z
                    }
                }
                .pointerInput(bmp) {
                    detectTapGestures(onDoubleTap = {
                        val z = if (zoom > 1.5f) 1f else 2.5f
                        off = clamp(off * (z / zoom), z); zoom = z
                    })
                },
        ) {
            val s = cover * zoom
            val w = bmp.width * s; val h = bmp.height * s
            drawImage(
                image, dstOffset = IntOffset((size.width / 2 + off.x - w / 2).roundToInt(), (size.height / 2 + off.y - h / 2).roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            )
            // Dim everything outside the circle.
            val c = Offset(size.width / 2, size.height / 2)
            val hole = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addOval(androidx.compose.ui.geometry.Rect(c, d / 2))
            }
            drawPath(hole, Color.Black.copy(alpha = 0.62f))
            drawCircle(Color.White.copy(alpha = 0.85f), d / 2, c, style = Stroke(1.5.dp.toPx()))
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Move and scale", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Pinch to zoom, drag to fit your face in the circle", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
            GSlider(zoom, 1f..MAX_ZOOM, { z -> off = clamp(off * (z / zoom), z); zoom = z }, Modifier.fillMaxWidth().padding(bottom = 16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Cancel", onCancel, Modifier.weight(1f), height = 52.dp)
                PrimaryButton("Use photo", {
                    val s = cover * zoom
                    val side = (d / s).coerceAtMost(min(bmp.width, bmp.height).toFloat())
                    val left = (bmp.width / 2f - (off.x + d / 2) / s).coerceIn(0f, bmp.width - side)
                    val top = (bmp.height / 2f - (off.y + d / 2) / s).coerceIn(0f, bmp.height - side)
                    val crop = Bitmap.createBitmap(bmp, left.roundToInt(), top.roundToInt(), side.roundToInt().coerceAtLeast(1), side.roundToInt().coerceAtLeast(1))
                    onDone(Bitmap.createScaledBitmap(crop, 512, 512, true))
                }, Modifier.weight(1f))
            }
        }
    }
}

/** Decodes [uri] at most ~2048px on its long side, honouring EXIF rotation where the platform can. */
private fun decodeUpright(ctx: android.content.Context, uri: Uri): Bitmap {
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > 2048) dec.setTargetSampleSize((long + 2047) / 2048)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
}
