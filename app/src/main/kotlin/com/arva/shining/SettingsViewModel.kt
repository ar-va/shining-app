package com.arva.shining

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.tuya.DeviceConfig
import com.arva.tuya.DiscoveredDevice
import com.arva.tuya.Discovery
import com.arva.tuya.LightDps
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
    val name: String = "Lightstrip",
    val ip: String = "",
    val id: String = "",
    val key: String = "",
    val version: TuyaVersion = TuyaVersion.V33,
    val scanning: Boolean = false,
    val found: List<DiscoveredDevice> = emptyList(),
    val scanMessage: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
) {
    fun toConfig() = DeviceConfig(name.trim().ifEmpty { "Lightstrip" }, id.trim(), ip.trim(), key, version)
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(
        app.lightApp.store.load()?.let {
            SettingsState(name = it.name, ip = it.ip, id = it.id, key = it.localKey, version = it.version)
        } ?: SettingsState()
    )
    val state: StateFlow<SettingsState> = _state

    fun edit(transform: (SettingsState) -> SettingsState) = _state.update { transform(it).copy(testResult = null) }

    fun pasteLink(text: String?): Boolean {
        val cfg = text?.let { DeviceConfig.fromSetupLink(it) } ?: return false
        _state.update { it.copy(name = cfg.name, ip = cfg.ip, id = cfg.id, key = cfg.localKey, version = cfg.version) }
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
        val cfg = _state.value.toConfig()
        if (!cfg.isComplete) {
            _state.update { it.copy(testResult = "Fill in the IP, device ID and a 16-character local key") }
            return
        }
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    TuyaDevice(cfg.id, cfg.ip, cfg.localKey, cfg.version).use { it.status() }
                }
            }
            val text = result.fold(
                onSuccess = { dps ->
                    val schema = LightDps.detect(dps)
                    val kind = when (schema) {
                        null -> "Connected, but the device reports no light data points."
                        else -> "Connected. ${schema.name} light layout."
                    }
                    kind + "\n" + dps.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.joinToString("\n") { "DP ${it.key} = ${it.value}" }
                },
                onFailure = { "Failed: ${it.message}" },
            )
            _state.update { it.copy(testing = false, testResult = text) }
        }
    }

    /** Saves and returns the config, or null if incomplete. */
    fun save(): DeviceConfig? = _state.value.toConfig().takeIf { it.isComplete }
}

/** Listens for Tuya UDP broadcasts on the local Wi-Fi. */
object LanDiscovery {
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
