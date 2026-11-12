package com.arva.shining.light

import android.net.Network
import com.arva.shining.LightCommand
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightDps
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
import com.arva.tuya.TuyaDevice
import com.arva.tuya.TuyaException
import javax.net.SocketFactory

/**
 * Talks to a stock Tuya light over the LAN. Wraps [TuyaDevice], detects and caches the DP schema,
 * and turns a [LightCommand] into the right data points.
 */
class TuyaLightDriver(
    cfg: DeviceConfig,
    network: Network?,
    private var schema: LightSchema?,
    private val onSchema: (LightSchema) -> Unit,
) : LightDriver {
    private val device = TuyaDevice(
        cfg.id, cfg.ip, cfg.localKey, cfg.version,
        socketFactory = network?.socketFactory ?: SocketFactory.getDefault(),
    )

    override val isConnected: Boolean get() = device.isConnected

    override fun read(): DriverReading {
        val dps = device.status()
        LightDps.detect(dps)?.let { if (it != schema) { schema = it; onSchema(it) } }
        val s = schema?.let { LightDps.parse(it, dps) }
        return DriverReading(state = s, schema = schema, detail = dps)
    }

    override fun apply(command: LightCommand, current: LightState?) {
        val sch = schema ?: read().schema
            ?: throw TuyaException("This device doesn't look like a Tuya light (no DP 1 or 20)")
        val dps = command.toDps(sch, current)
        if (dps.isNotEmpty()) device.setDps(dps)
    }

    override fun close() = device.close()
}
