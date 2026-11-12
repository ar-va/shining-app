package com.arva.shining.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arva.shining.LightCommand
import com.arva.shining.PRESETS
import com.arva.shining.R
import com.arva.shining.automation.Automation
import com.arva.shining.automation.GeoPoint
import com.arva.shining.automation.Scheduler
import com.arva.shining.automation.SunTimes
import com.arva.shining.automation.Trigger
import com.arva.shining.lightApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ---------------------------------------------------------------- list

@Composable
fun AutomationsScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val context = LocalContext.current
    val store = context.lightApp.automations
    val items by store.items.collectAsStateWithLifecycle()
    val results by store.results.collectAsStateWithLifecycle()
    val c = Arva.colors
    var exact by remember { mutableStateOf(Scheduler.canScheduleExact(context)) }
    var location by remember { mutableStateOf(store.location) }
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exact = Scheduler.canScheduleExact(context)
        location = store.location
        now = ZonedDateTime.now()
    }
    // Keep "Next: …" current while the screen stays open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = ZonedDateTime.now()
        }
    }

    val needsLocation = items.any { a ->
        a.enabled && (a.trigger is Trigger.Sun || (a.trigger as? Trigger.Arrive)?.afterDark == true)
    }
    val needsExact = items.any { it.enabled && it.trigger !is Trigger.Arrive }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ArvaHeader("Automations", navigation = { HeaderIcon(R.drawable.ic_arrow_left, "Back", onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Automations run on this phone. They work while it's at home on your Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium, color = c.text2,
            )

            if (needsExact && !exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Notice(
                    "Exact timing is off",
                    "Without it, Android may run timed automations a few minutes late.",
                    "Allow exact timing",
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                    )
                }
            }
            if (needsLocation && location == null) {
                LocationNotice { location = it }
            }

            if (items.isEmpty()) {
                ArvaCard(padding = PaddingValues(24.dp)) {
                    Text("No automations yet", style = MaterialTheme.typography.titleLarge, color = c.text)
                    Text(
                        "Add a schedule, a sunset or sunrise trigger, or have the light come on when you get home.",
                        style = MaterialTheme.typography.bodyMedium, color = c.text2,
                    )
                }
            }
            items.forEach { a -> AutomationCard(a, location, now, results[a.id], onClick = { onEdit(a.id) }) }

            PrimaryButton("Add automation", onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth())

            if (location != null && needsLocation) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupLabel("Location for sunset times: approximate", Modifier.weight(1f))
                    LocationButton(label = "Update") { location = it }
                }
            }

            Spacer(Modifier.height(8.dp))
            OtherAppsHelp()
        }
    }
}

@Composable
private fun AutomationCard(a: Automation, location: GeoPoint?, now: ZonedDateTime, result: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    val store = context.lightApp.automations
    val c = Arva.colors
    val next = a.nextRun(now, location)
    val status = when {
        !a.enabled -> "Off"
        a.trigger is Trigger.Arrive -> "When the phone joins your Wi-Fi"
        a.trigger is Trigger.Sun && location == null -> "Needs your location"
        next != null -> "Next: " + next.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        else -> "No upcoming run"
    }
    val icon = when (a.trigger) {
        is Trigger.Sun -> R.drawable.ic_sun
        is Trigger.Arrive -> R.drawable.ic_wifi
        else -> R.drawable.ic_clock
    }
    ArvaCard(onClick = onClick, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TriggerBadge(icon, a.command, enabled = a.enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(a.command.describe(), style = MaterialTheme.typography.titleSmall, color = if (a.enabled) c.text else c.text2)
                Text(a.describeWhen(), style = MaterialTheme.typography.bodyMedium, color = c.text2)
                Text(status, style = MaterialTheme.typography.bodySmall, color = c.text2)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.text2) }
            }
            ArvaSwitch(a.enabled, label = "Enabled") { on ->
                store.save(a.copy(enabled = on))
                Scheduler.rescheduleAll(context)
            }
        }
    }
}

/** A rounded badge: the command's colour behind the trigger-type icon. */
@Composable
private fun TriggerBadge(icon: Int, command: com.arva.shining.LightCommand, enabled: Boolean) {
    val c = Arva.colors
    val colour = commandColour(command)
    val bg = if (enabled && colour != null) colour else c.sunken
    Box(
        Modifier.size(44.dp).clip(RadiusControl).background(bg).border(1.dp, c.line, RadiusControl),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = null,
            tint = if (enabled && colour != null) onColourOf(colour) else c.text2,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The colour a command turns the light, or null for off/neutral. */
private fun commandColour(command: com.arva.shining.LightCommand): Color? {
    if (command.power == com.arva.shining.LightCommand.Power.OFF) return null
    val preset = command.preset?.let { com.arva.shining.findPreset(it) }
    val hue = command.hue?.toFloat() ?: preset?.hue
    val warmth = command.warmth?.let { it / 100f } ?: preset?.temperature
    return when {
        hue != null -> Color.hsv(hue, (command.saturation?.let { it / 100f } ?: preset?.saturation ?: 1f), 1f)
        warmth != null -> whiteColor(warmth)
        else -> null
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    val c = Arva.colors
    Column(
        Modifier.fillMaxWidth().clip(RadiusCard).background(c.sunken).border(1.dp, c.line, RadiusCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.warning)
        Text(body, style = MaterialTheme.typography.bodySmall, color = c.text2)
        TextLink(action, onAction)
    }
}

@Composable
private fun LocationNotice(onLocation: (GeoPoint) -> Unit) {
    val c = Arva.colors
    Column(
        Modifier.fillMaxWidth().clip(RadiusCard).background(c.sunken).border(1.dp, c.line, RadiusCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Location needed for sunset and sunrise", style = MaterialTheme.typography.titleSmall, color = c.warning)
        Text(
            "Shining asks once for your approximate location and keeps a rounded copy on this phone to work out sun times.",
            style = MaterialTheme.typography.bodySmall, color = c.text2,
        )
        LocationButton(label = "Use approximate location", onLocation)
    }
}

/** Requests coarse location once, reads it, and stores a rounded copy. */
@Composable
private fun LocationButton(label: String, onLocation: (GeoPoint) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun fetch() = scope.launch {
        val point = approximateLocation(context)
        if (point == null) {
            Toast.makeText(context, "Could not get a location. Is location turned on?", Toast.LENGTH_LONG).show()
        } else {
            context.lightApp.automations.location = point
            Scheduler.rescheduleAll(context)
            onLocation(point)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetch() else Toast.makeText(context, "Location permission was not granted", Toast.LENGTH_SHORT).show()
    }
    TextLink(label, onClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetch()
        else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    })
}

@SuppressLint("MissingPermission")
private suspend fun approximateLocation(context: Context): GeoPoint? {
    val lm = context.getSystemService(LocationManager::class.java)
    val providers = lm.getProviders(true)
    providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
        ?.let { return GeoPoint(it.latitude, it.longitude) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull() ?: return null
    return withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont ->
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { loc ->
                cont.resume(loc?.let { GeoPoint(it.latitude, it.longitude) })
            }
        }
    }
}

@Composable
private fun OtherAppsHelp() {
    val c = Arva.colors
    ArvaCard(padding = PaddingValues(20.dp)) {
        Text("Use with other apps", style = MaterialTheme.typography.titleLarge, color = c.text)
        Text(
            "Long-press the Shining icon for quick actions. These links work from NFC tags and any app that opens links:",
            style = MaterialTheme.typography.bodySmall, color = c.text2,
        )
        CodeLine("shining://light?power=off")
        CodeLine("shining://light?preset=Warm&brightness=40")
        Text(
            "Tasker or MacroDroid: send a broadcast with action com.arva.shining.action.LIGHT to package com.arva.shining and an extra \"command\" such as:",
            style = MaterialTheme.typography.bodySmall, color = c.text2,
        )
        CodeLine("power=toggle")
        CodeLine("hue=230&saturation=80&brightness=60")
    }
}

@Composable
private fun CodeLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Arva.colors.text,
        modifier = Modifier.fillMaxWidth().clip(RadiusTag).background(Arva.colors.sunken).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---------------------------------------------------------------- editor

private enum class WhenKind(val label: String) { TIME("Time"), SUNSET("Sunset"), SUNRISE("Sunrise"), ARRIVE("Arrive") }
private enum class DoKind(val label: String) { ON("Turn on"), OFF("Turn off"), PRESET("Preset") }

@Composable
fun AutomationEditor(id: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = context.lightApp.automations
    val existing = remember(id) { id?.let { store.get(it) } }
    val c = Arva.colors

    // Draft state, seeded from the automation being edited.
    val t = existing?.trigger
    var kind by rememberSaveable {
        mutableStateOf(
            when (t) {
                is Trigger.Sun -> if (t.event == SunTimes.Event.SUNSET) WhenKind.SUNSET else WhenKind.SUNRISE
                is Trigger.Arrive -> WhenKind.ARRIVE
                else -> WhenKind.TIME
            }
        )
    }
    var time by rememberSaveable { mutableStateOf((t as? Trigger.Time)?.time?.toSecondOfDay() ?: (7 * 3600)) }
    var offset by rememberSaveable { mutableStateOf((t as? Trigger.Sun)?.offsetMinutes ?: 0) }
    var afterDark by rememberSaveable { mutableStateOf((t as? Trigger.Arrive)?.afterDark ?: true) }
    var quietOn by rememberSaveable { mutableStateOf((t as? Trigger.Arrive)?.notAfter != null) }
    var quietAt by rememberSaveable { mutableStateOf((t as? Trigger.Arrive)?.notAfter?.toSecondOfDay() ?: (23 * 3600 + 30 * 60)) }
    var days by rememberSaveable { mutableStateOf((existing?.days ?: DayOfWeek.entries.toSet()).map { it.value }.toSet()) }
    val cmd = existing?.command
    var doKind by rememberSaveable {
        mutableStateOf(
            when {
                cmd == null -> DoKind.PRESET
                cmd.power == LightCommand.Power.OFF -> DoKind.OFF
                cmd.preset != null -> DoKind.PRESET
                else -> DoKind.ON
            }
        )
    }
    var preset by rememberSaveable { mutableStateOf(cmd?.preset ?: "Warm") }
    var brightness by rememberSaveable { mutableStateOf(cmd?.brightness ?: 60) }
    var picking by remember { mutableStateOf<String?>(null) } // "time" | "quiet"

    fun build(): Automation {
        val trigger = when (kind) {
            WhenKind.TIME -> Trigger.Time(LocalTime.ofSecondOfDay(time.toLong()))
            WhenKind.SUNSET -> Trigger.Sun(SunTimes.Event.SUNSET, offset)
            WhenKind.SUNRISE -> Trigger.Sun(SunTimes.Event.SUNRISE, offset)
            WhenKind.ARRIVE -> Trigger.Arrive(afterDark, if (quietOn) LocalTime.ofSecondOfDay(quietAt.toLong()) else null)
        }
        val command = when (doKind) {
            DoKind.ON -> LightCommand(power = LightCommand.Power.ON)
            DoKind.OFF -> LightCommand(power = LightCommand.Power.OFF)
            DoKind.PRESET -> LightCommand(preset = preset, brightness = brightness)
        }
        val base = existing ?: Automation(trigger = trigger, command = command)
        return base.copy(trigger = trigger, command = command, days = days.map { DayOfWeek.of(it) }.toSet())
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ArvaHeader(
            title = if (existing == null) "New automation" else "Edit automation",
            navigation = { HeaderIcon(R.drawable.ic_arrow_left, "Back", onDone) },
        ) {
            if (existing != null) HeaderIcon(R.drawable.ic_trash, "Delete") {
                store.delete(existing.id)
                Scheduler.cancel(context, existing.id)
                Scheduler.rescheduleAll(context)
                onDone()
            }
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            EditorSection("When") {
                Segmented(WhenKind.entries.map { it to it.label }, kind, { kind = it })
                when (kind) {
                    WhenKind.TIME -> SecondaryButton(formatTime(time), onClick = { picking = "time" })
                    WhenKind.SUNSET, WhenKind.SUNRISE -> OffsetStepper(offset) { offset = it }
                    WhenKind.ARRIVE -> {
                        Text(
                            "When the phone joins your Wi-Fi and the light is off. Runs at most once every 30 minutes.",
                            style = MaterialTheme.typography.bodySmall, color = c.text2,
                        )
                        SwitchRow("Only after dark", afterDark) { afterDark = it }
                        SwitchRow("Quiet late at night", quietOn) { quietOn = it }
                        if (quietOn) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GroupLabel("Not from")
                                SecondaryButton(formatTime(quietAt), onClick = { picking = "quiet" })
                                GroupLabel("until 06:00")
                            }
                        }
                    }
                }
                DayToggles(days) { days = it }
            }

            EditorSection("Do") {
                Segmented(DoKind.entries.map { it to it.label }, doKind, { doKind = it })
                if (doKind == DoKind.PRESET) {
                    PresetChooser(preset) { preset = it }
                    val p = PRESETS.firstOrNull { it.label == preset }
                    val colour = if (p?.temperature != null) whiteColor(p.temperature) else Color.hsv(p?.hue ?: 0f, p?.saturation ?: 1f, 1f)
                    ColorSlider(
                        "Brightness", brightness / 100f, "$brightness%",
                        { brightness = (it * 100).roundToInt().coerceIn(1, 100) },
                        Brush.horizontalGradient(listOf(if (c == ArvaDark) c.bg else c.text, colour)),
                    )
                }
            }

            PrimaryButton(
                "Save",
                onClick = {
                    if (days.isEmpty()) {
                        Toast.makeText(context, "Pick at least one day", Toast.LENGTH_SHORT).show()
                    } else {
                        store.save(build())
                        Scheduler.rescheduleAll(context)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    picking?.let { which ->
        TimeDialog(
            initialSeconds = if (which == "time") time else quietAt,
            onDismiss = { picking = null },
            onConfirm = { s -> if (which == "time") time = s else quietAt = s; picking = null },
        )
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Arva.colors.text)
        content()
    }
}

@Composable
private fun OffsetStepper(minutes: Int, onChange: (Int) -> Unit) {
    val c = Arva.colors
    val label = when {
        minutes < 0 -> "${-minutes} min before"
        minutes > 0 -> "$minutes min after"
        else -> "At the moment"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("−15", onClick = { onChange((minutes - 15).coerceAtLeast(-120)) })
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        SecondaryButton("+15", onClick = { onChange((minutes + 15).coerceAtMost(120)) })
    }
}

@Composable
private fun DayToggles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val c = Arva.colors
    val locale = LocalConfiguration.current.locales[0]
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { d ->
            val on = d.value in days
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RadiusControl)
                    .background(if (on) c.accentTint else c.sunken)
                    .border(1.dp, c.line, RadiusControl)
                    .toggleable(value = on, role = Role.Checkbox) { onChange(if (it) days + d.value else days - d.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) c.accent else c.text2,
                )
            }
        }
    }
}

@Composable
private fun PresetChooser(selected: String, onSelect: (String) -> Unit) {
    val c = Arva.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PRESETS.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val on = p.label == selected
                    val colour = if (p.temperature != null) whiteColor(p.temperature) else Color.hsv(p.hue ?: 0f, p.saturation, 1f)
                    Column(
                        Modifier.weight(1f).clip(RadiusControl).clickable(onClickLabel = "Choose ${p.label}") { onSelect(p.label) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(RadiusControl).background(colour)
                                .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RadiusControl),
                        )
                        Text(p.label, style = MaterialTheme.typography.bodySmall, color = if (on) c.accent else c.text2, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Arva.colors.text, modifier = Modifier.weight(1f))
        ArvaSwitch(checked, label, onChange)
    }
}

/** Brand switch: the "on" state uses accent tint with an accent knob. */
@Composable
fun ArvaSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val c = Arva.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.padding(start = 12.dp),
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.accent, checkedTrackColor = c.accentTint, checkedBorderColor = c.accent,
            uncheckedThumbColor = c.text2, uncheckedTrackColor = c.sunken, uncheckedBorderColor = c.line,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialSeconds: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val c = Arva.colors
    val state = rememberTimePickerState(initialHour = initialSeconds / 3600, initialMinute = (initialSeconds % 3600) / 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RadiusCard,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 3600 + state.minute * 60) }) { Text("Set", color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = c.sunken, selectorColor = c.accent, containerColor = c.surface,
                    timeSelectorSelectedContainerColor = c.accentTint, timeSelectorSelectedContentColor = c.accent,
                    timeSelectorUnselectedContainerColor = c.sunken, timeSelectorUnselectedContentColor = c.text,
                    clockDialSelectedContentColor = c.onAccent, clockDialUnselectedContentColor = c.text,
                ),
            )
        },
    )
}

private fun formatTime(secondOfDay: Int) = "%02d:%02d".format(secondOfDay / 3600, (secondOfDay % 3600) / 60)
