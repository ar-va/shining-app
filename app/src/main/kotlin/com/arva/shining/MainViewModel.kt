package com.arva.shining

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightKind
import com.arva.tuya.LightMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class UiState(
    val configured: Boolean = false,
    val name: String = "Lightstrip",
    val loading: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val on: Boolean = false,
    val colourMode: Boolean = true,
    val hue: Float = 30f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val temperature: Float = 0.3f,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.lightApp.store
    private val controller = app.lightApp.controller

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state

    // Slider drags produce many changes a few ms apart. Keep only the latest desired command and
    // send it at most every ~100 ms, so the light isn't flooded.
    @Volatile private var pending: LightCommand? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Suppress live-sync writes right after the user touches something, so polls don't fight input.
    @Volatile private var lastInteraction = 0L
    @Volatile private var healing = false

    init {
        viewModelScope.launch {
            for (unit in wake) {
                val command = synchronized(this@MainViewModel) { pending.also { pending = null } } ?: continue
                runCatching { controller.run(command) }
                    .onSuccess { _state.update { it.copy(connected = true, error = null) }; persist() }
                    .onFailure { e -> _state.update { it.copy(connected = false, error = e.message) } }
                delay(100)
            }
        }
    }

    private fun initialState(): UiState {
        val cfg = store.load()
        val c = store.loadState()
        return UiState(
            configured = cfg?.isComplete == true,
            name = cfg?.name ?: "Lightstrip",
            on = c?.on ?: false,
            colourMode = c?.colourMode ?: true,
            hue = c?.hue ?: 30f,
            saturation = c?.saturation ?: 1f,
            brightness = c?.brightness ?: 1f,
            temperature = c?.temperature ?: 0.3f,
        )
    }

    /** [showLoading] false for background polls, so the top bar doesn't flash every few seconds. */
    fun refresh(showLoading: Boolean = true) {
        val cfg = store.load()
        _state.update { it.copy(configured = cfg?.isComplete == true, name = cfg?.name ?: "Lightstrip") }
        if (cfg?.isComplete != true) return
        if (showLoading) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { controller.status() }
                .onSuccess { snap ->
                    val s = snap.state
                    // Don't clobber sliders the user is actively adjusting.
                    val quiet = System.currentTimeMillis() - lastInteraction > QUIET_MS && pending == null
                    _state.update {
                        when {
                            s == null -> it.copy(loading = false, connected = true, error = "Connected, but this doesn't look like a light")
                            !quiet -> it.copy(loading = false, connected = true, error = null)
                            else -> it.copy(
                                loading = false, connected = true, error = null,
                                on = s.on,
                                colourMode = s.mode == LightMode.COLOUR || (s.mode != LightMode.WHITE && it.colourMode),
                                hue = s.colour?.h ?: it.hue,
                                saturation = s.colour?.s ?: it.saturation,
                                brightness = s.brightness,
                                temperature = s.temperature ?: it.temperature,
                            )
                        }
                    }
                    if (s != null) persist()
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, connected = false, error = e.message) }
                    healIp(cfg, e)
                }
        }
    }

    /** If a Tuya light became unreachable, look for it at a new IP and retry once. */
    private fun healIp(cfg: DeviceConfig, error: Throwable) {
        if (healing || cfg.kind != LightKind.TUYA || cfg.id.isBlank()) return
        val msg = error.message.orEmpty().lowercase()
        if (!(msg.contains("reach") || msg.contains("answer") || msg.contains("connect"))) return
        healing = true
        viewModelScope.launch {
            try {
                val newIp = LanDiscovery.findIp(getApplication(), cfg.id)
                if (newIp != null && newIp != cfg.ip) {
                    store.updateIp(newIp)
                    controller.reset()
                    refresh(showLoading = false)
                }
            } catch (_: Exception) {
            } finally {
                healing = false
            }
        }
    }

    private fun persist() {
        val s = _state.value
        store.saveState(CachedLightState(s.on, s.colourMode, s.hue, s.saturation, s.brightness, s.temperature))
    }

    fun applyConfig(cfg: DeviceConfig) {
        store.save(cfg)
        controller.reset()
        refresh()
    }

    fun togglePower() {
        val on = !_state.value.on
        _state.update { it.copy(on = on) }
        queue(LightCommand(power = if (on) LightCommand.Power.ON else LightCommand.Power.OFF))
    }

    fun setColourMode(colour: Boolean) {
        _state.update { it.copy(colourMode = colour, on = true) }
        sendCurrentMode()
    }

    fun setHue(h: Float) {
        _state.update { it.copy(hue = h, colourMode = true, on = true) }
        sendCurrentMode()
    }

    fun setSaturation(s: Float) {
        _state.update { it.copy(saturation = s, colourMode = true, on = true) }
        sendCurrentMode()
    }

    /** Hue and saturation together, from the 2-D colour wheel. */
    fun setHsv(h: Float, s: Float) {
        _state.update { it.copy(hue = h, saturation = s, colourMode = true, on = true) }
        sendCurrentMode()
    }

    fun setTemperature(t: Float) {
        _state.update { it.copy(temperature = t, colourMode = false, on = true) }
        sendCurrentMode()
    }

    fun setBrightness(b: Float) {
        _state.update { it.copy(brightness = b.coerceAtLeast(0.01f), on = true) }
        sendCurrentMode()
    }


    fun applyPreset(p: Preset) {
        _state.update {
            if (p.temperature != null) it.copy(colourMode = false, temperature = p.temperature, on = true)
            else it.copy(colourMode = true, hue = p.hue ?: it.hue, saturation = p.saturation, on = true)
        }
        sendCurrentMode()
    }

    private fun sendCurrentMode() {
        val s = _state.value
        val bright = (s.brightness * 100).roundToInt().coerceIn(1, 100)
        queue(
            if (s.colourMode) {
                LightCommand(hue = s.hue.roundToInt(), saturation = (s.saturation * 100).roundToInt(), brightness = bright)
            } else {
                LightCommand(warmth = (s.temperature * 100).roundToInt(), brightness = bright)
            }
        )
    }

    private fun queue(command: LightCommand) {
        lastInteraction = System.currentTimeMillis()
        synchronized(this) { pending = command }
        wake.trySend(Unit)
    }

    private companion object {
        const val QUIET_MS = 2_000L
    }
}
