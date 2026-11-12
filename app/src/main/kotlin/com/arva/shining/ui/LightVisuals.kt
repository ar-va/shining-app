package com.arva.shining.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.arva.shining.UiState
import kotlin.math.roundToInt

/** The colour the light is currently showing, for previews and the hero. */
fun lightColourOf(s: UiState): Color =
    if (s.colourMode) Color.hsv(s.hue, s.saturation, 1f) else whiteColor(s.temperature)

/** Readable text/icon colour to place on top of [fill]. */
fun onColourOf(fill: Color): Color =
    if (fill.luminance() > 0.55f) Color(0xE6000000) else Color(0xF2FFFFFF)

/** Plain-language name of a hue, for the state line. */
fun colourName(hue: Float): String = when (((hue % 360) + 360) % 360) {
    in 0f..15f, in 345f..360f -> "Red"
    in 15f..45f -> "Orange"
    in 45f..70f -> "Yellow"
    in 70f..160f -> "Green"
    in 160f..200f -> "Cyan"
    in 200f..255f -> "Blue"
    in 255f..290f -> "Purple"
    else -> "Pink"
}

/** Warm / Neutral / Cool from a 0 (warm) – 1 (cool) temperature. */
fun warmthName(t: Float): String = when {
    t < 0.34f -> "Warm"
    t < 0.67f -> "Neutral"
    else -> "Cool"
}

/** "On · 60% · Warm" style descriptor for the current state. */
fun stateDescriptor(s: UiState): String {
    val pct = (s.brightness * 100).roundToInt()
    val mode = if (s.colourMode) colourName(s.hue) else warmthName(s.temperature)
    return "On · $pct% · $mode"
}
