package com.gbook.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.R
import com.gbook.ui.components.Avatar
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.rememberAvatarPicker
import com.gbook.ui.theme.AppSans
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.GType
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** First-run welcome: an animated splash, then profile picture and name, then importing the first books. */
@Composable
fun WelcomeFlow(vm: AppViewModel) {
    val g = LocalG.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(step > 0) { step-- }
    Box(Modifier.fillMaxSize().background(g.bg)) {
        AnimatedContent(
            step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(320)) + slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 4 * dir }) togetherWith
                    (fadeOut(tween(200)) + slideOutHorizontally(tween(300)) { -it / 4 * dir })
            },
            label = "welcome",
        ) { s ->
            when (s) {
                0 -> Splash(onStart = { step = 1 })
                1 -> ProfileStep(vm, onNext = { step = 2 })
                else -> BooksStep(vm, onDone = vm::finishOnboarding)
            }
        }
        if (step > 0) Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 1..2) {
                val on = i == step
                val c by animateColorAsState(if (on) g.accent else g.text2.copy(alpha = 0.3f), label = "dot")
                Box(Modifier.height(6.dp).width(if (on) 22.dp else 6.dp).clip(CircleShape).background(c).animateContentSize())
            }
        }
    }
}

/** The mark pops in with a ring of light, the wordmark rises after it, then the call to action. */
@Composable
private fun Splash(onStart: () -> Unit) {
    val g = LocalG.current
    val mark = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch { mark.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        delay(180)
        launch { ring.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        delay(260)
        word.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        delay(250)
        ready = true
    }
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { if (ready) onStart() }) {
        // Soft accent glow behind the mark.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val c = Offset(size.width / 2, size.height * 0.42f)
                drawCircle(Brush.radialGradient(listOf(g.accent.copy(alpha = 0.22f * mark.value), Color.Transparent), c, size.width * 0.75f), size.width * 0.75f, c)
                val r = 70.dp.toPx() + ring.value * size.width * 0.45f
                drawCircle(g.accent.copy(alpha = 0.35f * (1f - ring.value)), r, c, style = Stroke(2.dp.toPx()))
            },
        )
        Column(
            Modifier.align(Alignment.Center).offset(y = (-40).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(112.dp)
                    .graphicsLayer { scaleX = 0.4f + 0.6f * mark.value; scaleY = 0.4f + 0.6f * mark.value; alpha = mark.value.coerceIn(0f, 1f); rotationZ = (1f - mark.value) * -14f }
                    .clip(RoundedCornerShape(30.dp)).background(g.accentBrush),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_book_glyph), null, colorFilter = ColorFilter.tint(g.onAccent), modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "GBook", fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 44.sp, color = g.text,
                letterSpacing = (6 * (1f - word.value)).sp,
                modifier = Modifier.graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 24.dp.toPx() },
            )
            Text(
                "Read. Highlight. Remember.", style = GType.secondary, color = g.text2,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = word.value; translationY = (1f - word.value) * 32.dp.toPx() },
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(ready, enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 }) {
                PrimaryButton("Get Started", onStart, Modifier.fillMaxWidth())
            }
            Text(
                "props to Ame", style = GType.caption, color = g.text2.copy(alpha = 0.7f), letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 16.dp).graphicsLayer { alpha = word.value },
            )
        }
    }
}

@Composable
private fun ProfileStep(vm: AppViewModel, onNext: () -> Unit) {
    val g = LocalG.current
    val s = vm.data.collectAsState().value.settings
    val choose = rememberAvatarPicker { vm.saveAvatar(it) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.6f))
        Text("Make it yours", style = GType.largeTitle, color = g.text, textAlign = TextAlign.Center)
        Text(
            "Add a photo and your name. They stay on this device.", style = GType.secondary, color = g.text2,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(vm.avatarFile, s.avatarVersion, s.profileName, 136.dp, Modifier.clip(CircleShape).clickable(onClick = choose))
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(g.accent).border(3.dp, g.bg, CircleShape).clickable(onClick = choose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PhotoCamera, "Choose photo", tint = g.onAccent, modifier = Modifier.size(20.dp)) }
        }
        Text(
            if (s.avatarVersion == 0L) "Choose a photo" else "Change photo", color = g.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp).clip(CircleShape).clickable(onClick = choose).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Box(
            Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(g.fill).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (s.profileName.isEmpty()) Text("Your name", fontSize = 17.sp, color = g.text2)
            BasicTextField(
                s.profileName, { name -> vm.updateSettings { it.copy(profileName = name.take(40)) } },
                singleLine = true, textStyle = TextStyle(color = g.text, fontSize = 17.sp, fontFamily = AppSans),
                cursorBrush = SolidColor(g.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", onNext, Modifier.fillMaxWidth())
        Text(
            "Skip", color = g.text2, fontSize = 16.sp,
            modifier = Modifier.padding(vertical = 12.dp).clip(CircleShape).clickable(onClick = onNext).padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun BooksStep(vm: AppViewModel, onDone: () -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    val busy by vm.busy.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importMany(uris) }
    val recent = remember(data.books) { data.books.sortedByDescending { it.addedAt }.take(5) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.6f))
        Text("Bring your books", style = GType.largeTitle, color = g.text, textAlign = TextAlign.Center)
        Text(
            "Import EPUB and PDF files from your phone or Google Drive. Pick as many as you like.", style = GType.secondary, color = g.text2,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        val dash = g.accent.copy(alpha = 0.6f)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(g.accent.copy(alpha = 0.07f))
                .drawBehind {
                    drawRoundRect(
                        dash, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
                    )
                }
                .clickable(enabled = busy == null) { picker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream")) }
                .padding(vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(g.accentBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.FileUpload, null, tint = g.onAccent, modifier = Modifier.size(28.dp))
            }
            Text(busy ?: "Choose Books", style = GType.navTitle, color = g.text, modifier = Modifier.padding(top = 12.dp))
            Text("EPUB · PDF · multiple files", style = GType.caption, color = g.text2)
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
                recent.forEachIndexed { i, b ->
                    BookCover(
                        vm.coverPath(b), b.title, b.author, b.coverColor,
                        Modifier.height(84.dp).graphicsLayer { rotationZ = (i - (recent.size - 1) / 2f) * 6f },
                        elevation = 6.dp,
                    )
                }
            }
            Text(
                if (data.books.size == 1) "1 book in your library" else "${data.books.size} books in your library",
                style = GType.secondary, color = g.text2, modifier = Modifier.padding(top = 14.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(if (data.books.isEmpty()) "Skip for Now" else "Start Reading", onDone, Modifier.fillMaxWidth())
        Spacer(Modifier.height(52.dp))
    }
}
