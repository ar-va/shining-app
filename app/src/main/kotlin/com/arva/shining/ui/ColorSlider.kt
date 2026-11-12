package com.arva.shining.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Slider whose track shows the light's actual colours (content, like a photo — not decoration).
 * Chrome follows the brand: 8 dp radius, 1 dp line border, slim handle without shadow.
 */
@Composable
fun ColorSlider(
    label: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    track: Brush,
    modifier: Modifier = Modifier,
) {
    val c = Arva.colors
    val onChange by rememberUpdatedState(onValueChange)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(label, Modifier.weight(1f))
            // Mono only for numbers (brand rule); words stay in Plex Sans.
            val numeric = valueText.firstOrNull()?.isDigit() == true
            Text(valueText, style = if (numeric) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall, color = c.text2)
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                    setProgress { onChange(it.coerceIn(0f, 1f)); true }
                },
        ) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val handlePx = with(density) { HandleWidth.toPx() }
            val travel = (widthPx - handlePx).coerceAtLeast(1f)
            fun toValue(x: Float) = ((x - handlePx / 2) / travel).coerceIn(0f, 1f)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .align(Alignment.Center)
                    .clip(RadiusControl)
                    .background(track)
                    .border(1.dp, c.line, RadiusControl),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(widthPx) { detectTapGestures { onChange(toValue(it.x)) } }
                    .pointerInput(widthPx) {
                        detectHorizontalDragGestures(onDragStart = { onChange(toValue(it.x)) }) { change, _ ->
                            change.consume()
                            onChange(toValue(change.position.x))
                        }
                    },
            )
            Box(
                Modifier
                    .offset { IntOffset((value.coerceIn(0f, 1f) * travel).roundToInt(), 0) }
                    .size(width = HandleWidth, height = 44.dp)
                    .background(c.surface, HandleShape)
                    .border(1.5.dp, c.text, HandleShape),
            )
        }
    }
}

private val HandleWidth = 14.dp
private val HandleShape = RoundedCornerShape(6.dp)

/** The hue track: the colours the strip can actually make. */
val HueTrack = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })

private val Warm = Color(0xFFFFB067)
private val Neutral = Color(0xFFFFF1DE)
private val Cool = Color(0xFFDCE8FF)

/** Approximate on-screen colour of the strip's white channel at a warmth (0 warm – 1 cool). */
fun whiteColor(t: Float): Color =
    if (t < 0.5f) androidx.compose.ui.graphics.lerp(Warm, Neutral, (t * 2).coerceIn(0f, 1f))
    else androidx.compose.ui.graphics.lerp(Neutral, Cool, ((t - 0.5f) * 2).coerceIn(0f, 1f))

val WarmthTrack = Brush.horizontalGradient(listOf(Warm, Neutral, Cool))
