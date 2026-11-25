package com.gbook.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.gbook.R

/**
 * Colour of the status and navigation bar icons (clock, network, battery) for one window. The app's light or dark
 * mode sets the default; a screen whose top isn't in the app's colours, such as a dark reading page in light mode,
 * asks for its own while it shows. Kept on the window so it can be applied again after something else resets it:
 * the splash screen does when it leaves, from the XML theme, which knows nothing of the app's own appearance setting.
 */
private class BarIcons(private val window: Window) {
    var appDark = false
        set(v) { field = v; apply() }

    /** Screens asking for their own icons, latest last, each with whether its background is dark. */
    private val overrides = ArrayList<Pair<Any, Boolean>>()

    fun push(key: Any, darkBackground: Boolean) {
        overrides.removeAll { it.first === key }
        overrides += key to darkBackground
        apply()
    }

    fun pop(key: Any) {
        if (overrides.removeAll { it.first === key }) apply()
    }

    fun apply() {
        val dark = overrides.lastOrNull()?.second ?: appDark
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private fun Window.barIcons(): BarIcons =
    decorView.getTag(R.id.bar_icons) as? BarIcons ?: BarIcons(this).also { decorView.setTag(R.id.bar_icons, it) }

/** Applies this window's bar icon colours again, after something outside the app changed them. */
fun Window.reapplyBarIcons() = barIcons().apply()

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** The window this composable draws in: a dialog's or bottom sheet's own window, else the activity's. */
@Composable
private fun currentWindow(): Window? {
    val view = LocalView.current
    return (view.parent as? DialogWindowProvider)?.window ?: view.context.activity()?.window
}

/** Sets the default icon colours from the app's mode. */
@Composable
internal fun AppBarIcons(dark: Boolean) {
    val window = currentWindow() ?: return
    DisposableEffect(window, dark) {
        window.barIcons().appDark = dark
        onDispose { }
    }
}

/** While this is on screen, bar icons are drawn to read on [background] rather than following the app's mode. */
@Composable
fun BarIconsOver(background: Color) {
    val window = currentWindow() ?: return
    val key = remember { Any() }
    val dark = background.luminance() < 0.5f
    DisposableEffect(window, dark) {
        window.barIcons().push(key, dark)
        onDispose { window.barIcons().pop(key) }
    }
}
