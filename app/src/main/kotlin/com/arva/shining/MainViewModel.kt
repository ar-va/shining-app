package com.arva.shining

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.tuya.DeviceConfig
import com.arva.tuya.Hsv
import com.arva.tuya.LightDps
import com.arva.tuya.LightMode
import com.arva.tuya.LightSchema
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A one-tap preset: either a colour (hue/sat) or a white temperature. */
data class Preset(val label: String, val hue: Float? = null, val saturation: Float = 1f, val temperature: Float? = null)

val PRESETS = listOf(
    Preset("Warm", temperature = 0f),
    Preset("Daylight", temperature = 1f),
    Preset("Red", hue = 0f),
    Preset("Orange", hue = 25f),
    Preset("Yellow", hue = 50f),
    Preset("Green", hue = 120f),
    Preset("Cyan", hue = 185f),
    Preset("Blue", hue = 230f),
    Preset("Purple", hue = 275f),
    Preset("Pink", hue = 320f, saturation = 0.7f),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.lightApp.store
    private val controller = app.lightApp.controller

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state

    // Slider drags produce many changes: merge them and send the latest every ~100 ms.
    private val pending = LinkedHashMap<String, (LightSchema) -> Map<String, Any>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (unit in wake) {
                val batch = synchronized(pending) { pending.values.toList().also { pending.clear() } }
                if (batch.isEmpty()) continue
                runCatching { controller.send { schema -> batch.fold(emptyMap()) { acc, f -> acc + f(schema) } } }
                    .onSuccess { _state.update { it.copy(connected = true, error = null) } }
                    .onFailure { e -> _state.update { it.copy(connected = false, error = e.message) } }
                delay(100)
            }
        }
    }

    private fun initialState(): UiState {
        val cfg = store.load()
        return UiState(configured = cfg?.isComplete == true, name = cfg?.name ?: "Lightstrip")
    }

    fun refresh() {
        val cfg = store.load()
        _state.update { it.copy(configured = cfg?.isComplete == true, name = cfg?.name ?: "Lightstrip") }
        if (cfg?.isComplete != true) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { controller.status() }
                .onSuccess { snap ->
                    val s = snap.state
                    _state.update {
                        if (s == null) it.copy(loading = false, connected = true, error = "Connected, but this doesn't look like a light")
                        else it.copy(
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
                .onFailure { e -> _state.update { it.copy(loading = false, connected = false, error = e.message) } }
        }
    }

    fun applyConfig(cfg: DeviceConfig) {
        store.save(cfg)
        controller.reset()
        refresh()
    }

    fun togglePower() {
        val on = !_state.value.on
        _state.update { it.copy(on = on) }
        queue("power") { LightDps.power(it, on) }
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
        // Mode commands switch the light on themselves; a pending power command is superseded.
        synchronized(pending) { pending.remove("power") }
        if (s.colourMode) {
            queue("mode") { LightDps.colour(it, Hsv(s.hue, s.saturation, s.brightness)) }
        } else {
            queue("mode") { LightDps.white(it, s.brightness, s.temperature) }
        }
    }

    private fun queue(key: String, build: (LightSchema) -> Map<String, Any>) {
        synchronized(pending) {
            pending.remove(key)
            pending[key] = build
        }
        wake.trySend(Unit)
    }
}
