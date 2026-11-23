package com.arva.shining.light

import com.arva.shining.LightCommand
import com.arva.shining.findPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

    private val LOGIN_CHALLENGE = Regex("""^\s*basic\b.*\brealm\s*=\s*"OpenBeken HTTP Server"""", RegexOption.IGNORE_CASE)

    /** True when a `WWW-Authenticate` header is OpenBeken's own login request. */
    fun isLoginChallenge(header: String?): Boolean = header != null && LOGIN_CHALLENGE.containsMatchIn(header)

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
            // Brightness only, keeping the mode. Colour brightness lives in HSBColor, not the master Dimmer.
            command.brightness != null -> out += current?.colour?.takeIf { current.mode == LightMode.COLOUR }
                ?.let { "HSBColor ${it.h.roundToInt()},${(it.s * 100).roundToInt()},$b" } ?: "Dimmer $b"
            // plain "on": nothing more to send
        }
        // Ensure it ends up off after storing the look.
        if (command.keepOff) out += "led_enableAll 0"
        return out
    }

    /** The mode these console commands leave the light in, or null if they don't choose one. */
    fun modeSetBy(commands: List<String>): LightMode? =
        commands.lastOrNull { it.startsWith("HSBColor ") || it.startsWith("CT ") }
            ?.let { if (it.startsWith("CT ")) LightMode.WHITE else LightMode.COLOUR }

    fun warmthToCt(warmth01: Float): Int = (CT_WARM - warmth01.coerceIn(0f, 1f) * CT_SPAN).roundToInt()

    fun ctToWarmth(ct: Int): Float = ((CT_WARM - ct).toFloat() / CT_SPAN).coerceIn(0f, 1f)

    /**
     * Parses OpenBeken's status JSON into a [LightState], or null if it isn't recognisable.
     * [lastMode] is the answer when the output channels can't show the mode (see below).
     */
    fun parseState(json: String, lastMode: LightMode = LightMode.UNKNOWN): LightState? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        // Real OpenBeken nests live state under "StatusSTS" (Status 11); the flat form (mock) has it at top level.
        val obj = (root["StatusSTS"] as? JsonObject) ?: root
        val power = (obj["POWER"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["POWER1"] as? JsonPrimitive)?.contentOrNull
        val on = power?.equals("ON", ignoreCase = true) ?: return null

        val dimmer = (obj["Dimmer"] as? JsonPrimitive)?.intOrNull
        val hsb = (obj["HSBColor"] as? JsonPrimitive)?.contentOrNull?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        val ct = (obj["CT"] as? JsonPrimitive)?.intOrNull
        // Raw output channels R,G,B[,C,W]: the ground truth for which channels are lit.
        val colorCh = (obj["Color"] as? JsonPrimitive)?.contentOrNull?.split(",")?.mapNotNull { it.trim().toIntOrNull() }

        val hue = hsb?.getOrNull(0) ?: 0f
        val sat = (hsb?.getOrNull(1) ?: 0f) / 100f

        // Colour vs white: OpenBeken keeps HSBColor's saturation even in white/CT mode, so the raw
        // `Color` channels decide. They are all 0 when off, and at about 8% or less because the
        // firmware applies gamma 2.2 and rounds down, so then only the last known mode can answer.
        val mode = when {
            colorCh.isNullOrEmpty() -> if (sat <= 0f) LightMode.WHITE else LightMode.COLOUR
            colorCh.all { it == 0 } -> lastMode
            colorCh.take(3).all { it == 0 } -> LightMode.WHITE
            else -> LightMode.COLOUR
        }
        val white = mode == LightMode.WHITE
        // Brightness source depends on mode: in colour mode Shining sets it via HSBColor's value, and
        // the master `Dimmer` can stay at 100 — reading Dimmer there made the UI snap back to full.
        // In white/CT mode brightness is the `Dimmer` Shining sends alongside `CT`.
        val hsbBri = hsb?.getOrNull(2)?.roundToInt()
        val brightnessPct = (if (white) (dimmer ?: hsbBri) else (hsbBri ?: dimmer)) ?: 100
        val brightness = (brightnessPct / 100f).coerceIn(0f, 1f)
        return LightState(
            on = on,
            mode = mode,
            brightness = brightness,
            temperature = ct?.let { ctToWarmth(it) } ?: if (white) 0.5f else null,
            colour = if (mode == LightMode.COLOUR) Hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), brightness) else null,
        )
    }
}
