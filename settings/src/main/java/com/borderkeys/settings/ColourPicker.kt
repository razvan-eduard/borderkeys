// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.borderkeys.data.theme.ThemePalette
import com.borderkeys.i18n.Keys

/**
 * A colour by hand: a sheet with a large preview, the swatch row to seed the sliders, and hue,
 * saturation and brightness sliders. The alpha of the colour it is given is kept.
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
                    // The platform's close icon.
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
                // The hex value, not a catalogue string.
                Text(
                    hexOf(picked),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // The swatches again; tapping one loads it into the sliders without choosing it.
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

/** `#RRGGBB`, upper case, without the alpha. */
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
 * A label and the palette under it, in a horizontally scrolling row, with the current colour
 * ringed. Every screen that edits a colour uses it.
 *
 * With [preserveAlpha] the row matches on RGB and a pick keeps the current alpha.
 *
 * Order: the standard palette, then [customColours] (dot-marked, oldest first), then [current]
 * itself, unmarked, when neither list has it, then a dashed circle. The dashed circle and the
 * unmarked swatch open [ColourPickerSheet]; tapping a dot-marked swatch selects it, and
 * long-pressing one asks to delete it.
 */
@Composable
fun ColourRow(
    label: String,
    current: Int,
    customColours: List<Int>,
    onCustomColoursChange: (List<Int>) -> Unit,
    preserveAlpha: Boolean = false,
    onPick: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    var picking by remember { mutableStateOf(false) }
    var deletingIndex by remember { mutableStateOf<Int?>(null) }

    fun withCurrentAlpha(colour: Int) =
        if (preserveAlpha) (current and ALPHA_MASK) or (colour and RGB_MASK) else colour

    fun matchesCurrent(colour: Int) =
        if (preserveAlpha) (colour and RGB_MASK) == (current and RGB_MASK) else colour == current

    if (picking) {
        ColourPickerSheet(
            initial = current,
            palette = ThemePalette.COLOURS,
            onDismiss = { picking = false },
            onPick = { colour ->
                picking = false
                val applied = withCurrentAlpha(colour)
                onPick(applied)
                if (!ThemePalette.contains(ThemePalette.COLOURS, applied, preserveAlpha)) {
                    onCustomColoursChange(
                        ThemePalette.inserted(
                            customColours,
                            applied,
                            ThemePalette.appendIndex(customColours),
                            preserveAlpha,
                        ),
                    )
                }
            },
        )
    }

    deletingIndex?.let { index ->
        val colour = customColours[index]
        AlertDialog(
            onDismissRequest = { deletingIndex = null },
            title = { Text(strings[Keys.THEME_DELETE_CUSTOM_COLOUR_TITLE]) },
            text = { Text(strings.getString(Keys.THEME_DELETE_CUSTOM_COLOUR_MESSAGE, hexOf(colour))) },
            confirmButton = {
                TextButton(onClick = {
                    deletingIndex = null
                    onCustomColoursChange(ThemePalette.removedAt(customColours, index))
                    if (matchesCurrent(colour)) {
                        onPick(withCurrentAlpha(ThemePalette.COLOURS.first()))
                    }
                }) { Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingIndex = null }) { Text(strings[Keys.THEME_CANCEL]) }
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
            for (colour in ThemePalette.COLOURS) {
                ColourSwatch(
                    colour = colour,
                    selected = matchesCurrent(colour),
                    custom = false,
                    onClick = { onPick(withCurrentAlpha(colour)) },
                )
            }
            for ((index, colour) in customColours.withIndex()) {
                ColourSwatch(
                    colour = colour,
                    selected = matchesCurrent(colour),
                    custom = true,
                    onClick = { onPick(withCurrentAlpha(colour)) },
                    onLongClick = { deletingIndex = index },
                )
            }
            // The current value when neither list has it: unmarked, not deletable, and tapping
            // it opens the sheet.
            val transient = current.takeIf {
                !ThemePalette.contains(ThemePalette.COLOURS, it, preserveAlpha) &&
                    !ThemePalette.contains(customColours, it, preserveAlpha)
            }
            if (transient != null) {
                ColourSwatch(
                    colour = transient,
                    selected = true,
                    custom = false,
                    onClick = { picking = true },
                )
            }
            // Last, an empty dashed circle that opens the sheet; the sheet's "Use colour" adds a
            // dot-marked swatch before it.
            val outline = MaterialTheme.colorScheme.outline
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .drawBehind {
                        drawCircle(
                            color = outline,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                                ),
                            ),
                        )
                    }
                    .clickable(onClickLabel = strings[Keys.THEME_ADD_CUSTOM_COLOUR]) { picking = true },
            )
        }
    }
}

/** One swatch. [custom] draws the corner dot; without [onLongClick] it cannot be long-pressed. */
@Composable
private fun ColourSwatch(
    colour: Int,
    selected: Boolean,
    custom: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
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
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        // A corner dot on a colour the user added.
        if (custom) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

private const val RGB_MASK = 0x00FFFFFF
private const val ALPHA_MASK = 0xFF000000.toInt()
