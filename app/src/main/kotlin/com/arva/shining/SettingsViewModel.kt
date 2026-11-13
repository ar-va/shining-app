package com.arva.shining

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.shining.light.OpenBekenLight
import com.arva.tuya.DeviceConfig
import com.arva.tuya.DiscoveredDevice
import com.arva.tuya.Discovery
import com.arva.tuya.LightDps
import com.arva.tuya.LightKind
import com.arva.tuya.TuyaDevice
import com.arva.tuya.TuyaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class SettingsState(
    val kind: LightKind = LightKind.TUYA,
    val name: String = "Lightstrip",
    val ip: String = "",
    val id: String = "",
    val key: String = "",
    val version: TuyaVersion = TuyaVersion.V33,
    val obUser: String = "admin",
    val obPassword: String = "",
    val scanning: Boolean = false,
    val found: List<DiscoveredDevice> = emptyList(),
    val scanMessage: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
) {
    val isOpenBeken: Boolean get() = kind == LightKind.OPENBEKEN

    fun toConfig() = if (isOpenBeken) {
        DeviceConfig(
            name.trim().ifEmpty { "Lightstrip" }, id = "", ip = ip.trim(), localKey = "", version = TuyaVersion.V33,
            kind = LightKind.OPENBEKEN, username = obUser.trim().ifEmpty { "admin" }, password = obPassword,
        )
    } else {
        DeviceConfig(name.trim().ifEmpty { "Lightstrip" }, id.trim(), ip.trim(), key, version, kind = LightKind.TUYA)
    }
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        app.lightApp.store.load()?.let {
            SettingsState(kind = it.kind, name = it.name, ip = it.ip, id = it.id, key = it.localKey, version = it.version, obUser = it.username, obPassword = it.password)
        } ?: SettingsState()
    )
    val state: StateFlow<SettingsState> = _state

    fun edit(transform: (SettingsState) -> SettingsState) = _state.update { transform(it).copy(testResult = null) }

    fun setKind(kind: LightKind) = edit { it.copy(kind = kind) }

    fun pasteLink(text: String?): Boolean {
        val cfg = text?.let { DeviceConfig.fromSetupLink(it) } ?: return false
        _state.update { it.copy(kind = cfg.kind, name = cfg.name, ip = cfg.ip, id = cfg.id, key = cfg.localKey, version = cfg.version) }
        return true
    }

    fun pick(d: DiscoveredDevice) = edit {
        it.copy(ip = d.ip, id = d.id, version = TuyaVersion.parse(d.version) ?: it.version)
    }

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, found = emptyList(), scanMessage = null) }
        viewModelScope.launch {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            runCatching {
                LanDiscovery.scan(getApplication()).collect { d ->
                    found[d.id] = d
                    _state.update { it.copy(found = found.values.toList()) }
                }
            }.onFailure { e -> _state.update { it.copy(scanMessage = "Scan failed: ${e.message}") } }
            _state.update {
                it.copy(
                    scanning = false,
                    scanMessage = it.scanMessage ?: if (found.isEmpty()) "No lights heard. Check that the phone is on the same Wi-Fi and the light has power." else null,
                )
            }
        }
    }

    fun test() {
        val s = _state.value
        val cfg = s.toConfig()
        if (!cfg.isComplete) {
            _state.update {
                it.copy(testResult = if (it.isOpenBeken) "Fill in the light's IP address" else "Fill in the IP, device ID and a 16-character local key")
            }
            return
        }
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    if (s.isOpenBeken) {
                        val state = OpenBekenLight.fromAddress(cfg.ip, username = cfg.username, password = cfg.password).read().state
                        "Connected to OpenBeken.\n" + (state?.let { "Power ${if (it.on) "on" else "off"}, brightness ${(it.brightness * 100).toInt()}%" } ?: "")
                    } else {
                        val dps = TuyaDevice(cfg.id, cfg.ip, cfg.localKey, cfg.version).use { it.status() }
                        val schema = LightDps.detect(dps)
                        (if (schema == null) "Connected, but the device reports no light data points." else "Connected. ${schema.name} light layout.") +
                            "\n" + dps.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.joinToString("\n") { "DP ${it.key} = ${it.value}" }
                    }
                }.getOrElse { "Failed: ${it.message}" }
            }
            _state.update { it.copy(testing = false, testResult = text) }
        }
    }

    /** Saves and returns the config, or null if incomplete. */
    fun save(): DeviceConfig? = _state.value.toConfig().takeIf { it.isComplete }
}

/** Listens for Tuya UDP broadcasts on the local Wi-Fi. */
object LanDiscovery {
    /** Finds the current IP of the device with [deviceId] by listening briefly, or null. */
    suspend fun findIp(context: Context, deviceId: String, durationMs: Long = 6_000): String? {
        var ip: String? = null
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(durationMs + 1_000) {
                scan(context, durationMs).collect { if (it.id == deviceId) { ip = it.ip; throw kotlinx.coroutines.CancellationException("found") } }
            }
        }
        return ip
    }

    fun scan(context: Context, durationMs: Long = 8_000): Flow<DiscoveredDevice> = channelFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("lightstrip-discovery").apply { setReferenceCounted(false); acquire() }
        val sockets = Discovery.PORTS.mapNotNull { port ->
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 500
                    bind(InetSocketAddress(port))
                }
            }.getOrNull()
        }
        try {
            if (sockets.isEmpty()) throw IllegalStateException("could not open UDP ports")
            val deadline = System.currentTimeMillis() + durationMs
            sockets.map { socket ->
                launch {
                    val buf = ByteArray(4096)
                    while (isActive && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            Discovery.parse(packet.data.copyOf(packet.length))?.let { send(it) }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            }.forEach { it.join() }
        } finally {
            sockets.forEach { it.close() }
            lock.release()
        }
    }.flowOn(Dispatchers.IO)
}
