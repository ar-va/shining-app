package com.arva.shining.light

import com.arva.shining.LightCommand
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
import java.io.Closeable

/** One reading of the light: its parsed state, the Tuya schema (null for OpenBeken), and raw detail. */
data class DriverReading(
    val state: LightState?,
    val schema: LightSchema?,
    val detail: Map<String, Any?>,
)

/**
 * A way to talk to one light. [TuyaLightDriver] speaks the Tuya LAN protocol; [OpenBekenLight]
 * speaks OpenBeken HTTP. The controller owns one of these and serializes access to it.
 */
interface LightDriver : Closeable {
    val isConnected: Boolean

    /** Reads the current state. Blocking; call off the main thread. */
    fun read(): DriverReading

    /** Applies [command], using [current] (the state just read) when the command depends on it. */
    fun apply(command: LightCommand, current: LightState?)
}
