package com.arva.shining.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** False when the user has turned system animations off (Developer options / accessibility). */
@Composable
fun animationsOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) != 0f
    }
}

/** A tween duration that collapses to 0 (instant) when system animations are off. */
@Composable
fun motionMs(ms: Int): Int = if (animationsOn()) ms else 0

/** Light haptic tick that honours the system haptics setting. */
fun View.hapticTick() = performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

fun View.hapticSelect() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

/**
 * A stronger, more deliberate haptic for turning the light on or off — distinct from the light
 * [hapticSelect] used for brightness steps. Uses the platform toggle effects where available.
 */
fun View.hapticToggle(on: Boolean) {
    val constant = when {
        Build.VERSION.SDK_INT >= 34 ->
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
        else -> HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}
