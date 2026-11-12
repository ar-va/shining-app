package com.arva.shining

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightDps
import com.arva.tuya.LightSchema
import com.arva.tuya.LightState
import com.arva.tuya.TuyaDevice
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
import javax.net.SocketFactory

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
        val id = prefs.getString("id", null) ?: return null
        return DeviceConfig(
            name = prefs.getString("name", null) ?: "Lightstrip",
            id = id,
            ip = prefs.getString("ip", "")!!,
            localKey = prefs.getString("key", "")!!,
            version = TuyaVersion.parse(prefs.getString("version", null)) ?: TuyaVersion.V33,
        )
    }

    fun save(cfg: DeviceConfig) {
        prefs.edit {
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
 * Single owner of the TCP connection (Tuya lights often allow only one local client).
 * Serializes calls, keeps the socket open while in use and closes it after 20 s idle.
 */
class LightController(private val store: ConfigStore) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var device: TuyaDevice? = null
    private var deviceConfig: DeviceConfig? = null
    private var idleJob: Job? = null

    data class Snapshot(val schema: LightSchema?, val state: LightState?, val dps: Map<String, Any?>)

    suspend fun status(socketFactory: SocketFactory? = null): Snapshot {
        val dps = withDevice(socketFactory) { it.status() }
        val schema = LightDps.detect(dps)?.also { if (store.schema != it) store.schema = it } ?: store.schema
        return Snapshot(schema, schema?.let { LightDps.parse(it, dps) }, dps)
    }

    /** Builds DPs for the light's schema (querying it once if unknown) and sends them. */
    suspend fun send(build: (LightSchema) -> Map<String, Any>) {
        val schema = store.schema ?: status().schema
            ?: throw TuyaException("This device doesn't look like a Tuya light (no DP 1 or 20)")
        val dps = build(schema)
        if (dps.isEmpty()) return
        withDevice { it.setDps(dps) }
    }

    /**
     * Runs a [LightCommand]. Reads the light first when the command depends on its current state.
     * [socketFactory] pins the connection to one network (used right after Wi-Fi connects).
     * Returns the state read before the command, if one was read.
     */
    suspend fun run(command: LightCommand, socketFactory: SocketFactory? = null): Snapshot? {
        if (command.isEmpty) return null
        val before = if (command.needsState || store.schema == null) status(socketFactory) else null
        val schema = before?.schema ?: store.schema
            ?: throw TuyaException("This device doesn't look like a Tuya light (no DP 1 or 20)")
        val dps = command.toDps(schema, before?.state)
        withDevice(socketFactory) { it.setDps(dps) }
        return before
    }

    fun reset() {
        scope.launch { mutex.withLock { closeLocked() } }
    }

    private suspend fun <T> withDevice(socketFactory: SocketFactory? = null, block: (TuyaDevice) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val cfg = store.load()?.takeIf { it.isComplete }
                ?: throw TuyaException("Set up your light first")
            if (socketFactory != null) {
                // One-off connection on a specific network; the light allows one client, so drop ours first.
                closeLocked()
                return@withContext TuyaDevice(cfg.id, cfg.ip, cfg.localKey, cfg.version, socketFactory = socketFactory)
                    .use(block)
            }
            val dev = device?.takeIf { deviceConfig == cfg } ?: run {
                closeLocked()
                TuyaDevice(cfg.id, cfg.ip, cfg.localKey, cfg.version).also { device = it; deviceConfig = cfg }
            }
            val wasConnected = dev.isConnected
            val result = try {
                block(dev)
            } catch (e: IOException) {
                // A reused socket may have been dropped by the light; retry once on a fresh one.
                if (!wasConnected) throw e
                block(dev)
            }
            scheduleIdleClose()
            result
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(20_000)
            mutex.withLock { device?.close() }
        }
    }

    private fun closeLocked() {
        device?.close()
        device = null
        deviceConfig = null
    }
}
