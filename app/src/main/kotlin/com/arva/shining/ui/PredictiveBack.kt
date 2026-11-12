package com.arva.shining.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wraps an in-app screen so Android's predictive back gesture previews leaving it: [behind] (the
 * destination) is rendered underneath while you swipe, and the current screen eases back, shifts
 * toward the edge and rounds its corners to reveal it. Release commits [onBack]; cancel springs back.
 * The destination is only composed while a gesture is in progress, so there's no idle cost.
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    enabled: Boolean = true,
    behind: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    var fromLeft by remember { mutableStateOf(true) }
    val eased by animateFloatAsState(progress, label = "predictiveBack")

    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e ->
                active = true
                progress = e.progress
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            progress = 0f
            active = false
            onBack()
        } catch (e: CancellationException) {
            progress = 0f // cancelled: ease back, then stop drawing the destination
            active = false
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (active || eased > 0.001f) {
            Box(Modifier.fillMaxSize()) { behind() }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Arva.colors.bg) // opaque so the destination only shows as the screen eases away
                .graphicsLayer {
                    val p = eased.coerceIn(0f, 1f)
                    val s = 1f - 0.10f * p
                    scaleX = s
                    scaleY = s
                    translationX = (if (fromLeft) 1f else -1f) * p * 32.dp.toPx()
                    alpha = 1f - 0.08f * p
                    clip = true
                    shape = androidx.compose.foundation.shape.RoundedCornerShape((28f * p).dp)
                },
        ) {
            content()
        }
    }
}
