package com.arva.tuya

import kotlin.math.roundToInt

/**
 * Tuya RGB(W) lights use one of two standard data-point layouts:
 *
 * V2 ("20-series", most devices since ~2019):
 *   20 switch_led bool · 21 work_mode white|colour|scene|music · 22 bright 10–1000
 *   23 temp 0–1000 · 24 colour "HHHHSSSSVVVV" (h 0–360, s/v 0–1000)
 *
 * V1 (older "1-series"):
 *   1 switch bool · 2 mode white|colour|scene… · 3 bright 25–255 · 4 temp 0–255
 *   5 colour "RRGGBB0HHHSSVV" (rgb + h 0–360, s/v 0–255)
 */
enum class LightSchema { V1, V2 }

enum class LightMode { WHITE, COLOUR, SCENE, MUSIC, UNKNOWN }

/** h 0–360, s 0–1, v 0–1 */
data class Hsv(val h: Float, val s: Float, val v: Float)

data class LightState(
    val on: Boolean,
    val mode: LightMode,
    /** 0–1, brightness of the active mode (white brightness or colour value). */
    val brightness: Float,
    /** 0 = warmest, 1 = coolest; null if unsupported. */
    val temperature: Float?,
    val colour: Hsv?,
)

object LightDps {
    val V1_DPS = listOf("1", "2", "3", "4", "5")
    val V2_DPS = listOf("20", "21", "22", "23", "24")

    fun detect(dps: Map<String, Any?>): LightSchema? = when {
        "20" in dps -> LightSchema.V2
        "1" in dps -> LightSchema.V1
        else -> null
    }

    fun parse(schema: LightSchema, dps: Map<String, Any?>): LightState {
        val ids = ids(schema)
        val mode = when (val m = dps[ids.mode]?.toString()) {
            "white" -> LightMode.WHITE
            "colour" -> LightMode.COLOUR
            "music" -> LightMode.MUSIC
            null -> LightMode.UNKNOWN
            else -> if (m.startsWith("scene")) LightMode.SCENE else LightMode.UNKNOWN
        }
        val colour = (dps[ids.colour] as? String)?.let { parseColour(schema, it) }
        val white = (dps[ids.bright] as? Number)?.toFloat()?.let { fromRange(it, ids.brightMin, ids.max) }
        val brightness = if (mode == LightMode.COLOUR && colour != null) colour.v else white ?: colour?.v ?: 1f
        return LightState(
            on = dps[ids.switch] == true,
            mode = mode,
            brightness = brightness,
            temperature = (dps[ids.temp] as? Number)?.toFloat()?.let { fromRange(it, 0f, ids.max) },
            colour = colour,
        )
    }

    fun power(schema: LightSchema, on: Boolean): Map<String, Any> = mapOf(ids(schema).switch to on)

    fun white(schema: LightSchema, brightness: Float, temperature: Float?): Map<String, Any> {
        val ids = ids(schema)
        return buildMap {
            put(ids.switch, true)
            put(ids.mode, "white")
            put(ids.bright, toRange(brightness, ids.brightMin, ids.max))
            if (temperature != null) put(ids.temp, toRange(temperature, 0f, ids.max))
        }
    }

    fun colour(schema: LightSchema, hsv: Hsv): Map<String, Any> {
        val ids = ids(schema)
        return mapOf(ids.switch to true, ids.mode to "colour", ids.colour to formatColour(schema, hsv))
    }

    fun formatColour(schema: LightSchema, hsv: Hsv): String {
        val h = hsv.h.coerceIn(0f, 360f).roundToInt() % 361
        return when (schema) {
            LightSchema.V2 -> {
                val s = (hsv.s.coerceIn(0f, 1f) * 1000).roundToInt()
                val v = toRange(hsv.v, 10f, 1000f)
                "%04x%04x%04x".format(h, s, v)
            }
            LightSchema.V1 -> {
                val v = hsv.v.coerceIn(0.1f, 1f)
                val (r, g, b) = hsvToRgb(hsv.h, hsv.s, v)
                "%02x%02x%02x%04x%02x%02x".format(r, g, b, h, (hsv.s.coerceIn(0f, 1f) * 255).roundToInt(), (v * 255).roundToInt())
            }
        }
    }

    fun parseColour(schema: LightSchema, hex: String): Hsv? = try {
        when (schema) {
            LightSchema.V2 -> if (hex.length < 12) null else Hsv(
                hex.substring(0, 4).toInt(16).toFloat(),
                hex.substring(4, 8).toInt(16) / 1000f,
                hex.substring(8, 12).toInt(16) / 1000f,
            )
            LightSchema.V1 -> if (hex.length < 14) null else Hsv(
                hex.substring(6, 10).toInt(16).toFloat(),
                hex.substring(10, 12).toInt(16) / 255f,
                hex.substring(12, 14).toInt(16) / 255f,
            )
        }?.let { Hsv(it.h.coerceIn(0f, 360f), it.s.coerceIn(0f, 1f), it.v.coerceIn(0f, 1f)) }
    } catch (_: NumberFormatException) {
        null
    }

    /** Returns 0–255 RGB. */
    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val hp = (h.coerceIn(0f, 360f) % 360f) / 60f
        val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return Triple(((r1 + m) * 255).roundToInt(), ((g1 + m) * 255).roundToInt(), ((b1 + m) * 255).roundToInt())
    }

    private class Ids(
        val switch: String, val mode: String, val bright: String, val temp: String, val colour: String,
        val brightMin: Float, val max: Float,
    )

    private val v1 = Ids("1", "2", "3", "4", "5", 25f, 255f)
    private val v2 = Ids("20", "21", "22", "23", "24", 10f, 1000f)

    private fun ids(schema: LightSchema) = if (schema == LightSchema.V2) v2 else v1

    private fun toRange(f: Float, min: Float, max: Float): Int = (min + f.coerceIn(0f, 1f) * (max - min)).roundToInt()

    private fun fromRange(x: Float, min: Float, max: Float): Float = ((x - min) / (max - min)).coerceIn(0f, 1f)
}
