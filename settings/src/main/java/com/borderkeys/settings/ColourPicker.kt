// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.borderkeys.i18n.Keys

/**
 * A colour by hand, when none of the swatches is the one.
 *
 * The shape is VoxApps' `VoxCustomColorDialog`, ported rather than reinvented: a sheet that is
 * dismissed by dragging it down like everything else that covers the screen, a large preview of
 * the colour, the swatch row again so a preset can seed the sliders, and hue, saturation and
 * brightness on three sliders.
 *
 * Those three axes rather than red, green and blue: "a slightly warmer grey" is one small move
 * in the first and a puzzle in the second, and choosing a colour is the one task where the axes
 * people think in are not the axes the pixel is stored in.
 *
 * The alpha of the colour it is given is kept. The swipe trail is deliberately translucent, and
 * a picker that quietly made it opaque would be a picker that broke it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColourPickerSheet(
    initial: Int,
    palette: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    val start = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(start[1]) }
    var brightness by remember(initial) { mutableFloatStateOf(start[2]) }

    val alpha = initial ushr 24
    val picked = android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness))

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    // The platform's own icon, as the settings activity's close button already
                    // uses: this application ships no icon set and is not about to add one for
                    // a cross.
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = strings[Keys.THEME_CANCEL],
                    )
                }
                Text(
                    strings[Keys.THEME_COLOUR_PICKER],
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(picked))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
                // The hex is the value itself, not a sentence: the same six digits in every
                // language, and the form anyone copying a colour from elsewhere already has.
                Text(
                    hexOf(picked),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // The ready-made colours again, as somewhere to start from rather than somewhere to
            // finish: tapping one loads it into the sliders instead of choosing it.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (colour in palette) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(Color(colour), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .clickable {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(colour, hsv)
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                            },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(strings[Keys.THEME_HUE], style = MaterialTheme.typography.labelLarge)
            Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)

            Text(strings[Keys.THEME_SATURATION], style = MaterialTheme.typography.labelLarge)
            Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)

            Text(strings[Keys.THEME_BRIGHTNESS], style = MaterialTheme.typography.labelLarge)
            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0f..1f)

            Spacer(Modifier.height(24.dp))

            Button(onClick = { onPick(picked) }, modifier = Modifier.fillMaxWidth()) {
                Text(strings[Keys.THEME_USE_COLOUR])
            }

            Spacer(Modifier.height(16.dp))
        }
    }
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
