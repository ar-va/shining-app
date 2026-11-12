package com.arva.shining.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arva.shining.MainViewModel
import com.arva.shining.PRESETS
import com.arva.shining.Preset
import com.arva.shining.R
import com.arva.shining.UiState
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenAutomations: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Arva.colors
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
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!s.configured) SetupPrompt(onOpenSettings) else Controls(s, vm)
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
private fun Controls(s: UiState, vm: MainViewModel) {
    val c = Arva.colors

    // Title + a quiet live status chip.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.name, style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
        StatusChip(s)
    }

    LightHero(s, onToggle = vm::togglePower)

    // Adjustments
    ArvaCard {
        Segmented(listOf(true to "Colour", false to "White"), s.colourMode, vm::setColourMode)
        val dim = if (c == ArvaDark) c.bg else c.text
        val lightColor = lightColourOf(s)
        if (s.colourMode) {
            ColorSlider("Colour", s.hue / 360f, colourName(s.hue), { vm.setHue(it * 360f) }, HueTrack)
            ColorSlider(
                "Saturation", s.saturation, "${(s.saturation * 100).roundToInt()}%", vm::setSaturation,
                Brush.horizontalGradient(listOf(Color.White, Color.hsv(s.hue, 1f, 1f))),
            )
        } else {
            ColorSlider("Warmth", s.temperature, warmthName(s.temperature), vm::setTemperature, WarmthTrack)
        }
        ColorSlider(
            "Brightness", s.brightness, "${(s.brightness * 100).roundToInt()}%", vm::setBrightness,
            Brush.horizontalGradient(listOf(dim, lightColor)),
        )
    }

    // Presets
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupLabel("Presets")
        PRESETS.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p -> PresetSwatch(p, Modifier.weight(1f)) { vm.applyPreset(p) } }
            }
        }
    }
}

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

/** The living colour hero: fills with the light's colour, tap to toggle, state shown inside. */
@Composable
private fun LightHero(s: UiState, onToggle: () -> Unit) {
    val c = Arva.colors
    val dim = if (c == ArvaDark) c.bg else c.text
    val base = lightColourOf(s)
    // Modulate luminance by brightness so a dim light looks dim.
    val litTarget = lerp(dim, base, 0.45f + 0.55f * s.brightness.coerceIn(0f, 1f))
    val fill by animateColorAsState(if (s.on) litTarget else c.sunken, tween(260), label = "heroFill")
    val onFill = if (s.on) onColourOf(fill) else c.text2

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "heroPress")

    ArvaCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.9f)
                .scale(pressScale)
                .clip(RadiusCard)
                .background(if (s.on) Brush.radialGradient(listOf(lerp(fill, Color.White, 0.16f), fill)) else Brush.linearGradient(listOf(fill, fill)))
                .border(1.dp, c.line, RadiusCard)
                .clickable(interactionSource = interaction, indication = null, role = Role.Switch, onClickLabel = if (s.on) "Turn off" else "Turn on", onClick = onToggle)
                .padding(16.dp)
                .semantics {
                    contentDescription = "Light"
                    stateDescription = if (s.on) stateDescriptor(s) else "Off"
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = when {
                    !s.connected && s.error == null -> "Connecting…"
                    s.on -> stateDescriptor(s)
                    else -> "Off"
                },
                style = MaterialTheme.typography.titleSmall,
                color = onFill,
            )
        }
        PrimaryButton(if (s.on) "Turn off" else "Turn on", onToggle, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PresetSwatch(p: Preset, modifier: Modifier, onClick: () -> Unit) {
    val c = Arva.colors
    val colour = if (p.temperature != null) whiteColor(p.temperature) else Color.hsv(p.hue ?: 0f, p.saturation, 1f)
    Column(
        modifier
            .clip(RadiusControl)
            .clickable(onClickLabel = "Set ${p.label}", onClick = onClick)
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
