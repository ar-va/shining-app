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

    /** For work that must outlive a screen (shortcut commands, automation runs). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
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
                else DeviceConfig(name, id = "", ip = ip, localKey = "", version = TuyaVersion.V33, kind = LightKind.OPENBEKEN)
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
            remove("schema")
        }
    }

    var schema: LightSchema?
        get() = prefs.getString("schema", null)?.let { runCatching { LightSchema.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit { putString("schema", value?.name) }
}

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
        LightKind.OPENBEKEN -> OpenBekenLight.fromAddress(cfg.ip, network)
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
