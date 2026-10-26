package com.gbook.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.ui.theme.LocalG
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bottom-bar tab: a symbol [icon] and a Noto 3D emoji drawable [emoji] for the 3D icon style. */
class NavBarItem(val label: String, val icon: ImageVector, @DrawableRes val emoji: Int)

private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Floating pill navigation bar (after Nuvio): a blurred dark capsule whose selected tab sits in an accent-tinted
 * pill that springs between tabs, stretching while it moves. The pill can be dragged across tabs. Labels fold
 * away while content scrolls down ([collapsed]). With [onAdd], an accent "+" sits in the middle slot.
 */
@Composable
fun FloatingNavBar(
    items: List<NavBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    collapsed: Boolean,
    haze: HazeState?,
    emojiIcons: Boolean,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
    /** Separate round button beside the bar (iOS 26 style); [selected] is -1 while it is the active screen. */
    search: NavBarItem? = null,
    onSearch: () -> Unit = {},
) {
    val g = LocalG.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // Index of the add-button slot, or -1 when there is none.
    val mid = if (onAdd != null) items.size / 2 else -1
    val slots = items.size + if (onAdd != null) 1 else 0
    fun slotOf(tab: Int) = if (mid < 0 || tab < mid) tab else tab + 1
    fun tabOf(slot: Int) = when { mid < 0 || slot < mid -> slot; slot == mid -> null; else -> slot - 1 }

    val labels by animateFloatAsState(if (collapsed) 0f else 1f, tween(300, easing = Standard), label = "labels")
    val pos = remember { Animatable(slotOf(selected).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val press by animateFloatAsState(if (dragging) 1f else 0f, spring(stiffness = 600f), label = "press")
    val current by rememberUpdatedState(selected)

    LaunchedEffect(selected) {
        if (!dragging && selected >= 0) pos.animateTo(slotOf(selected).toFloat(), spring(dampingRatio = 0.66f, stiffness = 420f))
    }
    val shown = if (dragging) dragPos else pos.value
    val activeSlot = if (selected < 0 && !dragging) -1 else shown.roundToInt()

    val shape = CircleShape
    val edge = if (g.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)))
    else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.10f)))
    val fill = if (g.dark) Color(0xFF1C1C1E) else Color.White
    val side = if (search != null) 16.dp else 28.dp
    Row(
        modifier.fillMaxWidth().navigationBarsPadding()
            .padding(bottom = 10.dp, start = side + 30.dp * (1f - labels), end = side + 30.dp * (1f - labels)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(
            Modifier.weight(1f, fill = false).widthIn(max = 420.dp).fillMaxWidth().height(50.dp + 16.dp * labels)
                .graphicsLayer { val s = 1f + 0.025f * press; scaleX = s; scaleY = s }
                .shadow(if (g.dark) 0.dp else 14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
                .clip(shape)
                .then(
                    if (haze != null) Modifier.hazeEffect(haze) {
                        blurRadius = 24.dp
                        backgroundColor = fill
                        tints = listOf(HazeTint(fill.copy(alpha = if (g.dark) 0.55f else 0.72f)))
                        noiseFactor = 0f
                    } else Modifier.background(fill.copy(alpha = 0.92f)),
                )
                .border(0.75.dp, edge, shape)
                .pointerInput(slots) {
                    val slotW = { (size.width - 8.dp.toPx()) / slots }
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragPos = ((o.x - 4.dp.toPx()) / slotW() - 0.5f).coerceIn(0f, slots - 1f)
                            dragging = true
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            val before = dragPos.roundToInt()
                            dragPos = (dragPos + dx / slotW()).coerceIn(0f, slots - 1f)
                            if (dragPos.roundToInt() != before) haptics.tick()
                        },
                        onDragEnd = {
                            var slot = dragPos.roundToInt()
                            // The add button is not a tab: settle on the nearer neighbour.
                            if (mid >= 0 && slot == mid) slot = if (dragPos < mid) mid - 1 else mid + 1
                            val tab = tabOf(slot) ?: current
                            scope.launch {
                                pos.snapTo(dragPos)
                                dragging = false
                                if (tab != current) onSelect(tab)
                                else pos.animateTo(slotOf(tab).toFloat(), spring(dampingRatio = 0.66f, stiffness = 420f))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                pos.snapTo(dragPos)
                                dragging = false
                                pos.animateTo(slotOf(current).toFloat(), spring(dampingRatio = 0.66f, stiffness = 420f))
                            }
                        },
                    )
                }
                .drawBehind {
                    val inset = 4.dp.toPx()
                    val slotW = (size.width - 2 * inset) / slots
                    val v = if (dragging) 0f else abs(pos.velocity)
                    // Jelly: stretch along the motion, squash across it, and swell a little while held.
                    val sx = (1f + (v * 0.05f).coerceAtMost(0.34f)) * (1f + 0.12f * press)
                    val sy = (1f - (v * 0.02f).coerceAtMost(0.14f)) * (1f + 0.06f * press)
                    val cx = inset + (shown + 0.5f) * slotW
                    val w = (slotW - 2.dp.toPx()) * sx
                    val h = (size.height - 2 * inset) * sy
                    if (press > 0f) drawCircle(
                        Brush.radialGradient(listOf(g.accent.copy(alpha = 0.22f * press), Color.Transparent), Offset(cx, size.height / 2), size.width * 0.45f),
                        size.width * 0.45f, Offset(cx, size.height / 2),
                    )
                    if (activeSlot >= 0) drawRoundRect(
                        g.accent.copy(alpha = 0.15f),
                        Offset(cx - w / 2, (size.height - h) / 2), Size(w, h), CornerRadius(h / 2),
                    )
                },
        ) {
            Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (slot in 0 until slots) {
                    val tab = tabOf(slot)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (tab == null && onAdd != null) {
                            Box(
                                Modifier.size(40.dp + 4.dp * labels)
                                    .shadow(8.dp, CircleShape, ambientColor = g.accent, spotColor = g.accent)
                                    .clip(CircleShape).background(g.accentBrush)
                                    .clickable(role = Role.Button) { haptics.tap(); onAdd() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, "Add a book", tint = g.onAccent, modifier = Modifier.size(26.dp)) }
                        } else if (tab != null) {
                            NavTab(items[tab], active = slot == activeSlot, labels = labels, emojiIcons = emojiIcons, scale = if (slot == activeSlot) 1f + 0.12f * press else 1f) {
                                haptics.tap(); onSelect(tab)
                            }
                        }
                    }
                }
            }
        }
        if (search != null) {
            val on = selected < 0
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            Box(
                Modifier.size(50.dp + 16.dp * labels)
                    .shadow(if (g.dark) 0.dp else 14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
                    .clip(shape)
                    .then(
                        if (haze != null) Modifier.hazeEffect(haze) {
                            blurRadius = 24.dp
                            backgroundColor = fill
                            tints = listOf(HazeTint(fill.copy(alpha = if (g.dark) 0.55f else 0.72f)))
                            noiseFactor = 0f
                        } else Modifier.background(fill.copy(alpha = 0.92f)),
                    )
                    .border(0.75.dp, edge, shape)
                    .then(if (on) Modifier.background(g.accent.copy(alpha = 0.15f)) else Modifier)
                    .clickable(role = Role.Tab) { haptics.tap(); onSearch() },
                contentAlignment = Alignment.Center,
            ) {
                val color by animateColorAsState(if (on) g.accent else g.text2, label = "search")
                NavIcon(search, on, emojiIcons, 24.dp + 2.dp * labels, color, gradient = true)
            }
        }
    }
}

@Composable
private fun NavTab(item: NavBarItem, active: Boolean, labels: Float, emojiIcons: Boolean, scale: Float, onClick: () -> Unit) {
    val g = LocalG.current
    val color by animateColorAsState(if (active) g.accent else g.text2, label = "tab")
    Column(
        Modifier.fillMaxSize().clip(CircleShape).clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        NavIcon(item, active, emojiIcons, 24.dp + 2.dp * labels, color, gradient = true)
        Box(Modifier.height(16.dp * labels).fillMaxWidth().clipToBounds().alpha(labels)) {
            Text(
                item.label, color = color, fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Symbol icon (accent-gradient when active) or Noto 3D emoji (desaturated when inactive). */
@Composable
private fun NavIcon(item: NavBarItem, active: Boolean, emojiIcons: Boolean, size: Dp, color: Color, gradient: Boolean) {
    val g = LocalG.current
    if (emojiIcons) {
        Image(
            painterResource(item.emoji), item.label,
            colorFilter = if (active) null else Desaturate,
            modifier = Modifier.size(size + 4.dp).alpha(if (active) 1f else 0.7f)
                .graphicsLayer { val s = if (active) 1.08f else 1f; scaleX = s; scaleY = s },
        )
    } else {
        Icon(
            item.icon, item.label,
            tint = if (active && gradient) Color.White else color,
            modifier = Modifier.size(size).then(if (active && gradient) Modifier.gradientMask(g.accentBrush) else Modifier),
        )
    }
}

private val Desaturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })

/**
 * The original iOS 26-style bar: a frosted capsule holding every tab but the last, plus a separate round button
 * for the last tab (Settings). While content scrolls down the capsule shrinks to just the selected tab.
 */
@Composable
fun ClassicNavBar(
    items: List<NavBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    collapsed: Boolean,
    haze: HazeState?,
    emojiIcons: Boolean,
    modifier: Modifier = Modifier,
) {
    val g = LocalG.current
    val haptics = rememberHaptics()
    val main = items.dropLast(1)
    val lastIndex = items.lastIndex
    Row(
        modifier.navigationBarsPadding().padding(bottom = 8.dp, start = 20.dp, end = 20.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.animateContentSize().height(62.dp).frosted(CircleShape, haze).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (collapsed && selected < main.size) listOf(selected) else main.indices.toList()
            shown.forEach { i ->
                val on = i == selected
                Column(
                    Modifier.height(54.dp).clip(CircleShape)
                        .background(if (on && !collapsed) g.text.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { haptics.tap(); onSelect(i) }
                        .padding(horizontal = if (collapsed) 14.dp else 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    NavIcon(items[i], on, emojiIcons, 24.dp, if (on) g.accent else g.text, gradient = false)
                    if (!collapsed) Text(items[i].label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) g.accent else g.text)
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        val on = selected == lastIndex
        Box(
            Modifier.size(62.dp).frosted(CircleShape, haze).clickable { haptics.tap(); onSelect(lastIndex) },
            contentAlignment = Alignment.Center,
        ) { NavIcon(items[lastIndex], on, emojiIcons, 28.dp, if (on) g.accent else g.text, gradient = false) }
    }
}

/** Frosted glass used by the classic bar: backdrop blur, translucent tint and a light hairline. */
@Composable
private fun Modifier.frosted(shape: Shape, haze: HazeState?): Modifier {
    val g = LocalG.current
    val tint = if (g.dark) Color(0xFF1C1C1E).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f)
    return this
        .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
        .clip(shape)
        .then(
            if (haze != null) Modifier.hazeEffect(haze) {
                blurRadius = 24.dp
                noiseFactor = 0f
                tints = listOf(HazeTint(tint))
                backgroundColor = g.bg
            } else Modifier.background(g.chrome),
        )
        .border(0.7.dp, if (g.dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f), shape)
}
