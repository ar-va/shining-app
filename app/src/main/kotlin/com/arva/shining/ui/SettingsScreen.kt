package com.arva.shining.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.arva.tuya.TuyaVersion

@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onSave: (DeviceConfig) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Arva.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ArvaHeader(
            title = "Light setup",
            navigation = { HeaderIcon(R.drawable.ic_arrow_left, "Back", onBack) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // 1. Find
            Section("Step 1", "Find the light") {
                Text(
                    "Listens for the light's broadcast on this Wi-Fi for 8 seconds.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(if (s.scanning) "Listening" else "Scan Wi-Fi", vm::scan, enabled = !s.scanning)
                    if (s.scanning) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                }
                s.scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2) }
                s.found.forEach { d ->
                    ArvaCard(onClick = { vm.pick(d) }, padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(d.ip, style = MaterialTheme.typography.titleSmall, color = c.text)
                            Text("${d.id} · v${d.version}", style = MaterialTheme.typography.labelSmall, color = c.text2)
                        }
                    }
                }
            }

            // 2. Details
            Section("Step 2", "Device details") {
                Text(
                    "Scan the QR code from tools/setup_qr.py with the camera, paste the setup link, or type the values.",
                    style = MaterialTheme.typography.bodyMedium, color = c.text2,
                )
                TextLink("Paste setup link", onClick = {
                    if (!vm.pasteLink(clipboard.getText()?.text)) {
                        Toast.makeText(context, "The clipboard has no shining:// setup link", Toast.LENGTH_SHORT).show()
                    }
                })
                ArvaField(s.name, { v -> vm.edit { it.copy(name = v) } }, "Name")
                ArvaField(s.ip, { v -> vm.edit { it.copy(ip = v) } }, "IP address", mono = true, keyboardType = KeyboardType.Uri)
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

            // 3. Test and save
            Section("Step 3", "Test and save") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Test", vm::test, enabled = !s.testing)
                    PrimaryButton("Save", onClick = {
                        val cfg = vm.save()
                        if (cfg == null) {
                            Toast.makeText(context, "Fill in the IP, device ID and a 16-character key", Toast.LENGTH_SHORT).show()
                        } else {
                            onSave(cfg)
                        }
                    })
                    if (s.testing) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                }
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
            }
        }
    }
}

@Composable
private fun Section(eyebrow: String, title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(eyebrow)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Arva.colors.text)
        }
        content()
    }
}
