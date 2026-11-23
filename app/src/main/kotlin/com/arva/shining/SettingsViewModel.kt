package com.arva.shining

import com.arva.shining.light.PasswordTrust
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.shining.light.DeviceConfig
import com.arva.shining.light.OpenBekenLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val name: String = "Lightstrip",
    val ip: String = "",
    val obUser: String = "admin",
    val obPassword: String = "",
    val testing: Boolean = false,
    val testResult: String? = null,
    val online: Boolean? = null, // null = unknown/not checked yet
) {
    fun toConfig() = DeviceConfig(name.trim().ifEmpty { "Lightstrip" }, ip.trim(), obUser.trim().ifEmpty { "admin" }, obPassword)
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(saved())
    val state: StateFlow<SettingsState> = _state

    private fun saved() = getApplication<Application>().lightApp.store.load()?.let {
        SettingsState(name = it.name, ip = it.ip, obUser = it.username, obPassword = it.password)
    } ?: SettingsState()

    /** Drops unsaved edits. */
    fun discard() {
        _state.value = saved()
    }

    fun edit(transform: (SettingsState) -> SettingsState) = _state.update { transform(it).copy(testResult = null, online = null) }

    private fun light(cfg: DeviceConfig, trust: PasswordTrust = PasswordTrust.KNOWN_NETWORK) = OpenBekenLight.fromAddress(
        cfg.ip, getApplication<Application>().lightApp.homeNetwork,
        username = cfg.username, password = cfg.password, trust = trust,
    )

    /** A quiet reachability check for the status chip (no spinner, no result text). */
    fun probe() {
        val cfg = _state.value.toConfig()
        if (!cfg.isComplete) {
            _state.update { it.copy(online = false) }
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { light(cfg).read() }.isSuccess }
            _state.update { it.copy(online = ok) }
        }
    }

    fun test() {
        val cfg = _state.value.toConfig()
        if (!cfg.isComplete) {
            _state.update { it.copy(testResult = "Fill in the light's IP address") }
            return
        }
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val state = light(cfg, PasswordTrust.CONFIRM).read()
                    "Connected to OpenBeken.\nPower ${if (state.on) "on" else "off"}, brightness ${(state.brightness * 100).toInt()}%"
                }.getOrElse { "Failed: ${it.message}" }
            }
            _state.update { it.copy(testing = false, testResult = text) }
        }
    }

    /** The config to save, or null if incomplete. */
    fun save(): DeviceConfig? = _state.value.toConfig().takeIf { it.isComplete }
}
