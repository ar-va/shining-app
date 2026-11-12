package com.arva.shining.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arva.shining.R
import com.arva.shining.SettingsViewModel
import com.arva.tuya.DeviceConfig
import com.arva.tuya.LightKind
import com.arva.tuya.TuyaVersion

@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onSave: (DeviceConfig) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Arva.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ArvaHeader(title = "Light setup", navigation = { HeaderIcon(R.drawable.ic_arrow_left, "Back", onBack) })
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Light type
            SettingCard("Light type") {
                Segmented(listOf(LightKind.TUYA to "Tuya", LightKind.OPENBEKEN to "OpenBeken"), s.kind, vm::setKind)
                Text(
                    if (s.isOpenBeken) "After flashing open firmware. Shining talks to it over local HTTP — it needs only the IP."
                    else "Stock firmware over the Tuya LAN protocol.",
                    style = MaterialTheme.typography.bodySmall, color = c.text2,
                )
            }

            // Find on Wi-Fi (Tuya only)
            if (!s.isOpenBeken) {
                SettingCard("Find it") {
                    Text(
                        "Scan the setup QR with your camera, paste the setup link, or find the light on Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium, color = c.text2,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(if (s.scanning) "Listening…" else "Scan Wi-Fi", vm::scan, enabled = !s.scanning)
                        TextLink("Paste link", onClick = {
                            if (!vm.pasteLink(clipboard.getText()?.text)) {
                                Toast.makeText(context, "The clipboard has no shining:// setup link", Toast.LENGTH_SHORT).show()
                            }
                        })
                        if (s.scanning) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    }
                    s.scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2) }
                    s.found.forEachIndexed { i, d ->
                        if (i == 0) HorizontalDivider(color = c.line)
                        Row(
                            Modifier.fillMaxWidth().clip(RadiusControl).clickable { vm.pick(d) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(d.ip, style = MaterialTheme.typography.titleSmall, color = c.text)
                                Text("${d.id} · v${d.version}", style = MaterialTheme.typography.labelSmall, color = c.text2)
                            }
                            Text("Use", style = MaterialTheme.typography.bodyMedium, color = c.accent)
                        }
                    }
                }
            }

            // Device details
            SettingCard("Device") {
                ArvaField(s.name, { v -> vm.edit { it.copy(name = v) } }, "Name")
                ArvaField(
                    s.ip, { v -> vm.edit { it.copy(ip = v) } },
                    if (s.isOpenBeken) "IP address (from the OpenBeken page)" else "IP address",
                    mono = true, keyboardType = KeyboardType.Uri,
                )
                if (!s.isOpenBeken) {
                    ArvaField(s.id, { v -> vm.edit { it.copy(id = v) } }, "Device ID", mono = true)
                    ArvaField(
                        s.key, { v -> vm.edit { it.copy(key = v) } }, "Local key", mono = true,
                        isError = s.key.isNotEmpty() && s.key.length != 16,
                        supporting = "${s.key.length} of 16 characters",
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GroupLabel("Protocol version")
                        Segmented(TuyaVersion.entries.map { it to it.text }, s.version, { v -> vm.edit { it.copy(version = v) } })
                    }
                }
            }

            // Test result (shown above the action bar so it's visible once produced)
            s.testResult?.let { result ->
                val lines = result.lines()
                Column(
                    Modifier.fillMaxWidth().clip(RadiusCard).background(c.sunken).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        lines.first(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.startsWith("Failed")) c.error else c.success,
                    )
                    lines.drop(1).forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.text) }
                }
            }

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Test", vm::test, enabled = !s.testing)
                if (s.testing) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                Box(Modifier.weight(1f))
                PrimaryButton("Save", onClick = {
                    val cfg = vm.save()
                    if (cfg == null) {
                        Toast.makeText(context, if (s.isOpenBeken) "Enter the light's IP" else "Fill in the IP, device ID and a 16-character key", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(cfg)
                    }
                })
            }
        }
    }
}

/** A titled card: Archivo heading + content, in the brand card style. */
@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ArvaCard(padding = PaddingValues(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Arva.colors.text)
        content()
    }
}
