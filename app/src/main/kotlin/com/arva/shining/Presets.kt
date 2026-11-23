package com.arva.shining

import android.content.Context
import androidx.core.content.edit
import com.arva.shining.automation.Automation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A one-tap preset: either a colour (hue/sat) or a white temperature. */
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

/** Automations that would lose their colour if [removing] went away. Built-in names always resolve. */
fun automationsUsing(removing: List<Preset>, automations: List<Automation>): List<Automation> {
    val custom = removing.map { it.label }.filter { l -> DEFAULT_PRESETS.none { it.label.equals(l, ignoreCase = true) } }
    return automations.filter { a -> custom.any { it.equals(a.command.preset?.trim(), ignoreCase = true) } }
}

/** [base], or "base 2" and so on when that name is taken: automations find presets by name. */
fun freePresetName(base: String): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { findPreset(it) == null }

fun presetsToJson(list: List<Preset>): String = JsonArray(list.map { p ->
    buildJsonObject {
        put("label", p.label); put("hue", p.hue); put("saturation", p.saturation); put("temperature", p.temperature)
    }
}).toString()

fun presetsFromJson(text: String): List<Preset> = Json.parseToJsonElement(text).jsonArray.map { e ->
    val o = e.jsonObject
    Preset(
        label = o.getValue("label").jsonPrimitive.content,
        hue = o["hue"]?.jsonPrimitive?.floatOrNull,
        saturation = o["saturation"]?.jsonPrimitive?.floatOrNull ?: 1f,
        temperature = o["temperature"]?.jsonPrimitive?.floatOrNull,
    )
}

/** The user's editable preset list (persisted). Starts from [DEFAULT_PRESETS]. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Preset>> = _items

    init {
        Presets.all = _items.value
    }

    private fun load(): List<Preset> {
        val stored = prefs.getString("list", null) ?: return DEFAULT_PRESETS
        return runCatching { presetsFromJson(stored) }.getOrDefault(DEFAULT_PRESETS)
    }

    private fun write(list: List<Preset>) {
        prefs.edit { putString("list", presetsToJson(list)) }
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
