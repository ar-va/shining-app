package com.arva.shining.light

import java.io.IOException

class LightException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What Shining needs to reach the light. An empty password means it has no web login. */
data class DeviceConfig(
    val name: String,
    val ip: String,
    val username: String = "admin",
    val password: String = "",
) {
    val isComplete: Boolean get() = ip.isNotBlank()
}

enum class LightMode { WHITE, COLOUR, UNKNOWN }

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
