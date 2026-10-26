package com.gbook.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Profile picture, or the name's initial (or a person glyph) on the accent gradient. */
@Composable
fun Avatar(file: File, version: Long, name: String, size: Dp, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val bmp by produceState<ImageBitmap?>(null, file, version) {
        value = if (version == 0L) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }
    }
    Box(modifier.size(size).clip(CircleShape).background(g.accentBrush), contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b, "Profile picture", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            name.isNotBlank() -> Text(name.trim().take(1).uppercase(), color = g.onAccent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
            else -> Icon(Icons.Rounded.Person, null, tint = g.onAccent, modifier = Modifier.size(size * 0.55f))
        }
    }
}
