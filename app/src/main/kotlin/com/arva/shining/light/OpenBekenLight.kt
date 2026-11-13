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
    private val username: String = "admin",
    private val password: String = "",
    private val timeoutMs: Int = 5000,
) : LightDriver {

    // Pre-computed HTTP Basic header, sent only when a web password is set (OpenBeken guards /cm).
    private val authHeader: String? =
        if (password.isNotEmpty())
            "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        else null

    override val isConnected: Boolean get() = true // HTTP is per-request; nothing to hold open

    override fun read(): DriverReading {
        // Real OpenBeken reports live state via "Status 11" (nested under StatusSTS). "State" is empty.
        val json = request("Status 11")
        val state = OpenBekenProtocol.parseState(json)
            ?: throw TuyaException("This device didn't answer like an OpenBeken light")
        return DriverReading(state = state, schema = null, detail = mapOf("state" to json))
    }

    override fun apply(command: LightCommand, current: LightState?) {
        // Enable smooth hardware fades once per light per process (not on every 20 s reconnect), so
        // colour/brightness changes ease instead of snapping. OpenBeken keeps this until it reboots.
        val key = "$host:$port"
        if (fadeConfigured.add(key)) {
            runCatching { request("Fade 1"); request("Speed 3") }
                .onFailure { fadeConfigured.remove(key); throw it } // retry next time if it failed
        }
        // One request per console command; OpenBeken applies them in order.
        for (cmnd in OpenBekenProtocol.commandsFor(command, current)) request(cmnd)
    }

    override fun close() {}

    companion object {
        // Lights (by host:port) that have had fades enabled this process, so we send it only once.
        private val fadeConfigured = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** Builds a driver from an address that may include a port, e.g. "192.168.1.50" or "10.0.2.2:8080". */
        fun fromAddress(address: String, network: Network? = null, username: String = "admin", password: String = ""): OpenBekenLight {
            val trimmed = address.trim()
            val host = trimmed.substringBefore(':')
            val port = trimmed.substringAfter(':', "").toIntOrNull() ?: 80
            return OpenBekenLight(host, port, network, username, password)
        }
    }

    private fun request(cmnd: String): String {
        // Encode spaces as %20, not "+": OpenBeken's command parser treats "+" literally.
        val encoded = URLEncoder.encode(cmnd, "UTF-8").replace("+", "%20")
        val url = URL("http://$host:$port/cm?cmnd=$encoded")
        val conn = (if (network != null) network.openConnection(url) else url.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            authHeader?.let { conn.setRequestProperty("Authorization", it) }
            val code = conn.responseCode
            if (code == 401) throw TuyaException("The light needs a web password — set it in the OpenBeken fields below")
            if (code !in 200..299) throw TuyaException("The light returned HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw TuyaException("Could not reach the light at $host (${e.message ?: "no answer"})", e)
        } finally {
            conn.disconnect()
        }
    }
}
