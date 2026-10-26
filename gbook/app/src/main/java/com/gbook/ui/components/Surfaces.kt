package com.gbook.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.ui.theme.LocalG

/**
 * Floating control surface in the Nuvio style: a solid tonal fill, a soft shadow and a hairline edge that
 * catches light along the top. [tint] overrides the fill.
 */
@Composable
fun Modifier.panel(shape: Shape, tint: Color? = null, elevation: Dp = 6.dp): Modifier {
    val g = LocalG.current
    val edge = if (g.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.03f)))
    else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.09f)))
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape)
        .background(tint ?: g.chrome)
        .border(0.75.dp, edge, shape)
}

/** Paints the content (e.g. an icon) with [brush], keeping only its alpha. Used for accent-gradient icons. */
fun Modifier.gradientMask(brush: Brush): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache { onDrawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcIn) } }

@Composable
private fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    val haptics = rememberHaptics()
    return this
        .scale(s)
        .clickable(src, indication = null, enabled = enabled) {
            haptics.tap()
            onClick()
        }
}

@Composable
fun IconCircle(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color? = null,
    iconTint: Color = LocalG.current.text,
    enabled: Boolean = true,
) {
    Box(
        modifier.pressable(onClick, enabled).size(size).panel(CircleShape, tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) iconTint else iconTint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    tint: Color? = null,
    contentColor: Color = LocalG.current.text,
    enabled: Boolean = true,
) {
    Row(
        modifier.pressable(onClick, enabled).height(height).panel(CircleShape, tint).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) contentColor else contentColor.copy(alpha = 0.35f)
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Grouped capsule holding several segments (e.g. A | A stepper, zoom −/+). */
@Composable
fun SegmentCapsule(modifier: Modifier = Modifier, height: Dp = 48.dp, tint: Color? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(height).panel(CircleShape, tint),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun RowScope.CapsuleSegment(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.fillMaxHeight().pressable(onClick, enabled).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun CapsuleDivider() {
    Box(Modifier.width(1.dp).height(22.dp).background(LocalG.current.text.copy(alpha = 0.15f)))
}

/** Solid accent call-to-action. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, container: Color? = null, content: Color? = null) {
    val g = LocalG.current
    Row(
        modifier.pressable(onClick).height(52.dp).clip(CircleShape).background(container ?: g.primary).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = content ?: g.onPrimary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = content ?: g.onPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** iOS-style slider: 6dp track, white capsule thumb that grows while dragged (spec component 8). */
@Composable
fun GSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (dragging) 1.15f else 1f, label = "thumb")
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(
        modifier.height(40.dp).pointerInput(range) {
            fun emit(x: Float) = change(range.start + (x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start))
            awaitEachGesture {
                val down = awaitFirstDown()
                dragging = true
                emit(down.position.x); down.consume()
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    emit(c.position.x); c.consume()
                }
                dragging = false
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumbW = 40.dp
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(g.fill))
        Box(Modifier.width(maxWidth * frac).height(6.dp).clip(CircleShape).background(g.primary))
        Box(
            Modifier.padding(start = (maxWidth - thumbW) * frac).scale(thumbScale).width(thumbW).height(26.dp)
                .shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/**
 * Bottom padding for a tab's scrolling content, so its last item clears the floating bottom bar and the
 * system navigation bar (gesture pill or three buttons) instead of hiding underneath them.
 */
@Composable
fun bottomBarClearance(extra: Dp = 0.dp): Dp =
    androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp + extra
