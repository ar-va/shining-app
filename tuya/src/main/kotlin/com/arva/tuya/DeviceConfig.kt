package com.arva.tuya

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Everything needed to talk to the light locally. */
data class DeviceConfig(
    val name: String,
    val id: String,
    val ip: String,
    val localKey: String,
    val version: TuyaVersion,
) {
    val isComplete: Boolean
        get() = id.isNotBlank() && ip.isNotBlank() && localKey.toByteArray().size == 16

    /** shining://setup?… link, scanned as a QR code to configure the phone. */
    fun toSetupLink(): String = buildString {
        append("$SCHEME://setup?")
        append(listOf("name" to name, "id" to id, "ip" to ip, "key" to localKey, "version" to version.text)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" })
    }

    companion object {
        const val SCHEME = "shining"

        fun fromSetupLink(link: String): DeviceConfig? {
            val uri = try { URI(link.trim()) } catch (_: Exception) { return null }
            if (uri.scheme != SCHEME) return null
            val q = (uri.rawQuery ?: return null).split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            return DeviceConfig(
                name = q["name"] ?: "Lightstrip",
                id = q["id"] ?: return null,
                ip = q["ip"] ?: return null,
                localKey = q["key"] ?: return null,
                version = TuyaVersion.parse(q["version"]) ?: TuyaVersion.V33,
            )
        }
    }
}
