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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.borderkeys.data.theme.ThemePalette
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

/**
 * A label and the palette under it, with the current colour ringed.
 *
 * One implementation, called from every screen that edits a colour -- the theme's eight colours,
 * the pattern colour, the applied-highlight colour, the gradient's second stop, all of them.
 * Reused rather than each screen keeping its own copy of this row, which is exactly the failure
 * that let two of the eleven call sites this had before drift apart: the same-shaped row was
 * written out more than once and only one copy got a fix the other needed too.
 *
 * The row scrolls horizontally because the palette is wider than any phone: eighteen swatches at
 * 30dp with 10dp between them need about 710dp and a Pixel 5 offers 353dp inside the padding.
 * Without the scroll the accents past the ninth are drawn off the edge and cannot be tapped,
 * which is a colour picker that silently refuses to offer half its colours.
 *
 * `preserveAlpha` is for the swipe trail. The trail is drawn deliberately translucent, the
 * palette holds opaque colours, so an exact comparison never matches and the row shows nothing
 * selected. With the flag set the row matches on RGB and keeps the alpha the theme already has,
 * so picking a colour changes the hue of the trail and leaves it as see-through as it was.
 */
@Composable
fun ColourRow(
    label: String,
    current: Int,
    preserveAlpha: Boolean = false,
    onPick: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ColourPickerSheet(
            initial = current,
            palette = ThemePalette.COLOURS,
            onDismiss = { picking = false },
            onPick = { colour ->
                picking = false
                onPick(
                    if (preserveAlpha) (current and ALPHA_MASK) or (colour and RGB_MASK) else colour,
                )
            },
        )
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A colour from the editor is in no swatch, so without this it would be the
            // current colour and invisible: nothing shows it and nothing wears the ring. It
            // appears at the head of the row instead, exactly as VoxApps does it.
            val custom = current.takeIf {
                ThemePalette.COLOURS.none { entry ->
                    if (preserveAlpha) (entry and RGB_MASK) == (it and RGB_MASK) else entry == it
                }
            }
            if (custom != null) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(custom), CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { picking = true },
                )
            }
            for (colour in ThemePalette.COLOURS) {
                val selected = if (preserveAlpha) {
                    (colour and RGB_MASK) == (current and RGB_MASK)
                } else {
                    colour == current
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(colour), CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            onPick(
                                if (preserveAlpha) {
                                    (current and ALPHA_MASK) or (colour and RGB_MASK)
                                } else {
                                    colour
                                },
                            )
                        },
                )
            }
            // Last, after the ready-made colours, because it is the way out of them rather
            // than one more of them. A pencil rather than a colour, as in VoxApps: it is the
            // thing that opens the editor, not a colour to choose.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { picking = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_edit),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val RGB_MASK = 0x00FFFFFF
private const val ALPHA_MASK = 0xFF000000.toInt()
