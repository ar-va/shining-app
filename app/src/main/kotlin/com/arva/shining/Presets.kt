package com.arva.shining

/** A one-tap preset: either a colour (hue/sat) or a white temperature. */
data class Preset(val label: String, val hue: Float? = null, val saturation: Float = 1f, val temperature: Float? = null)

val PRESETS = listOf(
    Preset("Warm", temperature = 0f),
    Preset("Daylight", temperature = 1f),
    Preset("Red", hue = 0f),
    Preset("Orange", hue = 25f),
    Preset("Yellow", hue = 50f),
    Preset("Green", hue = 120f),
    Preset("Cyan", hue = 185f),
    Preset("Blue", hue = 230f),
    Preset("Purple", hue = 275f),
    Preset("Pink", hue = 320f, saturation = 0.7f),
)

fun findPreset(label: String): Preset? = PRESETS.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
