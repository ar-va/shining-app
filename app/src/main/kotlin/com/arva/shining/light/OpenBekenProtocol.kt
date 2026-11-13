package com.arva.shining.light

import com.arva.shining.LightCommand
import com.arva.shining.findPreset
import com.arva.tuya.Hsv
import com.arva.tuya.LightMode
import com.arva.tuya.LightState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * Translates between Shining's [LightCommand]/[LightState] and OpenBeken's Tasmota-style console
 * commands (run over HTTP at `/cm?cmnd=`). Pure and unit-testable; the HTTP lives in [OpenBekenLight].
 *
 * OpenBeken CT is in Home Assistant mireds (154 cool … 500 warm); Shining warmth is 0 warm … 1 cool.
 */
object OpenBekenProtocol {
    const val CT_COOL = 154
    const val CT_WARM = 500
    private const val CT_SPAN = CT_WARM - CT_COOL // 346

    /** The console commands to realise [command], given the light's [current] state. */
    fun commandsFor(command: LightCommand, current: LightState?): List<String> {
        val on = current?.on == true
        if (command.power == LightCommand.Power.OFF || (command.power == LightCommand.Power.TOGGLE && on)) {
            return listOf("led_enableAll 0")
        }

        val b = command.brightness ?: ((current?.brightness ?: 1f) * 100).roundToInt().coerceIn(1, 100)
        val preset = command.preset?.let { findPreset(it) }
        val hue = command.hue ?: preset?.hue?.roundToInt()
        val warmth = command.warmth ?: preset?.temperature?.let { (it * 100).roundToInt() }

        // Keep off: set the stored colour/brightness but don't enable; otherwise turn on first.
        val out = if (command.keepOff) mutableListOf() else mutableListOf("led_enableAll 1")
        when {
            hue != null -> {
                val sat = command.saturation ?: preset?.saturation?.let { (it * 100).roundToInt() } ?: 100
                // HSBColor sets hue, saturation and brightness together and switches to RGB mode.
                out += "HSBColor ${hue.coerceIn(0, 360)},${sat.coerceIn(0, 100)},$b"
            }
            warmth != null -> {
                out += "CT ${warmthToCt(warmth / 100f)}"
                out += "Dimmer $b"
            }
            command.brightness != null -> out += "Dimmer $b" // brightness only: keep current mode
            // plain "on": nothing more to send
        }
        // Ensure it ends up off after storing the look.
        if (command.keepOff) out += "led_enableAll 0"
        return out
    }

    fun warmthToCt(warmth01: Float): Int = (CT_WARM - warmth01.coerceIn(0f, 1f) * CT_SPAN).roundToInt()

    fun ctToWarmth(ct: Int): Float = ((CT_WARM - ct).toFloat() / CT_SPAN).coerceIn(0f, 1f)

    /** Parses OpenBeken's `State` JSON into a [LightState], or null if it isn't recognisable. */
    fun parseState(json: String): LightState? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        // Real OpenBeken nests live state under "StatusSTS" (Status 11); the flat form (mock) has it at top level.
        val obj = (root["StatusSTS"] as? JsonObject) ?: root
        val power = (obj["POWER"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["POWER1"] as? JsonPrimitive)?.contentOrNull
        val on = power?.equals("ON", ignoreCase = true) ?: return null

        val dimmer = (obj["Dimmer"] as? JsonPrimitive)?.intOrNull
        val hsb = (obj["HSBColor"] as? JsonPrimitive)?.contentOrNull?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        val ct = (obj["CT"] as? JsonPrimitive)?.intOrNull

        val hue = hsb?.getOrNull(0) ?: 0f
        val sat = (hsb?.getOrNull(1) ?: 0f) / 100f
        val brightness = ((dimmer ?: hsb?.getOrNull(2)?.roundToInt() ?: 100) / 100f).coerceIn(0f, 1f)

        // White when there's no colour saturation; otherwise colour. Like Tuya, leave colour null
        // in white mode so the UI keeps its last hue/saturation instead of snapping to grey.
        val white = sat <= 0f
        return LightState(
            on = on,
            mode = if (white) LightMode.WHITE else LightMode.COLOUR,
            brightness = brightness,
            temperature = ct?.let { ctToWarmth(it) } ?: if (white) 0.5f else null,
            colour = if (white) null else Hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), brightness),
        )
    }

    /** Pulls a value out of OpenBeken's per-command JSON ack, which echoes e.g. {"Dimmer":50}. Unused but handy. */
    @Suppress("unused")
    fun ackValue(json: String, key: String): Double? =
        runCatching { (Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive)?.doubleOrNull }.getOrNull()
}
