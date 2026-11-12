package com.arva.shining

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light/dark preference. SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object {
        fun parse(s: String?): ThemeMode = entries.firstOrNull { it.name.equals(s, true) } ?: SYSTEM
    }
}

/** App appearance settings (persisted). [dynamic] is Material You: colours from the wallpaper. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(ThemeMode.parse(prefs.getString("mode", null)))
    val mode: StateFlow<ThemeMode> = _mode

    private val _dynamic = MutableStateFlow(prefs.getBoolean("dynamic", false))
    val dynamic: StateFlow<Boolean> = _dynamic

    fun setMode(mode: ThemeMode) {
        prefs.edit { putString("mode", mode.name) }
        _mode.value = mode
    }

    fun setDynamic(on: Boolean) {
        prefs.edit { putBoolean("dynamic", on) }
        _dynamic.value = on
    }
}
