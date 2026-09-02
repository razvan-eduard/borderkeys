// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.borderkeys.i18n.Keys

/**
 * A colour by hand, when none of the swatches is the one.
 *
 * Hue, saturation and brightness rather than three channels of red, green and blue: "a slightly
 * warmer grey" is one small move here and a puzzle in RGB, and picking a colour is the one task
 * where the axes people think in are not the axes the pixel is stored in.
 *
 * The alpha of the colour it is given is kept. The swipe trail is deliberately translucent, and
 * a picker that quietly made it opaque would be a picker that broke it.
 */
@Composable
fun ColourPickerDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    val start = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(start[1]) }
    var brightness by remember(initial) { mutableFloatStateOf(start[2]) }

    val alpha = initial ushr 24
    val picked = android.graphics.Color.HSVToColor(
        alpha, floatArrayOf(hue, saturation, brightness),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[Keys.THEME_COLOUR_PICKER]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SaturationBrightnessField(hue, saturation, brightness) { s, v ->
                    saturation = s
                    brightness = v
                }
                HueSlider(hue) { hue = it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(picked), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    // The hex is the value itself, not a sentence: the same six digits in every
                    // language, and the form anyone copying a colour from somewhere else knows.
                    Text(hexOf(picked), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(picked) }) { Text(strings[Keys.THEME_USE_COLOUR]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

/** `#RRGGBB`, upper case, alpha left off because the picker does not change it. */
private fun hexOf(colour: Int): String {
    val digits = "0123456789ABCDEF"
    val out = CharArray(7)
    out[0] = '#'
    for (i in 0 until 6) {
        out[6 - i] = digits[(colour shr (i * 4)) and 0xF]
    }
    return String(out)
}

/**
 * The square: saturation across, brightness down, at the chosen hue.
 *
 * Two gradients over each other rather than a bitmap computed pixel by pixel -- white to the
 * hue horizontally, transparent to black vertically -- which is the same image and is drawn by
 * the GPU.
 */
@Composable
private fun SaturationBrightnessField(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float) -> Unit,
) {
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.4f)
                .pointerInput(hue) {
                    fun report(position: Offset) {
                        onChange(
                            (position.x / size.width).coerceIn(0f, 1f),
                            1f - (position.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                    // Both, so the square answers a tap and the start of a drag alike.
                    detectTapGestures(onTap = { report(it) }, onPress = { report(it) })
                }
                .pointerInput(hue) {
                    detectDragGestures { change, _ ->
                        onChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            1f - (change.position.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val centre = Offset(saturation * size.width, (1f - brightness) * size.height)
            // Two rings, dark inside light, so the marker is visible on every colour under it.
            drawCircle(Color.White, radius = MARKER_RADIUS_PX, center = centre, style = markerStroke)
            drawCircle(Color.Black, radius = MARKER_RADIUS_PX + 2f, center = centre, style = markerStroke)
        }
    }
}

/** The hue strip, one full turn of the wheel from left to right. */
@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                fun report(position: Offset) {
                    onChange((position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
                detectTapGestures(onTap = { report(it) }, onPress = { report(it) })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(HUE_STOPS))
        val x = (hue / 360f) * size.width
        drawCircle(Color.White, radius = size.height / 2f - 3f, center = Offset(x, size.height / 2f),
            style = markerStroke)
    }
}

private val markerStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
private const val MARKER_RADIUS_PX = 14f

/**
 * The corners of the hue wheel. Six stops plus the wrap back to red, so the strip is a full
 * turn and the ends meet.
 */
private val HUE_STOPS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)
