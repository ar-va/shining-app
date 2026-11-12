package com.arva.shining.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
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
 * Wraps an in-app screen so Android's predictive back gesture previews leaving it: as you swipe,
 * the screen eases back, shifts toward the edge and its corners round; release commits [onBack],
 * cancel springs it back. Uses the system progress, so it matches the OS animation.
 */
@Composable
fun PredictiveBackScreen(onBack: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val eased by animateFloatAsState(progress, label = "predictiveBack")

    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e ->
                progress = e.progress
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            progress = 0f
            onBack()
        } catch (e: CancellationException) {
            progress = 0f // cancelled: ease back
            throw e
        }
    }

    Box(
        Modifier.graphicsLayer {
            val p = eased.coerceIn(0f, 1f)
            val s = 1f - 0.08f * p
            scaleX = s
            scaleY = s
            translationX = (if (fromLeft) 1f else -1f) * p * 24.dp.toPx()
            alpha = 1f - 0.12f * p
            clip = true
            shape = androidx.compose.foundation.shape.RoundedCornerShape((28f * p).dp)
        },
    ) {
        content()
    }
}
