package com.arva.shining

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.tuya.DeviceConfig
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

    init {
        viewModelScope.launch {
            for (unit in wake) {
                val command = synchronized(this@MainViewModel) { pending.also { pending = null } } ?: continue
                runCatching { controller.run(command) }
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
        synchronized(this) { pending = command }
        wake.trySend(Unit)
    }
}
