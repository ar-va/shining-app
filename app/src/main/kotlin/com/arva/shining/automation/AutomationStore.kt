package com.arva.shining.automation

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Automations, the approximate location and per-automation run results (app-private prefs). */
class AutomationStore(context: Context) {
    private val prefs = context.getSharedPreferences("automations", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(Automation.listFromJson(prefs.getString(KEY_LIST, null)))
    val items: StateFlow<List<Automation>> = _items

    private val _results = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith("result.") }.mapKeys { it.key.removePrefix("result.") }.mapValues { it.value.toString() }
    )

    /** Last outcome per automation id, e.g. "Ran Tue 07:00" or "Skipped Tue 07:00: light unreachable". */
    val results: StateFlow<Map<String, String>> = _results

    fun get(id: String): Automation? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(automation: Automation) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id == automation.id }
        if (i >= 0) list[i] = automation else list += automation
        write(list)
    }

    @Synchronized
    fun delete(id: String) {
        write(_items.value.filterNot { it.id == id })
        prefs.edit { remove("result.$id") }
        _results.value = _results.value - id
    }

    private fun write(list: List<Automation>) {
        prefs.edit { putString(KEY_LIST, Automation.listToJson(list)) }
        _items.value = list
    }

    var location: GeoPoint?
        get() {
            val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return null
            return GeoPoint(lat, lon)
        }
        set(value) = prefs.edit {
            if (value == null) { remove("lat"); remove("lon") }
            else { putString("lat", "%.2f".format(java.util.Locale.ROOT, value.latitude)); putString("lon", "%.2f".format(java.util.Locale.ROOT, value.longitude)) }
        }

    fun setResult(id: String, text: String) {
        prefs.edit { putString("result.$id", text) }
        _results.value = _results.value + (id to text)
    }

    var lastArrivalMillis: Long
        get() = prefs.getLong("lastArrival", 0L)
        set(value) = prefs.edit { putLong("lastArrival", value) }

    /** Home/away state for arrival automations (see [Presence]). */
    var presence: Presence.State
        get() = runCatching { Presence.State.valueOf(prefs.getString("presence", null)!!) }.getOrDefault(Presence.State.UNKNOWN)
        set(value) = prefs.edit { putString("presence", value.name) }

    /** The Wi-Fi connection (Network id) on which the light was last reachable. */
    var homeNetwork: String?
        get() = prefs.getString("homeNetwork", null)
        set(value) = prefs.edit { putString("homeNetwork", value) }

    private companion object {
        const val KEY_LIST = "list"
    }
}
