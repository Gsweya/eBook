package com.gbook.ui.theme

import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Resolved colour tokens for the current accent palette and light/dark mode. */
@Immutable
data class GColors(
    val bg: Color,
    val grouped: Color,
    val surface: Color,
    val fill: Color,
    val text: Color,
    val text2: Color,
    val separator: Color,
    /** Floating controls (buttons, pills, bars) over content. */
    val chrome: Color,
    /** Hairline edge of floating controls. */
    val border: Color,
    val primary: Color,
    val onPrimary: Color,
    val sheet: Color,
    val accent: Color,
    val onAccent: Color,
    val accentGradient: List<Color>,
    val destructive: Color,
    val toggleOn: Color,
    val dark: Boolean,
) {
    val accentBrush: Brush get() = if (accentGradient.size >= 2) Brush.linearGradient(accentGradient) else SolidColor(accent)
}

/**
 * Accent palettes in the style of Nuvio: each tints the near-black dark surfaces slightly towards its hue.
 * [accentStrong] is used instead of [accent] on light backgrounds where the bright accent lacks contrast.
 */
@Immutable
data class AccentPalette(
    val id: String,
    val name: String,
    val accent: Color,
    val accentStrong: Color,
    val gradient: List<Color>,
    val onAccent: Color,
    val bg: Color,
    val elevated: Color,
    val card: Color,
)

val AccentPalettes = listOf(
    AccentPalette("amber", "Amber", Color(0xFFFB8C00), Color(0xFFE07800), listOf(Color(0xFFFFB74D), Color(0xFFFB8C00), Color(0xFFEF6C00)), Color(0xFF111111), Color(0xFF0F0D0D), Color(0xFF1E1A1A), Color(0xFF24201A)),
    AccentPalette("gold", "Gold", Color(0xFFE8A91C), Color(0xFFB07A00), listOf(Color(0xFF8A5700), Color(0xFFE8A91C), Color(0xFFFFF1A8), Color(0xFFFFD45C), Color(0xFF9A6200)), Color(0xFF111111), Color(0xFF0F0E0B), Color(0xFF1D1A14), Color(0xFF262116)),
    AccentPalette("jade", "Jade", Color(0xFF22D37C), Color(0xFF0E9E5A), listOf(Color(0xFF7BF08D), Color(0xFF22D37C), Color(0xFF0BBF9A)), Color(0xFF111111), Color(0xFF0B0F0D), Color(0xFF141D18), Color(0xFF16251D)),
    AccentPalette("rosegold", "Rose Gold", Color(0xFFEC70A9), Color(0xFFC94A88), listOf(Color(0xFFB75AFF), Color(0xFFEC70A9), Color(0xFFFFB37A)), Color(0xFF111111), Color(0xFF100C0F), Color(0xFF1F161D), Color(0xFF281A24)),
    AccentPalette("arctic", "Arctic Blue", Color(0xFF3185F5), Color(0xFF2A6FD6), listOf(Color(0xFF4DE3FF), Color(0xFF3185F5), Color(0xFF4D55E8)), Color.White, Color(0xFF0B0E14), Color(0xFF141A24), Color(0xFF161E2A)),
    AccentPalette("graphite", "Graphite", Color(0xFFAAB2BE), Color(0xFF5B6573), listOf(Color(0xFFF3F5F7), Color(0xFFAAB2BE), Color(0xFF687381)), Color(0xFF111111), Color(0xFF0C0D0F), Color(0xFF17191D), Color(0xFF20242A)),
    AccentPalette("crimson", "Crimson", Color(0xFFE53935), Color(0xFFC62828), listOf(Color(0xFFFF5252), Color(0xFFE53935), Color(0xFFC62828)), Color.White, Color(0xFF0D0D0D), Color(0xFF1A1A1A), Color(0xFF241A1A)),
    AccentPalette("ocean", "Ocean", Color(0xFF1E88E5), Color(0xFF1565C0), listOf(Color(0xFF42A5F5), Color(0xFF1E88E5), Color(0xFF1565C0)), Color.White, Color(0xFF0D0D0F), Color(0xFF1A1A1E), Color(0xFF1A1F24)),
    AccentPalette("violet", "Violet", Color(0xFFAB47BC), Color(0xFF8E24AA), listOf(Color(0xFFCE93D8), Color(0xFFAB47BC), Color(0xFF6A1B9A)), Color.White, Color(0xFF0D0D0F), Color(0xFF1A1A1E), Color(0xFF1F1A24)),
    AccentPalette("emerald", "Emerald", Color(0xFF43A047), Color(0xFF2E7D32), listOf(Color(0xFF66BB6A), Color(0xFF43A047), Color(0xFF2E7D32)), Color.White, Color(0xFF0D0D0D), Color(0xFF1A1A1A), Color(0xFF1A241A)),
    AccentPalette("rose", "Rose", Color(0xFFD81B60), Color(0xFFC2185B), listOf(Color(0xFFEC407A), Color(0xFFD81B60), Color(0xFFC2185B)), Color.White, Color(0xFF0D0D0D), Color(0xFF1A1A1A), Color(0xFF241A1F)),
)

fun accentPalette(id: String) = AccentPalettes.firstOrNull { it.id == id } ?: AccentPalettes[0]

fun gColors(p: AccentPalette, dark: Boolean, amoled: Boolean): GColors = if (dark) {
    val bg = if (amoled) Color.Black else p.bg
    GColors(
        bg = bg, grouped = bg, surface = p.elevated, fill = p.card,
        text = Color(0xFFF5F7F8), text2 = Color(0xFF969CA3), separator = Color(0xFF252A2A),
        chrome = p.card, border = Color.White.copy(alpha = 0.08f),
        primary = p.accent, onPrimary = p.onAccent, sheet = p.elevated,
        accent = p.accent, onAccent = p.onAccent, accentGradient = p.gradient,
        destructive = Color(0xFFE36A8A), toggleOn = p.accent, dark = true,
    )
} else {
    GColors(
        bg = Color(0xFFF6F6F7), grouped = Color(0xFFF6F6F7), surface = Color.White, fill = Color(0xFFECECEF),
        text = Color(0xFF111111), text2 = Color(0xFF6B7078), separator = Color(0xFFE3E4E7),
        chrome = Color.White, border = Color.Black.copy(alpha = 0.06f),
        primary = p.accentStrong, onPrimary = if (p.onAccent == Color.White) Color.White else Color(0xFF111111), sheet = Color(0xFFF6F6F7),
        accent = p.accentStrong, onAccent = p.onAccent, accentGradient = p.gradient,
        destructive = Color(0xFFD9363E), toggleOn = p.accentStrong, dark = false,
    )
}

val LocalG = staticCompositionLocalOf { gColors(AccentPalettes[0], dark = true, amoled = false) }

val SerifTitle = TextStyle(fontFamily = DisplaySerif, fontWeight = FontWeight.Bold)

object GType {
    val largeTitle = SerifTitle.copy(fontSize = 34.sp, lineHeight = 40.sp)
    val section = SerifTitle.copy(fontSize = 24.sp, lineHeight = 30.sp)
    val navTitle = TextStyle(fontFamily = AppSans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = AppSans, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    val secondary = TextStyle(fontFamily = AppSans, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontFamily = AppSans, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val eyebrow = TextStyle(fontFamily = AppSans, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
}

@Immutable
data class ReaderTheme(
    val name: String,
    val page: Color,
    val text: Color,
    val pageDark: Color,
    val textDark: Color,
    val bold: Boolean = false,
)

/** Theme tiles from spec frame 150 / dark variants from 155-159. */
val ReaderThemes = listOf(
    ReaderTheme("Original", Color.White, Color.Black, Color.Black, Color.White),
    ReaderTheme("Quiet", Color(0xFF4F4F51), Color(0xFFB8B8BA), Color.Black, Color(0xFF8E8E93)),
    ReaderTheme("Paper", Color(0xFFEFEFEF), Color(0xFF1C1C1E), Color(0xFF1C1C1E), Color(0xFFE5E5E7)),
    ReaderTheme("Bold", Color.White, Color.Black, Color.Black, Color.White, bold = true),
    ReaderTheme("Calm", Color(0xFFF3E6CC), Color(0xFF3B2F1E), Color(0xFF3F392C), Color(0xFFE9DEC6)),
    ReaderTheme("Focus", Color(0xFFFDFDF8), Color(0xFF222222), Color(0xFF17170F), Color(0xFFE8E8E0)),
)

val HighlightColors = listOf(
    Color(0xFFFFD60A), Color(0xFF32D74B), Color(0xFF64B5F6), Color(0xFFFF7AB6), Color(0xFFBF5AF2),
)

val SelectionColor = Color(0xFFA8C0F5)

private val AppTypography = Typography().let { t ->
    // Medium (500) by default: DM Sans Regular reads thin and faint on phone screens.
    fun TextStyle.sans() = copy(fontFamily = AppSans, fontWeight = fontWeight?.takeIf { it > FontWeight.Medium } ?: FontWeight.Medium)
    t.copy(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
        titleLarge = t.titleLarge.sans(), titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
        bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans(), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
    )
}

/** [appearance]: 0 follows the system, 1 light, 2 dark. */
@Composable
fun GBookTheme(accent: String, appearance: Int, amoled: Boolean, content: @Composable () -> Unit) {
    val dark = when (appearance) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val g = remember(accent, dark, amoled) { gColors(accentPalette(accent), dark, amoled) }
    val scheme = if (dark) darkColorScheme(primary = g.accent, onPrimary = g.onAccent, surface = g.surface, background = g.bg)
    else lightColorScheme(primary = g.accent, onPrimary = g.onAccent, surface = g.surface, background = g.bg)
    // Status/navigation bar icons follow the app's mode, which can differ from the system's.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalG provides g) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
