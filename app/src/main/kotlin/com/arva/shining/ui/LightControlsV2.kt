package com.arva.shining.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.arva.shining.R
import com.arva.shining.UiState
import com.arva.shining.lightApp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The light itself: a tall capsule filling from the bottom with the light's real colour, its height
 * showing brightness. Tap to switch on or off, drag to set brightness, hold for full. Off it relaxes
 * to a soft rectangle; on it rounds into a pill. The colour is content (the brand's exception for the
 * light's own colours); everything around it stays on tokens.
 */
@Composable
fun BrightnessCapsule(
    s: UiState,
    onToggle: () -> Unit,
    onBrightness: (Float) -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = Arva.colors
    val view = LocalView.current
    val density = LocalDensity.current
    val glowOn by LocalContext.current.lightApp.themeStore.glow.collectAsState()
    // The gesture blocks below outlive a recomposition, so they must read the current callbacks.
    val toggle by rememberUpdatedState(onToggle)
    val setBrightness by rememberUpdatedState(onBrightness)
    val longPress by rememberUpdatedState(onLongPress)
    val base = lightColourOf(s)
    val track = if (s.on) lerp(base, c.bg, 0.86f) else c.sunken
    val targetFraction = if (s.on) s.brightness.coerceIn(0.05f, 1f) else 0f
    val pct = (s.brightness * 100).roundToInt().coerceIn(0, 100)

    var heightPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var lastPct by remember { mutableIntStateOf(pct) }

    // The fill follows the finger exactly while dragging; it eases when switching on or off.
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(if (dragging) 0 else motionMs(MotionMs), easing = ArvaEase),
        label = "fill",
    )

    fun setFromY(y: Float) {
        val f = (1f - y / heightPx).coerceIn(0f, 1f)
        val p = (f * 100).roundToInt()
        if (p != lastPct) { view.hapticSelect(); lastPct = p }
        setBrightness(f)
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Keeps its proportions, and gives way on a short screen instead of overlapping the controls.
        val capsuleHeight: Dp = minOf(CapsuleHeight, maxHeight - 16.dp).coerceAtLeast(160.dp)
        val capsuleWidth: Dp = minOf(CapsuleWidth, capsuleHeight * 0.46f)
        val corner by animateDpAsState(
            targetValue = if (s.on) capsuleWidth / 2 else OffRadius,
            animationSpec = tween(motionMs(MotionMs), easing = ArvaEase),
            label = "corner",
        )
        val shape = RoundedCornerShape(corner)

        Box(Modifier.fillMaxWidth().height(capsuleHeight), contentAlignment = Alignment.TopCenter) {
            // Ambient glow (opt-in, off by default): the owner's choice, against the brand's calm rule.
            if (glowOn && s.on) {
                val breathe = if (animationsOn()) {
                    val t = rememberInfiniteTransition(label = "glow")
                    t.animateFloat(
                        0.85f, 1f,
                        infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
                        label = "breathe",
                    ).value
                } else 0.92f
                // matchParentSize (not fillMaxSize): fills without growing the box, so the capsule
                // never shifts when the glow appears or goes.
                Canvas(Modifier.matchParentSize()) {
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.height * 0.56f * breathe
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(base.copy(alpha = 0.16f * breathe), Color.Transparent),
                            center = centre, radius = radius,
                        ),
                        radius = radius, center = centre,
                    )
                }
            }
            Box(
                Modifier
                    .width(capsuleWidth)
                    .fillMaxHeight()
                    .arvaFocus(shape)
                    .onKeyEvent { e ->
                        val activate = e.type == KeyEventType.KeyUp &&
                            e.key in setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)
                        if (activate) toggle()
                        activate
                    }
                    .focusable()
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
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { longPress() }, onTap = { toggle() }) }
                    .semantics {
                        contentDescription = "Light"
                        stateDescription = if (s.on) "On, $pct%" else "Off"
                        progressBarRangeInfo = ProgressBarRangeInfo(s.brightness, 0f..1f)
                        setProgress { onBrightness(it.coerceIn(0f, 1f)); true }
                        onClick(label = if (s.on) "Turn off" else "Turn on") { toggle(); true }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(fraction).background(if (s.on) base else c.sunken))
                // The icon sits on the fill only once the fill actually reaches it.
                val onFill = s.on && capsuleHeight * fraction > 48.dp
                Icon(
                    painterResource(R.drawable.ic_brightness),
                    contentDescription = null,
                    tint = if (onFill) onColourOf(base) else c.text2,
                    modifier = Modifier.padding(bottom = 24.dp).size(24.dp),
                )
            }

            // The percentage beside the fill line, while adjusting.
            val slide = with(density) { 8.dp.roundToPx() }
            AnimatedVisibility(
                visible = dragging,
                enter = fadeIn(tween(motionMs(150), easing = ArvaEase)) + slideInHorizontally(tween(motionMs(150), easing = ArvaEase)) { -slide },
                exit = fadeOut(tween(motionMs(150), easing = ArvaEase)) + slideOutHorizontally(tween(motionMs(150), easing = ArvaEase)) { -slide },
                modifier = Modifier.offset {
                    val areaH = heightPx
                    val fillLineY = (1f - fraction) * areaH
                    val bubbleH = BubbleHeight.toPx()
                    val x = capsuleWidth.toPx() / 2 + 48.dp.toPx()
                    IntOffset(
                        x.roundToInt(),
                        (fillLineY - bubbleH / 2).roundToInt().coerceIn(0, (areaH - bubbleH).roundToInt().coerceAtLeast(0)),
                    )
                },
            ) {
                Box(
                    Modifier
                        .height(BubbleHeight)
                        .widthIn(min = 48.dp)
                        .clip(RadiusTag)
                        .background(c.surface)
                        .border(1.dp, c.line, RadiusTag)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$pct%", style = MaterialTheme.typography.labelSmall, color = c.text)
                }
            }
        }
    }
}

private val CapsuleWidth = 184.dp
private val CapsuleHeight = 400.dp
private val OffRadius = 32.dp
private val BubbleHeight = 32.dp
private const val MotionMs = 220

/** The percentage (tap to type an exact value) between − and + buttons that nudge 1% at a time. */
@Composable
fun BrightnessStepper(s: UiState, onBrightness: (Float) -> Unit) {
    val c = Arva.colors
    val view = LocalView.current
    val pct = (s.brightness * 100).roundToInt().coerceIn(1, 100)
    var typing by remember { mutableStateOf(false) }
    fun step(delta: Int) { view.hapticSelect(); onBrightness(((pct + delta).coerceIn(1, 100)) / 100f) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StepButton(R.drawable.ic_minus, "Dimmer", enabled = pct > 1) { step(-1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$pct%",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = PlexMono, fontWeight = FontWeight.Normal),
                color = c.text,
                modifier = Modifier
                    .arvaFocus()
                    .clip(RadiusControl)
                    .clickable(onClickLabel = "Type a brightness value") { view.hapticTick(); typing = true }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            GroupLabel("Brightness")
        }
        StepButton(R.drawable.ic_plus, "Brighter", enabled = pct < 100) { step(1) }
    }

    if (typing) {
        BrightnessTypeDialog(
            current = pct,
            onDismiss = { typing = false },
            onSet = { value -> onBrightness(value / 100f); typing = false },
        )
    }
}

/** Quick brightness levels: Off, 25, 50, 75, 100. The current level takes the "on" state. */
@Composable
fun BrightnessLevels(s: UiState, onLevel: (Int) -> Unit) {
    val c = Arva.colors
    val view = LocalView.current
    val current = if (s.on) (s.brightness * 100).roundToInt() else 0
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in listOf(0, 25, 50, 75, 100)) {
            val active = if (level == 0) !s.on else s.on && kotlin.math.abs(current - level) <= 3
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .arvaFocus()
                    .clip(RadiusControl)
                    .background(if (active) c.kikyoTint else c.sunken)
                    .border(1.dp, if (active) c.kikyoTint else c.line, RadiusControl)
                    .clickable(onClickLabel = if (level == 0) "Turn off" else "Set $level percent") { view.hapticSelect(); onLevel(level) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (level == 0) "Off" else "$level",
                    style = if (level == 0) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                    color = if (active) c.onTint else c.text2,
                )
            }
        }
    }
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Arva.colors
    Box(
        Modifier
            .size(48.dp)
            .arvaFocus()
            .clip(RadiusControl)
            .background(c.sunken)
            .border(1.dp, c.line, RadiusControl)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon), contentDescription = label,
            tint = if (enabled) c.text else c.line, modifier = Modifier.size(24.dp),
        )
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
        tonalElevation = 0.dp,
        shape = RadiusCard,
        modifier = Modifier.border(1.dp, c.line, RadiusCard),
        title = { Text("Set brightness", style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = {
            ArvaField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                label = "Percent",
                mono = true,
                keyboardType = KeyboardType.Number,
            )
        },
        confirmButton = { TextButton(onClick = { commit() }) { Text("Set", style = MaterialTheme.typography.labelLarge, color = c.kikyo) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge, color = c.text2) } },
    )
}

/** Colour or temperature, opened from the first round swatch. Changes reach the light as you pick. */
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
        tonalElevation = 0.dp,
        shape = RadiusCard,
        modifier = Modifier.border(1.dp, c.line, RadiusCard),
        title = { Text("Light colour", style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Segmented(listOf(true to "Colour", false to "Temperature"), s.colourMode, onColourMode)
                if (s.colourMode) {
                    ColourWheel(s.hue, s.saturation, onHsv)
                } else {
                    ColorSlider("Warmth", s.temperature, warmthName(s.temperature), onTemperature, WarmthTrack)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", style = MaterialTheme.typography.labelLarge, color = c.kikyo) } },
    )
}

/**
 * A 2-D colour picker: hue around the wheel, saturation from the white centre to the edge. The wheel
 * shows the strip's real colours (content); the thumb is chrome. Drag or tap to pick.
 */
@Composable
fun ColourWheel(hue: Float, saturation: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    val c = Arva.colors
    val view = LocalView.current
    val hueColours = remember { (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) } }
    val change by rememberUpdatedState(onChange)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(WheelSize)
                .pointerInput(Unit) {
                    detectTapGestures { pos -> emit(pos, size.width.toFloat(), size.height.toFloat(), change) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos -> view.hapticTick(); emit(pos, size.width.toFloat(), size.height.toFloat(), change) },
                    ) { pointer, _ -> pointer.consume(); emit(pointer.position, size.width.toFloat(), size.height.toFloat(), change) }
                },
        ) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(hueColours, centre), r, centre)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, r), r, centre)

            // Thumb at (hue angle, saturation radius).
            val a = Math.toRadians(hue.toDouble())
            val dist = saturation.coerceIn(0f, 1f) * r
            val thumb = Offset(centre.x + (cos(a) * dist).toFloat(), centre.y + (sin(a) * dist).toFloat())
            val selected = Color.hsv(((hue % 360) + 360) % 360, saturation.coerceIn(0f, 1f), 1f)
            drawCircle(selected, 12.dp.toPx(), thumb)
            drawCircle(ArvaLight.surface, 12.dp.toPx(), thumb, style = Stroke(width = 3.dp.toPx()))
            drawCircle(ArvaLight.text, 13.5.dp.toPx(), thumb, style = Stroke(width = 1.dp.toPx()))
        }
        // A hairline ring to seat the wheel in the card.
        Box(Modifier.size(WheelSize).clip(CircleShape).border(1.dp, c.line, CircleShape))
    }
}

private val WheelSize = 240.dp

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
