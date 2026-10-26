package com.gbook.ui.components

import androidx.compose.animation.animateColorAsState
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

class NavBarItem(val label: String, val icon: ImageVector)

private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Floating pill navigation bar (after Nuvio): a blurred dark capsule whose selected tab sits in an accent-tinted
 * pill that springs between tabs, stretching while it moves. The pill can be dragged across tabs. Labels fold
 * away while content scrolls down ([collapsed]). An accent "+" sits in the middle slot for adding books.
 */
@Composable
fun FloatingNavBar(
    items: List<NavBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    collapsed: Boolean,
    haze: HazeState?,
    modifier: Modifier = Modifier,
) {
    val g = LocalG.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val mid = items.size / 2
    val slots = items.size + 1
    fun slotOf(tab: Int) = if (tab < mid) tab else tab + 1
    fun tabOf(slot: Int) = when { slot < mid -> slot; slot == mid -> null; else -> slot - 1 }

    val labels by animateFloatAsState(if (collapsed) 0f else 1f, tween(300, easing = Standard), label = "labels")
    val pos = remember { Animatable(slotOf(selected).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val press by animateFloatAsState(if (dragging) 1f else 0f, spring(stiffness = 600f), label = "press")
    val current by rememberUpdatedState(selected)

    LaunchedEffect(selected) {
        if (!dragging) pos.animateTo(slotOf(selected).toFloat(), spring(dampingRatio = 0.66f, stiffness = 420f))
    }
    val shown = if (dragging) dragPos else pos.value
    val activeSlot = shown.roundToInt()

    Box(
        modifier.fillMaxWidth().navigationBarsPadding()
            .padding(bottom = 10.dp, start = 28.dp + 30.dp * (1f - labels), end = 28.dp + 30.dp * (1f - labels)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = CircleShape
        val edge = if (g.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)))
        else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.10f)))
        val fill = if (g.dark) Color(0xFF1C1C1E) else Color.White
        Box(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().height(50.dp + 16.dp * labels)
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
                            if (slot == mid) slot = if (dragPos < mid) mid - 1 else mid + 1
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
                    drawRoundRect(
                        g.accent.copy(alpha = 0.15f),
                        Offset(cx - w / 2, (size.height - h) / 2), Size(w, h), CornerRadius(h / 2),
                    )
                },
        ) {
            Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (slot in 0 until slots) {
                    val tab = tabOf(slot)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (tab == null) {
                            Box(
                                Modifier.size(40.dp + 4.dp * labels)
                                    .shadow(8.dp, CircleShape, ambientColor = g.accent, spotColor = g.accent)
                                    .clip(CircleShape).background(g.accentBrush)
                                    .clickable(role = Role.Button) { haptics.tap(); onAdd() },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, "Add a book", tint = g.onAccent, modifier = Modifier.size(26.dp)) }
                        } else {
                            NavTab(items[tab], active = slot == activeSlot, labels = labels, scale = if (slot == activeSlot) 1f + 0.12f * press else 1f) {
                                haptics.tap(); onSelect(tab)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavTab(item: NavBarItem, active: Boolean, labels: Float, scale: Float, onClick: () -> Unit) {
    val g = LocalG.current
    val color by animateColorAsState(if (active) g.accent else g.text2, label = "tab")
    Column(
        Modifier.fillMaxSize().clip(CircleShape).clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            item.icon, item.label,
            tint = if (active) Color.White else color,
            modifier = Modifier.size(24.dp + 2.dp * labels).then(if (active) Modifier.gradientMask(g.accentBrush) else Modifier),
        )
        Box(Modifier.height(15.dp * labels).fillMaxWidth().clipToBounds().alpha(labels)) {
            Text(
                item.label, color = color, fontSize = 11.sp, lineHeight = 14.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
