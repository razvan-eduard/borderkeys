// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.floor

/** How long one pass of the assistant's ring takes to loop. */
const val RING_PERIOD_MILLIS = 5000

/**
 * Where the ring's gradient is along its loop, from 0 to 1, repeating, in [RING_STEPS] steps.
 * Read it in the draw phase only.
 */
@Composable
fun rememberRingShift(): State<Float> {
    // No `label` argument: NoHardcodedTextTest reads every `label = "..."` as user-facing text.
    val ring = rememberInfiniteTransition()
    val shift = ring.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RING_PERIOD_MILLIS, easing = LinearEasing),
        ),
    )
    return remember { derivedStateOf { floor(shift.value * RING_STEPS) / RING_STEPS } }
}

/**
 * The assistant's moving gradient, looping without a seam; [shift] slides its start and end
 * along the diagonal of a square [span] px wide.
 */
fun ringBrush(shift: Float, alpha: Float = 1f, span: Float = DRAFT_BOX_SPAN): Brush {
    val x = (shift * span * 2f) - span
    val colours = if (alpha >= 1f) AI_RING_COLOURS else AI_RING_COLOURS.map { it.copy(alpha = alpha) }
    return Brush.linearGradient(
        colors = colours,
        start = Offset(x, 0f),
        end = Offset(x + span, span),
    )
}

/** Fills the bounds with the ring's gradient at [shift], behind the content. */
fun Modifier.ringBackground(shift: State<Float>, alpha: Float = 1f): Modifier =
    drawBehind { drawRect(ringBrush(shift.value, alpha)) }

/** Edges [shape] with a [width] line of the ring's gradient at [shift], over the content. */
fun Modifier.ringBorder(shift: State<Float>, width: Dp, shape: Shape): Modifier =
    drawWithContent {
        drawContent()
        val stroke = width.toPx()
        val outline = shape.createOutline(
            Size(size.width - stroke, size.height - stroke),
            layoutDirection,
            this,
        )
        translate(stroke / 2f, stroke / 2f) {
            drawOutline(outline, ringBrush(shift.value), style = Stroke(stroke))
        }
    }

/** The ring's gradient at [shift] filling [modifier]'s bounds, on a layer of its own. */
@Composable
fun RingBackground(shift: State<Float>, modifier: Modifier = Modifier) {
    Spacer(modifier.graphicsLayer().ringBackground(shift))
}

private val AI_RING_COLOURS = listOf(
    Color(0xFF8B5CF6), // violet
    Color(0xFF3B82F6), // blue
    Color(0xFFEF4444), // red
    Color(0xFF6D28D9), // purple
    Color(0xFF8B5CF6), // violet again
)

/** The gradient's span around the draft box. */
private const val DRAFT_BOX_SPAN = 900f

/** How many positions the gradient takes along one loop. */
private const val RING_STEPS = 75f
