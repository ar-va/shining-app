package com.arva.shining

import android.net.Network
import com.arva.shining.light.LightRoute
import com.arva.shining.light.NetworkFingerprint
import com.arva.shining.light.OpenBekenLight
import com.arva.shining.light.OpenBekenProtocol
import com.arva.shining.light.PasswordGate
import com.arva.shining.light.PasswordTrust
import com.arva.tuya.TuyaException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class OpenBekenPasswordTest {
    private val status = """{"StatusSTS":{"POWER":"ON","Dimmer":50,"HSBColor":"0,0,50","Color":"0,0,0,128,128","CT":300}}"""
    private val openBeken = "Basic realm=\"OpenBeken HTTP Server\""

    /** A one-request-per-connection HTTP device that records the Authorization header of each request. */
    private inner class Device(private val challenge: String?) {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val auth: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use {
                        val lines = it.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile(String::isNotEmpty).toList() }
                        val header = lines.firstOrNull { l -> l.startsWith("Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
                        auth += header
                        val reply = if (challenge != null && header == null) {
                            "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: $challenge\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            "HTTP/1.1 200 OK\r\nContent-Length: ${status.length}\r\nConnection: close\r\n\r\n$status"
                        }
                        it.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    }
                }
            }
        }
        fun light(gate: PasswordGate) = OpenBekenLight("127.0.0.1", server.localPort, username = "admin", password = "secret", gate = gate)
    }

    private class Gate(val allowed: Boolean) : PasswordGate {
        var accepted = 0
        override fun route(requested: Network?, trust: PasswordTrust) = LightRoute(null, allowed)
        override fun accepted(network: Network?, trust: PasswordTrust) { accepted++ }
    }

    private val devices = mutableListOf<Device>()
    private fun device(challenge: String?) = Device(challenge).also { devices += it }
    @After fun close() = devices.forEach { it.server.close() }

    @Test fun `unrecognised wifi never receives the password`() {
        val d = device(openBeken)
        val gate = Gate(allowed = false)
        val error = assertThrows(TuyaException::class.java) { d.light(gate).read() }
        assertTrue(error.message!!.contains("recognise"))
        assertEquals(listOf<String?>(null), d.auth.toList())
        assertEquals(0, gate.accepted)
    }

    @Test fun `a device that is not asking for an OpenBeken login never receives it`() {
        val d = device("Basic realm=\"Router\"")
        assertThrows(TuyaException::class.java) { d.light(Gate(allowed = true)).read() }
        assertEquals(listOf<String?>(null), d.auth.toList())
    }

    @Test fun `home wifi logs in after the challenge`() {
        val d = device(openBeken)
        val gate = Gate(allowed = true)
        assertNotNull(d.light(gate).read().state)
        assertNull(d.auth[0])
        assertTrue(d.auth[1]!!.startsWith("Basic "))
        assertEquals(1, gate.accepted)
    }

    @Test fun `a light without a login is not sent the password`() {
        val d = device(null)
        assertNotNull(d.light(Gate(allowed = true)).read().state)
        assertEquals(listOf<String?>(null), d.auth.toList())
    }

    @Test fun `login challenge must be OpenBeken's`() {
        assertTrue(OpenBekenProtocol.isLoginChallenge(openBeken))
        assertTrue(OpenBekenProtocol.isLoginChallenge("basic REALM=\"openbeken http server\""))
        assertFalse(OpenBekenProtocol.isLoginChallenge("Digest realm=\"OpenBeken HTTP Server\""))
        assertFalse(OpenBekenProtocol.isLoginChallenge("Basic realm=\"Router\""))
        assertFalse(OpenBekenProtocol.isLoginChallenge(null))
    }

    @Test fun `fingerprint uses the subnet, gateway and domain`() {
        assertEquals("192.168.1.0/24", NetworkFingerprint.subnet(byteArrayOf(192.toByte(), 168.toByte(), 1, 57), 24))
        assertEquals("10.20.0.0/14", NetworkFingerprint.subnet(byteArrayOf(10, 22, 9, 1), 14))
        val home = NetworkFingerprint.of("192.168.1.0/24", "192.168.1.1", "Lan")
        assertEquals(home, NetworkFingerprint.of("192.168.1.0/24", "192.168.1.1", " lan "))
        assertFalse(home == NetworkFingerprint.of("192.168.1.0/24", "192.168.1.254", "lan"))
        assertFalse(home == NetworkFingerprint.of("192.168.0.0/24", "192.168.0.1", "lan"))
        assertNull(NetworkFingerprint.of(null, "192.168.1.1", "lan"))
    }
}
