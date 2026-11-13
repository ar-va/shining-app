package com.arva.shining.ui

import com.arva.shining.lightApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arva.shining.MainViewModel
import com.arva.shining.Preset
import com.arva.shining.R
import com.arva.shining.UiState

@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenAutomations: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Arva.colors

    // Live sync: while the screen is on top, poll quietly so external changes (voice, another phone)
    // show up and the socket stays warm for snappy slider response.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(s.configured) {
        if (!s.configured) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(4_000)
                vm.refresh(showLoading = false)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ArvaHeader("Shining") {
            if (s.configured) {
                HeaderIcon(R.drawable.ic_refresh, "Refresh", vm::refresh)
                HeaderIcon(R.drawable.ic_clock, "Automations", onOpenAutomations)
            }
            HeaderIcon(R.drawable.ic_settings, "Light setup", onOpenSettings)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg)
        }
        if (!s.configured) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) { SetupPrompt(onOpenSettings) }
        } else {
            Controls(s, vm)
        }
    }
}

@Composable
private fun SetupPrompt(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow("First run")
        Text("Connect your lightstrip", style = MaterialTheme.typography.headlineMedium, color = Arva.colors.text)
        Text(
            "Shining talks to the light over your Wi-Fi. It needs the light's details once — the README explains how to get them.",
            style = MaterialTheme.typography.bodyLarge,
            color = Arva.colors.text2,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Set up", onOpenSettings)
    }
}

@Composable
private fun ColumnScope.Controls(s: UiState, vm: MainViewModel) {
    val c = Arva.colors
    val view = LocalView.current
    val toggle = { view.hapticTick(); vm.togglePower() }

    // Fixed header: name + status, then the brightness capsule (vertical drag lives here, outside the
    // scroll below, so dragging it never fights the page scroll).
    Column(
        Modifier.padding(horizontal = 20.dp).padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
            StatusChip(s)
        }
        BrightnessCapsule(s, onToggle = toggle, onBrightness = vm::setBrightness)
    }

    // Scrollable controls.
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BrightnessStepper(s, onBrightness = vm::setBrightness)
        PrimaryButton(if (s.on) "Turn off" else "Turn on", toggle, Modifier.fillMaxWidth())

        ArvaCard {
            Segmented(listOf(true to "Colour", false to "Temperature"), s.colourMode, vm::setColourMode)
            if (s.colourMode) {
                ColourWheel(s.hue, s.saturation, vm::setHsv)
            } else {
                ColorSlider("Temperature", s.temperature, warmthName(s.temperature), vm::setTemperature, WarmthTrack)
            }
        }

        PresetsSection(s, vm)
    }
}

/** Editable presets grid: tap to apply, long-press to remove, "+" to save the current colour. */
@Composable
private fun PresetsSection(s: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val store = context.lightApp.presetStore
    val presets by store.items.collectAsStateWithLifecycle()
    val view = LocalView.current
    var removing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel("Presets", Modifier.weight(1f))
            Text("Long-press to remove", style = MaterialTheme.typography.bodySmall, color = Arva.colors.text2)
        }
        // The preset tiles followed by an "add" tile, laid out 5 per row.
        val slots = presets.indices.toList() + ADD_TILE
        slots.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { idx ->
                    if (idx == ADD_TILE) {
                        AddPresetTile(Modifier.weight(1f)) { view.hapticSelect(); adding = true }
                    } else {
                        val p = presets[idx]
                        PresetSwatch(
                            p, Modifier.weight(1f),
                            onClick = { view.hapticSelect(); vm.applyPreset(p) },
                            onLongClick = { view.hapticTick(); removing = idx },
                        )
                    }
                }
                repeat(5 - row.size) { Box(Modifier.weight(1f)) } // keep tile widths consistent
            }
        }
    }

    if (adding) {
        AddPresetDialog(
            defaultName = if (s.colourMode) colourName(s.hue) else warmthName(s.temperature),
            onDismiss = { adding = false },
            onSave = { name ->
                val preset = if (s.colourMode) Preset(name, hue = s.hue, saturation = s.saturation)
                else Preset(name, temperature = s.temperature)
                store.add(preset)
                adding = false
            },
        )
    }
    removing?.let { idx ->
        val label = presets.getOrNull(idx)?.label ?: ""
        ConfirmDialog(
            title = "Remove \"$label\"?",
            confirm = "Remove",
            onConfirm = { store.removeAt(idx); removing = null },
            onDismiss = { removing = null },
        )
    }
}

private const val ADD_TILE = -1

/** Small pill shown only when there's something to say (updating / offline). */
@Composable
private fun StatusChip(s: UiState) {
    val c = Arva.colors
    val (dot, label) = when {
        s.loading -> c.accent to "Updating"
        s.error != null || !s.connected -> c.error to "Offline"
        else -> return
    }
    Row(
        Modifier.clip(RadiusTag).background(c.sunken).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.text2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetSwatch(p: Preset, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Arva.colors
    val colour = if (p.temperature != null) whiteColor(p.temperature) else Color.hsv(p.hue ?: 0f, p.saturation, 1f)
    Column(
        modifier
            .clip(RadiusControl)
            .combinedClickable(onClickLabel = "Set ${p.label}", onLongClickLabel = "Remove ${p.label}", onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RadiusControl)
                .background(colour)
                .border(1.dp, c.line, RadiusControl),
        )
        Text(p.label, style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = c.text2, maxLines = 1)
    }
}

/** The "+" tile that saves the current colour as a new preset. */
@Composable
private fun AddPresetTile(modifier: Modifier, onClick: () -> Unit) {
    val c = Arva.colors
    Column(
        modifier.clip(RadiusControl).clickable(onClickLabel = "Save current colour", onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RadiusControl).background(c.sunken).border(1.dp, c.line, RadiusControl),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = c.accent)
        }
        Text("Save", style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = c.text2, maxLines = 1)
    }
}

@Composable
private fun AddPresetDialog(defaultName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Arva.colors
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RadiusCard,
        title = { Text("Save preset", color = c.text) },
        text = {
            ArvaField(name, { name = it }, "Name")
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save", color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Arva.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RadiusCard,
        title = { Text(title, color = c.text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = c.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}
