package com.arva.shining

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arva.shining.ui.ShiningTheme
import com.arva.shining.ui.AutomationEditor
import com.arva.shining.ui.AutomationsScreen
import com.arva.shining.ui.MainScreen
import com.arva.shining.ui.PredictiveBackScreen
import com.arva.shining.ui.SettingsScreen
import com.arva.tuya.DeviceConfig

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSetupLink(intent)
        setContent {
            ShiningTheme {
                // Simple saved route: "main", "settings", "automations", "edit:<id>" or "edit:"
                var route by rememberSaveable { mutableStateOf("main") }
                // The home screen, reused both as the active route and as the "behind" page for
                // predictive back previews.
                val home = @Composable {
                    MainScreen(
                        main,
                        onOpenSettings = { route = "settings" },
                        onOpenAutomations = { route = "automations" },
                    )
                }
                val automations = @Composable {
                    AutomationsScreen(
                        onBack = { route = "main" },
                        onEdit = { id -> route = "edit:" + (id ?: "") },
                    )
                }
                when {
                    route == "settings" -> PredictiveBackScreen(onBack = { route = "main" }, behind = home) {
                        val settings: SettingsViewModel = viewModel()
                        SettingsScreen(
                            vm = settings,
                            onBack = { route = "main" },
                            onSave = { cfg ->
                                main.applyConfig(cfg)
                                route = "main"
                            },
                        )
                    }
                    route == "automations" -> PredictiveBackScreen(onBack = { route = "main" }, behind = home, content = automations)
                    route.startsWith("edit:") -> PredictiveBackScreen(onBack = { route = "automations" }, behind = automations) {
                        AutomationEditor(
                            id = route.removePrefix("edit:").ifEmpty { null },
                            onDone = { route = "automations" },
                        )
                    }
                    else -> home()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupLink(intent)
    }

    // While the light is on, the volume keys nudge its brightness. When it's off (or not set up),
    // they fall through to the normal phone volume so nothing feels hijacked.
    private fun handleVolumeKey(keyCode: Int): Boolean {
        val s = main.state.value
        if (!s.configured || !s.on) return false
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> main.bumpBrightness(5)
            KeyEvent.KEYCODE_VOLUME_DOWN -> main.bumpBrightness(-5)
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consume the matching key-up so the system volume panel doesn't appear.
        val s = main.state.value
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && s.configured && s.on) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        main.refresh()
    }

    private fun handleSetupLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        val cfg = DeviceConfig.fromSetupLink(link)
        if (cfg == null || !cfg.isComplete) {
            Toast.makeText(this, "That setup link is incomplete", Toast.LENGTH_LONG).show()
        } else {
            main.applyConfig(cfg)
            Toast.makeText(this, "Saved “${cfg.name}”", Toast.LENGTH_SHORT).show()
        }
        intent.data = null // don't re-apply on rotation
    }
}
