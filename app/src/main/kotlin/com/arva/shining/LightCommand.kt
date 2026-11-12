package com.arva.shining

import com.arva.tuya.Hsv
import com.arva.tuya.LightDps
import com.arva.tuya.LightMode
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
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
) {
    enum class Power { ON, OFF, TOGGLE }

    val isEmpty: Boolean get() = this == LightCommand()

    /** True when the current light state is needed to build the DPs. */
    val needsState: Boolean get() = power == Power.TOGGLE || (power != Power.OFF && brightness == null) ||
        (brightness != null && preset == null && hue == null && warmth == null)

    fun toQuery(): String = listOfNotNull(
        power?.let { "power=${it.name.lowercase()}" },
        preset?.let { "preset=${URLEncoder.encode(it, "UTF-8")}" },
        brightness?.let { "brightness=$it" },
        hue?.let { "hue=$it" },
        saturation?.let { "saturation=$it" },
        warmth?.let { "warmth=$it" },
    ).joinToString("&")

    /** Short human description, e.g. "Turn off", "Warm at 40%". */
    fun describe(): String {
        val pct = brightness?.let { " at $it%" } ?: ""
        return when {
            power == Power.OFF -> "Turn off"
            power == Power.TOGGLE -> "Switch on or off"
            preset != null -> "${findPreset(preset)?.label ?: preset}$pct"
            hue != null -> "Colour ${hue}°$pct"
            warmth != null -> "White, ${if (warmth < 50) "warm" else "cool"}$pct"
            brightness != null -> "Brightness $brightness%"
            else -> "Turn on"
        }
    }

    /**
     * Data points for this command. [state] is the light's current state (needed when [needsState]);
     * without it, missing values fall back to sensible defaults.
     */
    fun toDps(schema: LightSchema, state: LightState?): Map<String, Any> {
        val on = state?.on == true
        if (power == Power.OFF || (power == Power.TOGGLE && on)) return LightDps.power(schema, false)
        if (power == Power.TOGGLE) return LightDps.power(schema, true)

        val b = brightness?.let { (it / 100f).coerceIn(0.01f, 1f) } ?: state?.brightness ?: 1f
        val p = preset?.let { findPreset(it) }
        val targetHue = hue?.toFloat() ?: p?.hue
        val targetWarmth = warmth?.let { it / 100f } ?: p?.temperature
        return when {
            targetHue != null -> {
                val sat = saturation?.let { it / 100f } ?: p?.saturation ?: 1f
                LightDps.colour(schema, Hsv(targetHue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), b))
            }
            targetWarmth != null -> LightDps.white(schema, b, targetWarmth.coerceIn(0f, 1f))
            brightness != null -> {
                // Brightness only: keep the current mode and colour.
                val colour = state?.colour
                if (state?.mode == LightMode.COLOUR && colour != null) LightDps.colour(schema, colour.copy(v = b))
                else LightDps.white(schema, b, state?.temperature)
            }
            else -> LightDps.power(schema, true)
        }
    }

    companion object {
        /** Parses a query string (with or without a leading "?"). Unknown keys are ignored. */
        fun parse(query: String?): LightCommand {
            val q = query.orEmpty().removePrefix("?").split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).lowercase() to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            return fromMap(q)
        }

        fun fromMap(values: Map<String, String?>): LightCommand {
            fun int(key: String, range: IntRange) = values[key]?.trim()?.toFloatOrNull()?.roundToInt()?.coerceIn(range)
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
            )
        }
    }
}
