package com.arva.shining

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A one-tap preset: either a colour (hue/sat) or a white temperature. */
@Serializable
data class Preset(val label: String, val hue: Float? = null, val saturation: Float = 1f, val temperature: Float? = null)

val DEFAULT_PRESETS = listOf(
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

/**
 * The presets [findPreset] resolves names against. Kept in sync by [PresetStore]. Built-in names
 * always resolve (even if removed from the visible list) so existing automations keep working.
 */
object Presets {
    @Volatile var all: List<Preset> = DEFAULT_PRESETS
}

fun findPreset(label: String): Preset? {
    val l = label.trim()
    return Presets.all.firstOrNull { it.label.equals(l, ignoreCase = true) }
        ?: DEFAULT_PRESETS.firstOrNull { it.label.equals(l, ignoreCase = true) }
}

/** The user's editable preset list (persisted). Starts from [DEFAULT_PRESETS]. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Preset>> = _items

    init {
        Presets.all = _items.value
    }

    private fun load(): List<Preset> {
        val stored = prefs.getString("list", null) ?: return DEFAULT_PRESETS
        return runCatching { json.decodeFromString<List<Preset>>(stored) }.getOrDefault(DEFAULT_PRESETS)
    }

    private fun write(list: List<Preset>) {
        prefs.edit { putString("list", json.encodeToString(list)) }
        _items.value = list
        Presets.all = list
    }

    fun add(preset: Preset) = write(_items.value + preset)

    fun removeAt(index: Int) = write(_items.value.filterIndexed { i, _ -> i != index })

    fun resetToDefault() {
        prefs.edit { remove("list") }
        _items.value = DEFAULT_PRESETS
        Presets.all = DEFAULT_PRESETS
    }
}
