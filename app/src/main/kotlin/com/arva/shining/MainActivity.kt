package com.arva.shining

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.arva.shining.automation.Presence
import kotlinx.coroutines.launch
import com.arva.shining.ui.ShiningTheme
import com.arva.shining.ui.AutomationEditor
import com.arva.shining.ui.AutomationsScreen
import com.arva.shining.ui.MainScreen
import com.arva.shining.ui.PredictiveBackScreen
import com.arva.shining.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShiningTheme {
                // Simple saved route: "main", "settings", "automations", "edit:<id>" or "edit:"
                var route by rememberSaveable { mutableStateOf("main") }
                val leaveSettings: () -> Unit = {
                    settings.discard()
                    route = "main"
                }
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
                    route == "settings" -> PredictiveBackScreen(onBack = leaveSettings, behind = home) {
                        SettingsScreen(
                            vm = settings,
                            onBack = leaveSettings,
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

    override fun onResume() {
        super.onResume()
        main.refresh()
        // Back at home with something queued: run it now, then show the result.
        lifecycleScope.launch {
            runCatching { Presence.onAppOpened(lightApp) }
            main.refresh(showLoading = false)
        }
    }
}
