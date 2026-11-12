package com.arva.tuya

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** How Shining talks to the light. */
enum class LightKind {
    /** Tuya LAN protocol (stock firmware): needs id, local key and protocol version. */
    TUYA,

    /** OpenBeken HTTP (after flashing open firmware): needs only an IP. */
    OPENBEKEN;

    companion object {
        fun parse(text: String?): LightKind = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: TUYA
    }
}

/** Everything needed to talk to the light locally. */
data class DeviceConfig(
    val name: String,
    val id: String,
    val ip: String,
    val localKey: String,
    val version: TuyaVersion,
    val kind: LightKind = LightKind.TUYA,
) {
    val isComplete: Boolean
        get() = when (kind) {
            LightKind.TUYA -> id.isNotBlank() && ip.isNotBlank() && localKey.toByteArray().size == 16
            LightKind.OPENBEKEN -> ip.isNotBlank()
        }

    /** shining://setup?… link, scanned as a QR code to configure the phone. */
    fun toSetupLink(): String = buildString {
        append("$SCHEME://setup?")
        val fields = buildList {
            add("name" to name)
            add("ip" to ip)
            if (kind == LightKind.TUYA) {
                add("id" to id)
                add("key" to localKey)
                add("version" to version.text)
            } else {
                add("kind" to kind.name.lowercase())
            }
        }
        append(fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" })
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
            val kind = LightKind.parse(q["kind"])
            val ip = q["ip"] ?: return null
            return when (kind) {
                LightKind.OPENBEKEN -> DeviceConfig(
                    name = q["name"] ?: "Lightstrip", id = "", ip = ip, localKey = "",
                    version = TuyaVersion.V33, kind = LightKind.OPENBEKEN,
                )
                LightKind.TUYA -> DeviceConfig(
                    name = q["name"] ?: "Lightstrip",
                    id = q["id"] ?: return null,
                    ip = ip,
                    localKey = q["key"] ?: return null,
                    version = TuyaVersion.parse(q["version"]) ?: TuyaVersion.V33,
                    kind = LightKind.TUYA,
                )
            }
        }
    }
}
