package com.arva.shining

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * One light action, shared by automations, launcher shortcuts, `shining://light?…` links and the
 * broadcast intent for automation apps. Written as a query string, e.g. `power=off`,
 * `preset=Warm&brightness=40`, `hue=230&saturation=80`, `warmth=100`, `power=toggle`.
 *
 * Percentages are 0–100. `warmth` 0 is warmest, 100 coolest. `hue` is 0–360.
 */
data class LightCommand(
    val power: Power? = null,
    val preset: String? = null,
    val brightness: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val warmth: Int? = null,
    /** Set the colour/brightness but leave the light off, so it's ready for the next manual on. */
    val keepOff: Boolean = false,
) {
    enum class Power { ON, OFF, TOGGLE }

    val isEmpty: Boolean get() = this == LightCommand()

    /** True when the current light state is needed to build the light's commands. */
    val needsState: Boolean get() = power == Power.TOGGLE || (power != Power.OFF && brightness == null) ||
        (brightness != null && preset == null && hue == null && warmth == null)

    fun toQuery(): String = listOfNotNull(
        power?.let { "power=${it.name.lowercase()}" },
        preset?.let { "preset=${URLEncoder.encode(it, "UTF-8")}" },
        brightness?.let { "brightness=$it" },
        hue?.let { "hue=$it" },
        saturation?.let { "saturation=$it" },
        warmth?.let { "warmth=$it" },
        if (keepOff) "keepoff=1" else null,
    ).joinToString("&")

    /** Short human description, e.g. "Turn off", "Warm at 40%". */
    fun describe(): String {
        val pct = brightness?.let { " at $it%" } ?: ""
        val tail = if (keepOff) ", stays off" else ""
        return when {
            power == Power.OFF -> "Turn off"
            power == Power.TOGGLE -> "Switch on or off"
            preset != null -> "${findPreset(preset)?.label ?: preset}$pct$tail"
            hue != null -> "Colour ${hue}°$pct$tail"
            warmth != null -> "White, ${if (warmth < 50) "warm" else "cool"}$pct$tail"
            brightness != null -> "Brightness $brightness%$tail"
            else -> "Turn on"
        }
    }

    companion object {
        /** Parses a query string (with or without a leading "?"). Unknown keys are ignored. */
        fun parse(query: String?): LightCommand {
            val q = query.orEmpty().removePrefix("?").split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null
                else runCatching { it.substring(0, i).lowercase() to URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrNull()
            }.toMap()
            return fromMap(q)
        }

        fun fromMap(values: Map<String, String?>): LightCommand {
            fun int(key: String, range: IntRange) = values[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(range)
            return LightCommand(
                power = when (values["power"]?.trim()?.lowercase()) {
                    "on", "true", "1" -> Power.ON
                    "off", "false", "0" -> Power.OFF
                    "toggle" -> Power.TOGGLE
                    else -> null
                },
                preset = values["preset"]?.trim()?.takeIf { it.isNotEmpty() },
                brightness = int("brightness", 1..100),
                hue = int("hue", 0..360),
                saturation = int("saturation", 0..100),
                warmth = int("warmth", 0..100),
                keepOff = values["keepoff"]?.trim()?.lowercase() in setOf("1", "true", "yes"),
            )
        }
    }
}
