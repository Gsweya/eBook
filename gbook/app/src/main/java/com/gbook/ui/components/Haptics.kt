package com.gbook.ui.components

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Soft, iOS-like haptics built from vibration primitives (low ticks at reduced amplitude) instead of the
 * heavier stock long-press/handle-move effects. Ticks are rate-limited so fast drags feel like a smooth
 * texture rather than a buzz. Falls back to view feedback on devices without primitive support.
 */
class Haptics(ctx: Context, private val view: View) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val primitives = Build.VERSION.SDK_INT >= 31 && vibrator?.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK,
    ) == true
    private var lastTick = 0L

    /** Selection grew or shrank by a word; rate-limited. */
    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 45) return
        lastTick = now
        if (primitives) play(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.55f)
        else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Light tap for buttons and steppers. */
    fun tap() {
        if (primitives) play(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
        else view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Selection started (long-press recognised). */
    fun select() {
        if (primitives) play(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f)
        else view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun play(primitive: Int, scale: Float) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < 31) return
        val effect = VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
        // USAGE_TOUCH honours the system "touch feedback" setting.
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        else v.vibrate(effect)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val ctx = LocalContext.current
    val view = LocalView.current
    return remember(ctx, view) { Haptics(ctx, view) }
}
