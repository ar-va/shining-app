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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arva.shining.BuildConfig
import com.arva.shining.MainViewModel
import com.arva.shining.Updater
import com.arva.shining.Preset
import com.arva.shining.R
import com.arva.shining.UiState

@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenAutomations: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Arva.colors

    // Quiet, one-shot update check on open: if GitHub has a newer release, offer it (dismissable).
    var updateVersion by remember { mutableStateOf<String?>(null) }
    var updateDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Updater.checkLatest()?.let { if (Updater.isNewer(it.version, BuildConfig.VERSION_NAME)) updateVersion = it.version }
    }

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

    // Opt-in colour wash: a faint bloom of the light's colour behind the whole screen (off by default).
    val washOn by LocalContext.current.lightApp.themeStore.wash.collectAsState()
    // Transient "Turned off · Undo" bar after switching the light off from the capsule.
    var undoShow by remember { mutableStateOf(false) }
    LaunchedEffect(undoShow) { if (undoShow) { delay(4_000); undoShow = false } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (washOn && s.on && s.configured) ColourWash(lightColourOf(s))
        Column(Modifier.fillMaxSize()) {
            ArvaHeader("Shining", divider = false) {
                if (s.configured) {
                    HeaderIcon(R.drawable.ic_refresh, "Refresh", vm::refresh)
                    HeaderIcon(R.drawable.ic_clock, "Automations", onOpenAutomations)
                }
                HeaderIcon(R.drawable.ic_settings, "Light setup", onOpenSettings)
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg)
            }
            updateVersion?.takeIf { !updateDismissed }?.let { v -> UpdateBanner(v, onOpenSettings) { updateDismissed = true } }
            if (!s.configured) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) { SetupPrompt(onOpenSettings) }
            } else {
                Controls(s, vm, onPoweredOff = { undoShow = true })
            }
        }
        if (undoShow) {
            UndoBar(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                onUndo = { vm.togglePower(); undoShow = false },
            )
        }
    }
}

/** A faint radial of the light's colour behind the screen (the opt-in colour wash). */
@Composable
private fun ColourWash(colour: Color) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.34f)
        val radius = size.minDimension * 0.95f
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                colors = listOf(colour.copy(alpha = 0.12f), Color.Transparent),
                center = centre, radius = radius,
            ),
            radius = radius, center = centre,
        )
    }
}

/** Transient bottom bar with an Undo action, shown after turning the light off. */
@Composable
private fun UndoBar(modifier: Modifier, onUndo: () -> Unit) {
    val c = Arva.colors
    Row(
        modifier
            .clip(RadiusTag)
            .background(c.surface)
            .border(1.dp, c.line, RadiusTag)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Turned off", style = MaterialTheme.typography.bodyMedium, color = c.text)
        Text(
            "Undo",
            style = MaterialTheme.typography.labelLarge,
            color = c.accent,
            modifier = Modifier.clip(RadiusControl).clickable(onClickLabel = "Undo", onClick = onUndo).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A slim banner offering an available update; tap to open Settings' Updates card, or dismiss. */
@Composable
private fun UpdateBanner(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = Arva.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.accent.copy(alpha = 0.12f))
            .clickable(onClickLabel = "Open update", onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Shining $version is available", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
        Text("Update", style = MaterialTheme.typography.bodyMedium, color = c.accent)
        Text("✕", style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.clickable(onClickLabel = "Dismiss", onClick = onDismiss).padding(start = 4.dp))
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
private fun ColumnScope.Controls(s: UiState, vm: MainViewModel, onPoweredOff: () -> Unit) {
    val c = Arva.colors
    val view = LocalView.current
    // Tapping the capsule turns the light on/off (no separate button), with a stronger toggle haptic.
    val toggle = {
        val goingOff = s.on
        view.hapticToggle(!s.on); vm.togglePower()
        if (goingOff) onPoweredOff()
    }
    var picking by remember { mutableStateOf(false) }

    // Everything fits on one screen (no page scroll). Name + status at the top, the capsule fills the
    // flexible middle, then the brightness read-out and a horizontally scrolling preset row.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
            StatusChip(s, onRetry = vm::refresh)
        }
    }

    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        BrightnessCapsule(
            s, onToggle = toggle, onBrightness = vm::setBrightness,
            // Long-press the capsule for instant full brightness.
            onLongPress = { view.hapticTick(); vm.setBrightness(1f) },
        )
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrightnessStepper(s, onBrightness = vm::setBrightness)
    }

    RecentsRow(vm)
    PresetsRow(s, vm, onOpenPicker = { view.hapticSelect(); picking = true })

    if (picking) {
        ColourPickerDialog(
            s,
            onColourMode = vm::setColourMode,
            onHsv = vm::setHsv,
            onTemperature = vm::setTemperature,
            onDismiss = { vm.rememberCurrentColour(); picking = false },
        )
    }
}

/** Small row of recently picked colours (session-scoped) for a quick snap-back. Hidden when empty. */
@Composable
private fun RecentsRow(vm: MainViewModel) {
    val recent by vm.recent.collectAsStateWithLifecycle()
    if (recent.isEmpty()) return
    val c = Arva.colors
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupLabel("Recent", Modifier.padding(end = 4.dp))
        recent.forEach { hsv ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.hsv(hsv.h, hsv.s, 1f))
                    .border(1.dp, c.line, CircleShape)
                    .clickable(onClickLabel = "Use recent colour") { view.hapticSelect(); vm.setHsv(hsv.h, hsv.s) },
            )
        }
    }
}

/**
 * A single horizontally scrolling row of round swatches (Google-Home style): the colour/temperature
 * picker first, then the presets, then a "+" to save the current colour. Tap a preset to apply it,
 * long-press to remove it. The row sits at the bottom, above the navigation bar.
 */
@Composable
private fun PresetsRow(s: UiState, vm: MainViewModel, onOpenPicker: () -> Unit) {
    val context = LocalContext.current
    val store = context.lightApp.presetStore
    val presets by store.items.collectAsStateWithLifecycle()
    val view = LocalView.current
    var removing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerDot(onClick = onOpenPicker)
        presets.forEachIndexed { idx, p ->
            PresetDot(
                p,
                onClick = { view.hapticSelect(); vm.applyPreset(p) },
                onLongClick = { view.hapticTick(); removing = idx },
            )
        }
        AddDot { view.hapticSelect(); adding = true }
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

private val DotSize = 60.dp

/** Small pill shown only when there's something to say (updating / offline). Tap when offline to retry. */
@Composable
private fun StatusChip(s: UiState, onRetry: () -> Unit) {
    val c = Arva.colors
    val offline = s.error != null || !s.connected
    val (dot, label) = when {
        s.loading -> c.accent to "Updating"
        offline -> c.error to "Offline · retry"
        else -> return
    }
    Row(
        Modifier
            .clip(RadiusTag)
            .background(c.sunken)
            .then(if (offline && !s.loading) Modifier.clickable(onClickLabel = "Retry connection", onClick = onRetry) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.text2)
    }
}

/** A round preset swatch in the light's real colour: tap to apply, long-press to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetDot(p: Preset, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Arva.colors
    val colour = if (p.temperature != null) whiteColor(p.temperature) else Color.hsv(p.hue ?: 0f, p.saturation, 1f)
    Box(
        Modifier
            .size(DotSize)
            .clip(CircleShape)
            .background(colour)
            .border(1.dp, c.line, CircleShape)
            .combinedClickable(onClickLabel = "Set ${p.label}", onLongClickLabel = "Remove ${p.label}", onClick = onClick, onLongClick = onLongClick),
    )
}

/** The first dot: opens the colour / temperature picker. */
@Composable
private fun PickerDot(onClick: () -> Unit) {
    val c = Arva.colors
    Box(
        Modifier
            .size(DotSize)
            .clip(CircleShape)
            .background(c.sunken)
            .border(1.dp, c.line, CircleShape)
            .clickable(onClickLabel = "Pick a colour", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_palette), contentDescription = null, tint = c.accent, modifier = Modifier.size(24.dp))
    }
}

/** The "+" dot that saves the current colour as a new preset. */
@Composable
private fun AddDot(onClick: () -> Unit) {
    val c = Arva.colors
    Box(
        Modifier
            .size(DotSize)
            .clip(CircleShape)
            .background(c.sunken)
            .border(1.dp, c.line, CircleShape)
            .clickable(onClickLabel = "Save current colour", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = MaterialTheme.typography.headlineMedium, color = c.accent)
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
