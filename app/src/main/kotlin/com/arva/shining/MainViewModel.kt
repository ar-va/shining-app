package com.arva.shining

import com.arva.shining.light.PasswordTrust
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arva.shining.light.DeviceConfig
import com.arva.shining.light.Hsv
import com.arva.shining.light.LightMode
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
) {
    fun showing(c: CachedLightState) = copy(
        on = c.on, colourMode = c.colourMode, hue = c.hue,
        saturation = c.saturation, brightness = c.brightness, temperature = c.temperature,
    )
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.lightApp.store
    private val controller = app.lightApp.controller

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state

    // Recently picked colours (session-scoped), newest first, for a quick "snap back" row.
    private val _recent = MutableStateFlow<List<Hsv>>(emptyList())
    val recent: StateFlow<List<Hsv>> = _recent

    // A newer version on GitHub, checked once per app run. Dismissing it lasts for that run.
    private val _update = MutableStateFlow<String?>(null)
    val update: StateFlow<String?> = _update

    // Slider drags produce many changes a few ms apart. Keep only the latest desired command and
    // send it at most every ~100 ms, so the light isn't flooded.
    @Volatile private var pending: LightCommand? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Suppress live-sync writes right after the user touches something, so polls don't fight input.
    @Volatile private var lastInteraction = 0L

    // Reads in progress (main thread only). Polls wait their turn so they can't queue up ahead of a tap.
    private var refreshing = 0

    init {
        viewModelScope.launch {
            for (unit in wake) {
                val command = synchronized(this@MainViewModel) { pending.also { pending = null } } ?: continue
                runCatching { controller.run(command, trust = PasswordTrust.FIRST_USE) }
                    .onSuccess { _state.update { it.copy(connected = true, error = null) }; persist() }
                    .onFailure { e ->
                        // Nothing newer is queued, so go back to what the light last confirmed.
                        val saved = store.loadState().takeIf { pending == null }
                        _state.update { s -> (saved?.let(s::showing) ?: s).copy(connected = false, error = e.message) }
                    }
                delay(100)
            }
        }
        viewModelScope.launch {
            Updater.checkLatest()?.takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }
                ?.let { _update.value = it.version }
        }
    }

    private fun initialState(): UiState {
        val cfg = store.load()
        val base = UiState(configured = cfg?.isComplete == true, name = cfg?.name ?: "Lightstrip")
        return store.loadState()?.let(base::showing) ?: base
    }

    fun dismissUpdate() {
        _update.value = null
    }

    /** [showLoading] false for background polls, so the top bar doesn't flash every few seconds. */
    fun refresh(showLoading: Boolean = true) {
        if (!showLoading && refreshing > 0) return
        val cfg = store.load()
        _state.update { it.copy(configured = cfg?.isComplete == true, name = cfg?.name ?: "Lightstrip") }
        if (cfg?.isComplete != true) return
        if (showLoading) _state.update { it.copy(loading = true) }
        refreshing++
        viewModelScope.launch {
            try {
                runCatching { controller.status(trust = PasswordTrust.FIRST_USE) }
                    .onSuccess { s ->
                        // Don't clobber sliders the user is actively adjusting.
                        val quiet = System.currentTimeMillis() - lastInteraction > QUIET_MS && pending == null
                        _state.update {
                            if (!quiet) it.copy(loading = false, connected = true, error = null)
                            else it.copy(
                                loading = false, connected = true, error = null,
                                on = s.on,
                                colourMode = s.mode == LightMode.COLOUR || (s.mode != LightMode.WHITE && it.colourMode),
                                hue = s.colour?.h ?: it.hue,
                                saturation = s.colour?.s ?: it.saturation,
                                brightness = if (s.mode == LightMode.UNKNOWN) it.brightness else s.brightness,
                                temperature = s.temperature ?: it.temperature,
                            )
                        }
                        persist()
                    }
                    .onFailure { e -> _state.update { it.copy(loading = false, connected = false, error = e.message) } }
            } finally {
                refreshing--
            }
        }
    }

    private fun persist() {
        val s = _state.value
        store.saveState(CachedLightState(s.on, s.colourMode, s.hue, s.saturation, s.brightness, s.temperature))
    }

    fun applyConfig(cfg: DeviceConfig) {
        store.save(cfg)
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

    /** Remember the current colour (called when the picker closes) so it appears in the recents row. */
    fun rememberCurrentColour() {
        val s = _state.value
        if (!s.colourMode) return
        val c = Hsv(s.hue, s.saturation, 1f)
        _recent.update { prev ->
            (listOf(c) + prev.filterNot { kotlin.math.abs(it.h - c.h) < 4f && kotlin.math.abs(it.s - c.s) < 0.04f }).take(8)
        }
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
