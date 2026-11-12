package com.arva.shining.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
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
            "Shining talks to the light over your Wi-Fi. It needs the light's device ID and local key once. The README explains how to get them.",
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
    val lightColor = if (s.colourMode) Color.hsv(s.hue, s.saturation, 1f) else whiteColor(s.temperature)
    // "Dim" end of brightness: the darkest token in each theme (Ink in light, Night in dark).
    val dim = if (c == ArvaDark) c.bg else c.text
    val preview by animateColorAsState(
        if (s.on) lightColor.copy(alpha = 0.35f + 0.65f * s.brightness).compositeOver(dim) else c.sunken,
        animationSpec = tween(200),
        label = "preview",
    )

    // Title block
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Lightstrip")
        Text(s.name, style = MaterialTheme.typography.headlineMedium, color = c.text)
        Text(
            text = when {
                s.error != null -> s.error
                !s.connected -> "Connecting"
                s.on -> "On · ${(s.brightness * 100).roundToInt()}%"
                else -> "Off"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (s.error != null) c.error else c.text2,
        )
    }

    // Preview + the one primary action
    ArvaCard {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.4f)
                .clip(RadiusCard)
                .background(preview)
                .border(1.dp, c.line, RadiusCard)
                .semantics { contentDescription = if (s.on) "Current light colour" else "Light is off" },
            contentAlignment = Alignment.Center,
        ) {
            if (!s.on) Text("Off", style = MaterialTheme.typography.bodySmall, color = c.text2)
        }
        PrimaryButton(if (s.on) "Turn off" else "Turn on", vm::togglePower, Modifier.fillMaxWidth())
    }

    // Adjustments
    ArvaCard {
        Segmented(
            options = listOf(true to "Colour", false to "White"),
            selected = s.colourMode,
            onSelect = vm::setColourMode,
        )
        if (s.colourMode) {
            ColorSlider("Colour", s.hue / 360f, "${s.hue.roundToInt()}°", { vm.setHue(it * 360f) }, HueTrack)
            ColorSlider(
                "Saturation", s.saturation, "${(s.saturation * 100).roundToInt()}%", vm::setSaturation,
                Brush.horizontalGradient(listOf(Color.White, Color.hsv(s.hue, 1f, 1f))),
            )
        } else {
            ColorSlider(
                "Warmth", s.temperature, if (s.temperature < 0.5f) "Warm" else "Cool", vm::setTemperature, WarmthTrack,
            )
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
