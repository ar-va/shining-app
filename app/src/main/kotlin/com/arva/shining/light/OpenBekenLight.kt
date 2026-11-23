package com.arva.shining.light

import android.net.Network
import com.arva.shining.LightCommand
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
    private val gate: PasswordGate,
    private val timeoutMs: Int = 5000,
) {

    /** Set by the owner before each use. See [PasswordTrust]. */
    @Volatile var trust: PasswordTrust = PasswordTrust.KNOWN_NETWORK

    // The connection where the light has already passed the login check, to skip the extra round trip.
    private var verifiedOn: Network? = null

    // Pre-computed HTTP Basic header, sent only when a web password is set (OpenBeken guards /cm).
    private val authHeader: String? =
        if (password.isNotEmpty())
            "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        else null

    /** Reads the state. Blocking. [lastMode] is used when the light's channels can't show its mode. */
    fun read(lastMode: LightMode = LightMode.UNKNOWN): LightState =
        // Real OpenBeken reports live state via "Status 11" (nested under StatusSTS). "State" is empty.
        OpenBekenProtocol.parseState(request("Status 11"), lastMode)
            ?: throw LightException("This device didn't answer like an OpenBeken light")

    /** Applies [command] and returns the mode it put the light in, or null if it didn't set one. */
    fun apply(command: LightCommand, current: LightState?): LightMode? {
        val commands = OpenBekenProtocol.commandsFor(command, current)
        // One request per console command; OpenBeken applies them in order.
        commands.forEach { request(it) }
        return OpenBekenProtocol.modeSetBy(commands)
    }

    companion object {
        /** Builds a driver from an address that may include a port, e.g. "192.168.1.50" or "10.0.2.2:8080". */
        fun fromAddress(
            address: String,
            gate: PasswordGate,
            network: Network? = null,
            username: String = "admin",
            password: String = "",
            trust: PasswordTrust = PasswordTrust.KNOWN_NETWORK,
        ): OpenBekenLight {
            val trimmed = address.trim()
            val host = trimmed.substringBefore(':')
            val port = trimmed.substringAfter(':', "").toIntOrNull() ?: 80
            return OpenBekenLight(host, port, network, username, password, gate).also { it.trust = trust }
        }
    }

    /**
     * HTTP Basic is unencrypted, so the password goes out only when this is the home Wi-Fi and the
     * device has asked for an OpenBeken login. Anything else at this address never receives it.
     */
    private fun request(cmnd: String): String {
        if (authHeader == null) return bodyOf(send(cmnd, network, withPassword = false))
        val route = gate.route(network, trust)
        if (route.maySendPassword && route.network != null && route.network == verifiedOn) {
            val reply = send(cmnd, route.network, withPassword = true)
            if (reply.code == 401) verifiedOn = null
            return bodyOf(reply)
        }
        val first = send(cmnd, route.network, withPassword = false)
        if (first.code != 401) return bodyOf(first)
        if (!OpenBekenProtocol.isLoginChallenge(first.challenge)) {
            throw LightException("The device at $host isn't asking for an OpenBeken login, so Shining kept the password.")
        }
        if (!route.maySendPassword) {
            throw LightException("Shining doesn't recognise this Wi-Fi, so it kept the password. If you're home, press Test in Settings.")
        }
        val body = bodyOf(send(cmnd, route.network, withPassword = true))
        verifiedOn = route.network
        gate.accepted(route.network, trust)
        return body
    }

    private fun bodyOf(reply: Reply): String = when {
        reply.code == 401 && authHeader == null -> throw LightException("The light needs its web password. Add it in Settings.")
        reply.code == 401 -> throw LightException("The light rejected the web password. Check it in Settings.")
        reply.code !in 200..299 -> throw LightException("The light returned HTTP ${reply.code}")
        else -> reply.body
    }

    private class Reply(val code: Int, val body: String, val challenge: String?)

    private fun send(cmnd: String, via: Network?, withPassword: Boolean): Reply {
        // Encode spaces as %20, not "+": OpenBeken's command parser treats "+" literally.
        val encoded = URLEncoder.encode(cmnd, "UTF-8").replace("+", "%20")
        val url = URL("http://$host:$port/cm?cmnd=$encoded")
        val conn = (if (via != null) via.openConnection(url) else url.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            if (withPassword) authHeader?.let { conn.setRequestProperty("Authorization", it) }
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
            Reply(code, body, conn.getHeaderField("WWW-Authenticate"))
        } catch (e: IOException) {
            throw LightException("Could not reach the light at $host (${e.message ?: "no answer"})", e)
        } finally {
            conn.disconnect()
        }
    }
}
