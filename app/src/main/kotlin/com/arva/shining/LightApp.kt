package com.arva.shining

import android.app.Application
import android.content.Context
import android.net.Network
import androidx.core.content.edit
import com.arva.shining.light.LightDriver
import com.arva.shining.light.OpenBekenLight
import com.arva.shining.light.TuyaLightDriver
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightKind
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
import com.arva.tuya.TuyaException
import com.arva.tuya.TuyaVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class LightApp : Application() {
    val store by lazy { ConfigStore(this) }
    val controller by lazy { LightController(store) }
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
        val kind = LightKind.parse(prefs.getString("kind", null))
        val name = prefs.getString("name", null) ?: "Lightstrip"
        val ip = prefs.getString("ip", "") ?: ""
        return when (kind) {
            LightKind.OPENBEKEN ->
                if (ip.isBlank()) null
                else DeviceConfig(
                    name, id = "", ip = ip, localKey = "", version = TuyaVersion.V33, kind = LightKind.OPENBEKEN,
                    username = prefs.getString("ob_user", "admin")?.ifBlank { "admin" } ?: "admin",
                    password = prefs.getString("ob_pass", "") ?: "",
                )
            LightKind.TUYA -> {
                val id = prefs.getString("id", null) ?: return null
                DeviceConfig(
                    name = name, id = id, ip = ip,
                    localKey = prefs.getString("key", "")!!,
                    version = TuyaVersion.parse(prefs.getString("version", null)) ?: TuyaVersion.V33,
                    kind = LightKind.TUYA,
                )
            }
        }
    }

    fun save(cfg: DeviceConfig) {
        prefs.edit {
            putString("kind", cfg.kind.name)
            putString("name", cfg.name); putString("id", cfg.id); putString("ip", cfg.ip)
            putString("key", cfg.localKey); putString("version", cfg.version.text)
            putString("ob_user", cfg.username); putString("ob_pass", cfg.password)
            remove("schema")
            // A different device: forget the cached look.
            listOf("st_on", "st_colour", "st_hue", "st_sat", "st_bright", "st_temp").forEach(::remove)
        }
    }

    /** Updates the device's IP in place (used when it moves to a new address). */
    fun updateIp(ip: String) = prefs.edit { putString("ip", ip) }

    var schema: LightSchema?
        get() = prefs.getString("schema", null)?.let { runCatching { LightSchema.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit { putString("schema", value?.name) }

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
}

data class CachedLightState(
    val on: Boolean, val colourMode: Boolean, val hue: Float,
    val saturation: Float, val brightness: Float, val temperature: Float,
)

/**
 * Single owner of the connection to the light. Serializes calls, reuses a Tuya socket while in use
 * and closes it after 20 s idle (Tuya lights allow one local client; OpenBeken is stateless HTTP).
 */
class LightController(private val store: ConfigStore) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var driver: LightDriver? = null
    private var driverConfig: DeviceConfig? = null
    private var idleJob: Job? = null

    data class Snapshot(val schema: LightSchema?, val state: LightState?, val dps: Map<String, Any?>)

    suspend fun status(network: Network? = null): Snapshot = withDriver(network) { it.read().toSnapshot() }

    /**
     * Runs a [LightCommand], reading the light first when the command depends on its current state.
     * [network] pins the connection to one Wi-Fi network (used right after Wi-Fi connects).
     * Returns the state read before the command, if one was read.
     */
    suspend fun run(command: LightCommand, network: Network? = null): Snapshot? {
        if (command.isEmpty) return null
        return withDriver(network) { driver ->
            val before = if (command.needsState) driver.read().toSnapshot() else null
            driver.apply(command, before?.state)
            before
        }
    }

    fun reset() {
        scope.launch { mutex.withLock { closeLocked() } }
    }

    private fun com.arva.shining.light.DriverReading.toSnapshot() = Snapshot(schema, state, detail)

    private suspend fun <T> withDriver(network: Network?, block: (LightDriver) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = store.load()?.takeIf { it.isComplete }
                ?: throw TuyaException("Set up your light first")
            if (network != null) {
                // One-off connection pinned to a network; a Tuya light allows one client, so drop ours first.
                closeLocked()
                return@withContext buildDriver(cfg, network).use(block)
            }
            val d = driver?.takeIf { driverConfig == cfg } ?: run {
                closeLocked()
                buildDriver(cfg, null).also { driver = it; driverConfig = cfg }
            }
            val wasConnected = d.isConnected
            val result = try {
                block(d)
            } catch (e: IOException) {
                // A reused Tuya socket may have been dropped by the light; retry once on a fresh one.
                if (!wasConnected) throw e
                block(d)
            }
            scheduleIdleClose()
            result
        }
    }

    private fun buildDriver(cfg: DeviceConfig, network: Network?): LightDriver = when (cfg.kind) {
        LightKind.TUYA -> TuyaLightDriver(cfg, network, store.schema) { store.schema = it }
        LightKind.OPENBEKEN -> OpenBekenLight.fromAddress(cfg.ip, network, cfg.username, cfg.password)
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(20_000)
            mutex.withLock { driver?.close() }
        }
    }

    private fun closeLocked() {
        driver?.close()
        driver = null
        driverConfig = null
    }
}
