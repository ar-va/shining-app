package com.arva.shining.light

import android.net.Network
import com.arva.shining.LightCommand
import com.arva.tuya.LightState
import com.arva.tuya.TuyaException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Controls an OpenBeken light over HTTP (`/cm?cmnd=…`). Stateless — each call is one request —
 * so there's no persistent connection to keep open. [network] pins requests to one Wi-Fi network
 * (used by arrival automations).
 */
class OpenBekenLight(
    private val host: String,
    private val port: Int = 80,
    private val network: Network? = null,
    private val timeoutMs: Int = 5000,
) : LightDriver {

    override val isConnected: Boolean get() = true // HTTP is per-request; nothing to hold open

    override fun read(): DriverReading {
        val json = request("State")
        val state = OpenBekenProtocol.parseState(json)
            ?: throw TuyaException("This device didn't answer like an OpenBeken light")
        return DriverReading(state = state, schema = null, detail = mapOf("state" to json))
    }

    override fun apply(command: LightCommand, current: LightState?) {
        // One request per console command; OpenBeken applies them in order.
        for (cmnd in OpenBekenProtocol.commandsFor(command, current)) request(cmnd)
    }

    override fun close() {}

    companion object {
        /** Builds a driver from an address that may include a port, e.g. "192.168.1.50" or "10.0.2.2:8080". */
        fun fromAddress(address: String, network: Network? = null): OpenBekenLight {
            val trimmed = address.trim()
            val host = trimmed.substringBefore(':')
            val port = trimmed.substringAfter(':', "").toIntOrNull() ?: 80
            return OpenBekenLight(host, port, network)
        }
    }

    private fun request(cmnd: String): String {
        val url = URL("http://$host:$port/cm?cmnd=${URLEncoder.encode(cmnd, "UTF-8")}")
        val conn = (if (network != null) network.openConnection(url) else url.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw TuyaException("The light returned HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw TuyaException("Could not reach the light at $host (${e.message ?: "no answer"})", e)
        } finally {
            conn.disconnect()
        }
    }
}
