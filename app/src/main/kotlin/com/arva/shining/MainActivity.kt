package com.arva.shining

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
                when {
                    route == "settings" -> PredictiveBackScreen(onBack = { route = "main" }) {
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
                    route == "automations" -> PredictiveBackScreen(onBack = { route = "main" }) {
                        AutomationsScreen(
                            onBack = { route = "main" },
                            onEdit = { id -> route = "edit:" + (id ?: "") },
                        )
                    }
                    route.startsWith("edit:") -> PredictiveBackScreen(onBack = { route = "automations" }) {
                        AutomationEditor(
                            id = route.removePrefix("edit:").ifEmpty { null },
                            onDone = { route = "automations" },
                        )
                    }
                    else -> MainScreen(
                        main,
                        onOpenSettings = { route = "settings" },
                        onOpenAutomations = { route = "automations" },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupLink(intent)
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
