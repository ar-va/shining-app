package com.arva.shining.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.arva.shining.R
import com.arva.shining.UiState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The light's hero: a tall vertical capsule that fills from the bottom with the light's real colour,
 * its height showing brightness. Tap anywhere on it to turn the light on or off (no separate button);
 * drag up/down to set brightness by position. When off it relaxes to a soft rectangle and when on it
 * rounds into a pill — the shape and fill animate on toggle, with a stronger haptic than a brightness
 * step. A small percentage bubble fades in beside the fill while adjusting; the first touch and every
 * 1% give a light haptic. The colour is content (the brand's deliberate exception); chrome is on-brand.
 */
@Composable
fun BrightnessCapsule(s: UiState, onToggle: () -> Unit, onBrightness: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Arva.colors
    val view = LocalView.current
    val base = lightColourOf(s)
    val fill = if (s.on) base else c.sunken
    val track = if (s.on) lerp(base, c.bg, 0.86f) else c.sunken
    val onFill = if (s.on) onColourOf(base) else c.text2
    val targetFraction = if (s.on) s.brightness.coerceIn(0.05f, 1f) else 0f
    val pct = (s.brightness * 100).roundToInt().coerceIn(0, 100)

    var heightPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var lastPct by remember { mutableIntStateOf(pct) }
    val density = LocalDensity.current

    // Fill follows the finger exactly while dragging (no lag); it eases when toggling on/off.
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (dragging) 0 else motionMs(300)),
        label = "fill",
    )
    // Rectangular when off, a full pill when on — morphs on toggle.
    val corner by animateDpAsState(
        targetValue = if (s.on) OnRadius else OffRadius,
        animationSpec = tween(motionMs(300)),
        label = "corner",
    )
    val shape = RoundedCornerShape(corner)

    fun setFromY(y: Float) {
        val f = (1f - y / heightPx).coerceIn(0f, 1f)
        val p = (f * 100).roundToInt()
        if (p != lastPct) { view.hapticSelect(); lastPct = p }
        onBrightness(f)
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // The capsule itself, centred.
        Box(
            Modifier
                .width(CapsuleWidth)
                .height(CapsuleHeight)
                .clip(shape)
                .background(track)
                .border(1.dp, c.line, shape)
                .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> dragging = true; view.hapticTick(); setFromY(offset.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); setFromY(change.position.y) }
                }
                .pointerInput(Unit) { detectTapGestures { onToggle() } }
                .semantics {
                    contentDescription = "Light"
                    stateDescription = if (s.on) "On, $pct%" else "Off"
                    progressBarRangeInfo = ProgressBarRangeInfo(s.brightness, 0f..1f)
                    setProgress { onBrightness(it.coerceIn(0f, 1f)); true }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Filled portion = brightness, with a soft emission highlight at the top of the fill.
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .background(
                        if (s.on) Brush.verticalGradient(listOf(lerp(fill, Color.White, 0.14f), fill))
                        else Brush.verticalGradient(listOf(fill, fill)),
                    ),
            )
            Icon(
                painterResource(R.drawable.ic_brightness),
                contentDescription = null,
                tint = onFill,
                modifier = Modifier.padding(bottom = 20.dp).size(22.dp),
            )
        }

        // Percentage bubble, fading in beside the fill line while adjusting.
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(motionMs(120))) + scaleIn(tween(motionMs(120)), initialScale = 0.8f),
            exit = fadeOut(tween(motionMs(160))) + scaleOut(tween(motionMs(160)), targetScale = 0.8f),
            modifier = Modifier.offset {
                val areaH = heightPx
                val fillLineY = (1f - fraction) * areaH
                val bubbleH = with(density) { 34.dp.toPx() }
                val x = with(density) { (CapsuleWidth.toPx() / 2 + 28.dp.toPx()) }
                IntOffset(
                    x.roundToInt(),
                    (fillLineY - bubbleH / 2).roundToInt().coerceIn(0, (areaH - bubbleH).roundToInt().coerceAtLeast(0)),
                )
            },
        ) {
            Box(
                Modifier
                    .widthIn(min = 52.dp)
                    .clip(RadiusTag)
                    .background(c.surface)
                    .border(1.dp, c.line, RadiusTag)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$pct%", style = MaterialTheme.typography.labelSmall, color = c.text)
            }
        }
    }
}

// Google-Home-ish proportions: a wide, comfortably tall pill (not a thin bar), centred with margins.
private val CapsuleWidth = 184.dp
private val CapsuleHeight = 400.dp
private val OnRadius = 92.dp
private val OffRadius = 36.dp

/**
 * Brightness read-out and fine control: the percentage (tap to type an exact value) flanked by − / +
 * steppers that nudge 1% at a time. Each step and the first touch give haptic feedback.
 */
@Composable
fun BrightnessStepper(s: UiState, onBrightness: (Float) -> Unit) {
    val c = Arva.colors
    val view = LocalView.current
    val pct = (s.brightness * 100).roundToInt().coerceIn(1, 100)
    var typing by remember { mutableStateOf(false) }
    fun step(delta: Int) { view.hapticSelect(); onBrightness(((pct + delta).coerceIn(1, 100)) / 100f) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StepButton("−", enabled = pct > 1) { step(-1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$pct%",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = PlexMono),
                color = c.text,
                modifier = Modifier.clip(RadiusControl).clickable(onClickLabel = "Type a brightness value") { view.hapticTick(); typing = true }.padding(horizontal = 12.dp, vertical = 2.dp),
            )
            GroupLabel("Brightness")
        }
        StepButton("+", enabled = pct < 100) { step(1) }
    }

    if (typing) {
        BrightnessTypeDialog(
            current = pct,
            onDismiss = { typing = false },
            onSet = { value -> onBrightness(value / 100f); typing = false },
        )
    }
}

@Composable
private fun StepButton(sign: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Arva.colors
    Box(
        Modifier
            .size(52.dp)
            .clip(RadiusControl)
            .background(c.sunken)
            .border(1.dp, c.line, RadiusControl)
            .clickable(enabled = enabled, onClickLabel = if (sign == "+") "Brighter" else "Dimmer", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, style = MaterialTheme.typography.headlineSmall, color = if (enabled) c.accent else c.text2)
    }
}

@Composable
private fun BrightnessTypeDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val c = Arva.colors
    var text by remember { mutableStateOf(current.toString()) }
    fun commit() { text.toIntOrNull()?.let { onSet(it.coerceIn(1, 100)) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RadiusCard,
        title = { Text("Set brightness", color = c.text) },
        text = {
            ArvaField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                label = "Percent",
                mono = true,
                keyboardType = KeyboardType.Number,
            )
        },
        confirmButton = { TextButton(onClick = { commit() }) { Text("Set", color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.text2) } },
    )
}

/**
 * The colour / temperature picker, shown as a dialog from the palette tile (Google-Home style): a
 * Colour / Temperature switch over the 2-D wheel or the warmth gradient. Changes apply live to the
 * light; "Done" closes it.
 */
@Composable
fun ColourPickerDialog(
    s: UiState,
    onColourMode: (Boolean) -> Unit,
    onHsv: (Float, Float) -> Unit,
    onTemperature: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Arva.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RadiusCard,
        title = { Text("Colour", style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Segmented(listOf(true to "Colour", false to "Temperature"), s.colourMode, onColourMode)
                if (s.colourMode) {
                    ColourWheel(s.hue, s.saturation, onHsv)
                } else {
                    ColorSlider("Temperature", s.temperature, warmthName(s.temperature), onTemperature, WarmthTrack)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = c.accent) } },
    )
}

/**
 * A 2-D colour picker: hue around the wheel, saturation from the white centre to the saturated edge.
 * The wheel shows the strip's real colours (content); the thumb is on-brand chrome. Drag or tap to pick.
 */
@Composable
fun ColourWheel(hue: Float, saturation: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Arva.colors
    val view = LocalView.current
    val hueColours = remember { (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) } }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(WheelSize)
                .pointerInput(Unit) {
                    detectTapGestures { pos -> emit(pos, size.width.toFloat(), size.height.toFloat(), onChange) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos -> view.hapticTick(); emit(pos, size.width.toFloat(), size.height.toFloat(), onChange) },
                    ) { change, _ -> change.consume(); emit(change.position, size.width.toFloat(), size.height.toFloat(), onChange) }
                },
        ) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(hueColours, centre), r, centre)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, r), r, centre)

            // Thumb at (hue angle, saturation radius).
            val a = Math.toRadians(hue.toDouble())
            val dist = saturation.coerceIn(0f, 1f) * r
            val tx = centre.x + (cos(a) * dist).toFloat()
            val ty = centre.y + (sin(a) * dist).toFloat()
            val selected = Color.hsv(((hue % 360) + 360) % 360, saturation.coerceIn(0f, 1f), 1f)
            drawCircle(selected, 11.dp.toPx(), Offset(tx, ty))
            drawCircle(Color.White, 11.dp.toPx(), Offset(tx, ty), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        }
        // A hairline ring to seat the wheel in the card (brand 1dp line).
        Box(Modifier.size(WheelSize).clip(CircleShape).border(1.dp, c.line, CircleShape))
    }
}

private val WheelSize = 236.dp

/** Maps a touch point on the wheel to (hue, saturation) and reports it. */
private fun emit(pos: Offset, w: Float, h: Float, onChange: (Float, Float) -> Unit) {
    val cx = w / 2f
    val cy = h / 2f
    val r = minOf(w, h) / 2f
    val dx = pos.x - cx
    val dy = pos.y - cy
    val sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
    var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    if (hue < 0f) hue += 360f
    onChange(hue, sat)
}
