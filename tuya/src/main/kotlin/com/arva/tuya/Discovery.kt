package com.arva.tuya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A device seen in a Tuya UDP broadcast. */
data class DiscoveredDevice(
    val id: String,
    val ip: String,
    val version: String,
    val productKey: String?,
)

/**
 * Tuya devices broadcast their id/ip/version on UDP:
 *   6666 – plaintext (3.1), 6667 – AES-ECB (3.3/3.4), 7000 – AES-GCM 6699 frames (3.5).
 * The broadcast key is md5("yGAdlopoPVldABfn") — the same for every device.
 */
object Discovery {
    val PORTS = listOf(6666, 6667, 7000)
    private val UDP_KEY = TuyaCrypto.md5("yGAdlopoPVldABfn".toByteArray())

    fun parse(packet: ByteArray): DiscoveredDevice? = try {
        val prefix = if (packet.size >= 4) java.nio.ByteBuffer.wrap(packet, 0, 4).int else 0
        val json = when (prefix) {
            Frames.PREFIX_55AA -> {
                val payload = Frames.unpack(packet, null, useHmac = false).payload
                if (payload.firstOrNull() == '{'.code.toByte()) payload
                else TuyaCrypto.ecbDecrypt(UDP_KEY, payload)
            }
            Frames.PREFIX_6699 -> Frames.unpack(packet, UDP_KEY, useHmac = false).payload
            else -> null
        }
        json?.let { fromJson(it.toString(Charsets.UTF_8)) }
    } catch (_: Exception) {
        null
    }

    private fun fromJson(text: String): DiscoveredDevice? {
        val obj = Json.parseToJsonElement(text.trim()) as? JsonObject ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.content
        return DiscoveredDevice(
            id = str("gwId") ?: str("devId") ?: return null,
            ip = str("ip") ?: return null,
            version = str("version") ?: "3.3",
            productKey = str("productKey"),
        )
    }
}
