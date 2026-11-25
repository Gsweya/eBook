package com.gbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.gbook.ui.theme.HighlightColors
import com.gbook.ui.theme.LocalG

/** Names for [HighlightColors], in the same order. */
private val ColorNames = listOf("Yellow", "Green", "Blue", "Pink", "Purple")

/** Centres the popup on its anchor, above it when there is room and below otherwise, kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= 0) above else anchorBounds.bottom + gap
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y)
    }
}

/**
 * A pill naming the chosen highlight colour. Tapping it opens a small panel with the colours and a "No color"
 * choice; [onPick] gets the index into [HighlightColors], or null for no colour. [emptyLabel] names the pill
 * while nothing is chosen.
 */
@Composable
fun ColorPickerButton(selected: Int?, emptyLabel: String, onPick: (Int?) -> Unit, noneLabel: String = "No color") {
    val g = LocalG.current
    var open by remember { mutableStateOf(false) }
    val chosen = selected?.let { it % HighlightColors.size }
    Box {
        Row(
            Modifier.height(34.dp).clip(CircleShape).background(g.fill).clickable { open = true }.padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chosen != null) Box(Modifier.size(22.dp).clip(CircleShape).background(HighlightColors[chosen]))
            else Icon(Icons.Rounded.Palette, null, tint = g.text2, modifier = Modifier.size(22.dp))
            Text(
                chosen?.let { ColorNames[it] } ?: emptyLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = g.text,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (open) {
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { AboveAnchor(gap) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(Modifier.width(IntrinsicPanelWidth).panel(RoundedCornerShape(24.dp), elevation = 10.dp).padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HighlightColors.forEachIndexed { i, col ->
                            Swatch(col, ColorNames[i], on = i == chosen) { open = false; onPick(i) }
                        }
                    }
                    Row(
                        Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape)
                            .background(if (chosen == null) g.accent.copy(alpha = 0.16f) else g.fill)
                            .clickable { open = false; onPick(null) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = g.text2, modifier = Modifier.size(18.dp))
                        Text(noneLabel, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = g.text, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/** Five 40dp swatches, their gaps and the panel's padding. */
private val IntrinsicPanelWidth = (40 * 5 + 10 * 4 + 12 * 2).dp

@Composable
private fun Swatch(color: Color, name: String, on: Boolean, onClick: () -> Unit) {
    val g = LocalG.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color)
            .then(if (on) Modifier.border(2.5.dp, g.text, CircleShape) else Modifier)
            .clickable(onClickLabel = name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The palette is bright in both themes, so a dark tick reads on all of it.
        if (on) Icon(Icons.Rounded.Check, name, tint = Color(0xFF1C1C1E), modifier = Modifier.size(20.dp))
    }
}
