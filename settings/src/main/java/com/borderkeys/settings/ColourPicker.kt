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
 *
 * Order: the standard palette, then [customColours] -- colours this field has actually had
 * picked for it before, dot-marked, oldest first -- then, only if [current] is not covered by
 * either of those, [current] itself, unmarked, so a preset whose colour was simply never added
 * to the palette (Ocean's teal, for instance) still shows *something* ringed without looking
 * like anyone chose it. The dashed circle at the end is the only way in: tapping it, or the
 * unmarked trailing swatch, opens [ColourPickerSheet]; tapping a dot-marked swatch selects it
 * outright, and long-pressing one asks to delete it.
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
            // Only when the current value is not offered by either list above -- a preset's
            // own colour that nobody has picked through the wheel yet. Unmarked and not
            // deletable: there is nothing here the user added. Tapping it opens the sheet
            // rather than re-selecting itself, since it is already the current colour.
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
            // Last, after every colour, because it is the way out of them rather than one
            // more of them: an empty, dashed circle -- no icon, no fill -- opens the sheet
            // whose "Use colour" button appends a new dot-marked swatch just before this one.
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

/** One swatch, shared by the standard palette, the persisted custom colours and the transient
 *  off-palette one -- [custom] is the only difference in how it looks (the corner dot), and
 *  [onLongClick] the only difference in how it behaves. */
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
        // The same idea as QuickActionsView's own "this one is yours" dot, adapted to Compose:
        // a small corner mark rather than a second icon, since the swatch has no room for one.
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
