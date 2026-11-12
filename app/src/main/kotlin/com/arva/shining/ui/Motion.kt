package com.arva.shining.ui

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
