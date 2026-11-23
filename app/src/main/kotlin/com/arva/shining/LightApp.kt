package com.arva.shining

import android.app.Application
import android.content.Context
import android.net.Network
import androidx.core.content.edit
import com.arva.shining.light.DeviceConfig
import com.arva.shining.light.HomeNetworkGuard
import com.arva.shining.light.LightException
import com.arva.shining.light.LightMode
import com.arva.shining.light.LightState
import com.arva.shining.light.OpenBekenLight
import com.arva.shining.light.PasswordGate
import com.arva.shining.light.PasswordTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LightApp : Application() {
    val store by lazy { ConfigStore(this) }
    val homeNetwork by lazy { HomeNetworkGuard(this, store) }
    val controller by lazy { LightController(store, homeNetwork) { automations.dropPendingAfterManualChange() } }
    val automations by lazy { com.arva.shining.automation.AutomationStore(this) }
    val themeStore by lazy { ThemeStore(this) }
    val presetStore by lazy { PresetStore(this) }

    /** For work that must outlive a screen (shortcut commands, automation runs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        presetStore // load custom presets early so automations can resolve their names
        com.arva.shining.automation.Scheduler.rescheduleAll(this)
    }
}

val Context.lightApp: LightApp get() = applicationContext as LightApp

/** Saved light settings (app-private SharedPreferences; excluded from backups). */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("light", Context.MODE_PRIVATE)

    fun load(): DeviceConfig? {
        // A light saved by a version that still supported Tuya has to be set up again.
        if (prefs.getString("kind", null) != KIND) return null
        val ip = prefs.getString("ip", "").orEmpty()
        if (ip.isBlank()) return null
        return DeviceConfig(
            name = prefs.getString("name", null) ?: "Lightstrip",
            ip = ip,
            username = prefs.getString("ob_user", "admin")?.ifBlank { "admin" } ?: "admin",
            password = prefs.getString("ob_pass", "") ?: "",
        )
    }

    fun save(cfg: DeviceConfig) {
        val sameLight = cfg.ip == prefs.getString("ip", null)
        prefs.edit {
            putString("kind", KIND)
            putString("name", cfg.name); putString("ip", cfg.ip)
            putString("ob_user", cfg.username); putString("ob_pass", cfg.password)
            // Old Tuya credentials are no longer used, so they don't stay on the phone.
            listOf("id", "key", "version", "schema").forEach(::remove)
            if (!sameLight) remove("light_mode")
            // A different device: forget the cached look.
            listOf("st_on", "st_colour", "st_hue", "st_sat", "st_bright", "st_temp").forEach(::remove)
        }
    }

    /** The light's last known mode, for when its status can't show it (off or very dim). */
    var lightMode: LightMode
        get() = runCatching { LightMode.valueOf(prefs.getString("light_mode", null)!!) }.getOrDefault(LightMode.UNKNOWN)
        set(value) = prefs.edit { putString("light_mode", value.name) }

    /** Describes the Wi-Fi network the OpenBeken password may be sent on. Survives edits to the light. */
    var homeNetwork: String?
        get() = prefs.getString("home_net", null)
        set(value) = prefs.edit { putString("home_net", value) }

    /** Last seen light state, shown instantly on open before the live read lands. */
    fun loadState(): CachedLightState? {
        if (!prefs.contains("st_on")) return null
        return CachedLightState(
            on = prefs.getBoolean("st_on", false),
            colourMode = prefs.getBoolean("st_colour", true),
            hue = prefs.getFloat("st_hue", 30f),
            saturation = prefs.getFloat("st_sat", 1f),
            brightness = prefs.getFloat("st_bright", 1f),
            temperature = prefs.getFloat("st_temp", 0.3f),
        )
    }

    fun saveState(s: CachedLightState) = prefs.edit {
        putBoolean("st_on", s.on); putBoolean("st_colour", s.colourMode)
        putFloat("st_hue", s.hue); putFloat("st_sat", s.saturation)
        putFloat("st_bright", s.brightness); putFloat("st_temp", s.temperature)
    }

    private companion object {
        const val KIND = "OPENBEKEN"
    }
}

data class CachedLightState(
    val on: Boolean, val colourMode: Boolean, val hue: Float,
    val saturation: Float, val brightness: Float, val temperature: Float,
)

/** Single owner of access to the light: serializes calls so commands reach it in order. */
class LightController(
    private val store: ConfigStore,
    private val gate: PasswordGate,
    private val onManualCommand: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var light: OpenBekenLight? = null
    private var lightConfig: DeviceConfig? = null

    suspend fun status(network: Network? = null, trust: PasswordTrust = PasswordTrust.KNOWN_NETWORK): LightState =
        withLight(network, trust) { read(it) }

    /**
     * Runs a [LightCommand], reading the light first when the command depends on its current state.
     * [network] pins the connection to one Wi-Fi network (used right after Wi-Fi connects).
     * [automated] marks runs from automations; anything else is a person's choice and cancels queued
     * catch-ups. [trust] says whether this call may record the current Wi-Fi as home.
     */
    suspend fun run(
        command: LightCommand,
        network: Network? = null,
        automated: Boolean = false,
        trust: PasswordTrust = PasswordTrust.KNOWN_NETWORK,
    ) {
        if (command.isEmpty) return
        command.preset?.let { if (findPreset(it) == null) throw LightException("There's no preset named $it") }
        withLight(network, trust) { light ->
            val current = if (command.needsState) read(light) else null
            light.apply(command, current)?.let { store.lightMode = it }
        }
        if (!automated) onManualCommand()
    }

    private fun read(light: OpenBekenLight): LightState =
        light.read(store.lightMode).also { if (it.mode != LightMode.UNKNOWN) store.lightMode = it.mode }

    private suspend fun <T> withLight(network: Network?, trust: PasswordTrust, block: (OpenBekenLight) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = store.load()?.takeIf { it.isComplete }
                ?: throw LightException("Set up your light first")
            // A pinned call gets its own object, so a login check never carries over between networks.
            if (network != null) return@withContext block(build(cfg, network, trust))
            val current = light?.takeIf { lightConfig == cfg }
                ?: build(cfg, null, trust).also { light = it; lightConfig = cfg }
            current.trust = trust
            block(current)
        }
    }

    private fun build(cfg: DeviceConfig, network: Network?, trust: PasswordTrust) =
        OpenBekenLight.fromAddress(cfg.ip, gate, network, cfg.username, cfg.password, trust)
}
