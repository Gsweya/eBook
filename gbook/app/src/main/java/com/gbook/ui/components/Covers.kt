package com.gbook.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.ui.theme.DisplaySerif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val bitmapCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
fun rememberFileBitmap(path: String?, maxWidth: Int = 700): ImageBitmap? {
    val v by produceState(path?.let { bitmapCache.get("$it@$maxWidth") }, path, maxWidth) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { bitmapCache.put("$path@$maxWidth", it) }
        }
    }
    return v
}

/** Book cover with spine hinge and deep soft shadow (spec component 15). */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    author: String,
    color: Long,
    modifier: Modifier = Modifier,
    corner: Dp = 4.dp,
    elevation: Dp = 10.dp,
) {
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = corner, bottomEnd = corner)
    val bmp = rememberFileBitmap(coverPath)
    Box(
        modifier
            .aspectRatio(0.66f)
            .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(Color(color)),
    ) {
        if (bmp != null) {
            Image(bmp, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(title, author, Color(color))
        }
        // Spine hinge
        Box(
            Modifier.fillMaxHeight().width(6.dp).background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f), Color.Transparent)),
            ),
        )
    }
}

@Composable
private fun GeneratedCover(title: String, author: String, color: Color) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, color = Color.White, fontFamily = DisplaySerif, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, maxLines = 5,
            )
            Text(author, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 2)
        }
    }
}
