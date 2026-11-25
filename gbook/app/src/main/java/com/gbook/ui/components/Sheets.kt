package com.gbook.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** Bottom sheet with the iOS 26 38dp top radius and a round close button (spec component 11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GSheet(
    onDismiss: () -> Unit,
    title: String,
    full: Boolean = false,
    /** Fixed height as a fraction of the space below the status bar. Sheets whose scrolling list would
     *  otherwise size them close to the maximum keep re-settling ("bouncing"); a fixed height avoids that. */
    heightFraction: Float? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val g = LocalG.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = full)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = g.sheet,
        shape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        // The default insets pad the sheet's top by however much of the status bar it currently covers. That
        // padding changes the sheet's height, the height moves where the sheet rests, and that changes the
        // padding again, so a tall sheet never settles and keeps bouncing. Instead the sheet stops short of the
        // status bar, so its size never depends on where it is.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(
            Modifier
                .layout { m, c ->
                    val room = (c.maxHeight - statusBar.roundToPx()).coerceAtLeast(0)
                    val p = m.measure(c.copy(maxHeight = room, minHeight = c.minHeight.coerceAtMost(room)))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .navigationBarsPadding()
                .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = g.text, modifier = Modifier.weight(1f))
                trailing?.invoke()
                Spacer(Modifier.padding(4.dp))
                IconCircle(Icons.Rounded.Close, "Close", onDismiss, size = 44.dp, tint = g.fill, iconTint = g.text2)
            }
            content()
        }
    }
}

@Composable
fun GSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = LocalG.current
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = g.toggleOn, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = if (g.dark) Color(0xFF39393D) else Color(0xFFE3E3E6), uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Share"))
}

private fun Bitmap.writePng(out: OutputStream) {
    val soft = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    soft.compress(Bitmap.CompressFormat.PNG, 100, out)
}

/** Saves [bitmap] to the phone's Pictures/GBook folder; returns whether it worked. Android 9 and older need storage permission first. */
suspend fun saveImage(ctx: Context, bitmap: Bitmap): Boolean =
    saveToPictures(ctx, "GBook-${System.currentTimeMillis()}.png", "image/png") { bitmap.writePng(it) }

/** Saves a copy of the image file [file] (a book's picture, as it came in the book) to Pictures/GBook. */
suspend fun saveImageFile(ctx: Context, file: File): Boolean =
    saveToPictures(ctx, "GBook-${System.currentTimeMillis()}.${file.extension.ifEmpty { "png" }.lowercase()}", imageMime(file)) { out ->
        file.inputStream().use { it.copyTo(out) }
    }

/** The MIME type for an image file, from its extension. */
fun imageMime(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "bmp" -> "image/bmp"
    else -> "image/png"
}

private suspend fun saveToPictures(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GBook")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore refused the image")
            try {
                resolver.openOutputStream(uri)!!.use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GBook").apply { mkdirs() }
            val f = File(dir, name)
            f.outputStream().use(write)
            MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
        }
    }.onFailure { Log.w("GBook", "couldn't save image", it) }.isSuccess
}

/** Shares an image file from the book's own folder, without copying it. */
fun shareImageFile(ctx: Context, file: File, text: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType(imageMime(file))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share image"))
}

suspend fun shareImage(ctx: Context, bitmap: Bitmap, text: String) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val f = File(dir, "gbook-post-${System.currentTimeMillis() % 100000}.png")
    withContext(Dispatchers.IO) { f.outputStream().use { bitmap.writePng(it) } }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share post"))
}
