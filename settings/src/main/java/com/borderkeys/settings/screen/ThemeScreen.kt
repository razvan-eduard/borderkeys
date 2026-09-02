// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.ColourPickerDialog
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.KeyboardPreview
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * The theme editor, with the real keyboard above it.
 *
 * There is no apply button and no preview state. A swatch writes to the DataStore, the flow
 * re-emits, and the keyboard above redraws -- the same keyboard the input method shows, from the
 * same object. That is the whole reason `:settings` is allowed to depend on `:keyboard`.
 */
@Composable
fun ThemeScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val theme by repository.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by repository.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    fun update(transform: (KeyboardTheme) -> KeyboardTheme) {
        scope.launch { repository.updateTheme(transform) }
    }

    // The preview is outside the scrolling column, so it stays on screen while the controls
    // under it are scrolled. A preview that scrolls away is a preview you cannot see while you
    // are changing the thing it previews, which is the only moment it is for.
    Column(modifier = modifier.fillMaxSize()) {
        KeyboardPreview(theme, preferences, Modifier.padding(vertical = 12.dp))
        Divider()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSectionCard(strings[Keys.THEME_PRESETS]) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { update { KeyboardTheme() } }) { Text(strings[Keys.THEME_DARK]) }
                    TextButton(onClick = { update { LIGHT_THEME } }) { Text(strings[Keys.THEME_LIGHT]) }
                    TextButton(onClick = { update { HIGH_CONTRAST } }) { Text(strings[Keys.THEME_HIGH_CONTRAST]) }
                }
            }
            SettingsSectionCard(strings[Keys.THEME_COLOURS]) {
                ColourRow(strings[Keys.THEME_BACKGROUND], theme.backgroundColor) {
                    update { t -> t.copy(backgroundColor = it) }
                }
                ColourRow(strings[Keys.THEME_KEYS], theme.keyColor) { update { t -> t.copy(keyColor = it) } }
                ColourRow(strings[Keys.THEME_PRESSED_KEY], theme.keyPressedColor) {
                    update { t -> t.copy(keyPressedColor = it) }
                }
                ColourRow(strings[Keys.THEME_MODIFIER_KEYS], theme.modifierKeyColor) {
                    update { t -> t.copy(modifierKeyColor = it) }
                }
                ColourRow(strings[Keys.THEME_LABELS], theme.textColor) { update { t -> t.copy(textColor = it) } }
                ColourRow(strings[Keys.THEME_SECONDARY_LABELS], theme.secondaryTextColor) {
                    update { t -> t.copy(secondaryTextColor = it) }
                }
                ColourRow(strings[Keys.THEME_ACCENT], theme.accentColor) { update { t -> t.copy(accentColor = it) } }
                ColourRow(strings[Keys.THEME_SWIPE_TRAIL], theme.swipeTrailColor, preserveAlpha = true) {
                    update { t -> t.copy(swipeTrailColor = it) }
                }
            }
            SettingsSectionCard(strings[Keys.THEME_BACKGROUND_SECTION]) {
                Text(
                    strings[Keys.THEME_PATTERN],
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (index in PATTERN_LABELS.indices) {
                        val selected = theme.backgroundPattern == index
                        FilterChip(
                            selected = selected,
                            onClick = { update { t -> t.copy(backgroundPattern = index) } },
                            label = { Text(strings[PATTERN_LABELS[index]]) },
                        )
                    }
                }
                ColourRow(strings[Keys.THEME_PATTERN_COLOUR], theme.patternColor, preserveAlpha = true) {
                    update { t -> t.copy(patternColor = it) }
                }
                ThemeSlider(strings[Keys.THEME_PATTERN_SIZE], theme.patternScaleDp, 8f..64f, "dp") {
                    update { t -> t.copy(patternScaleDp = it) }
                }
                // Shown as the background's own colour when there is no second one, so the row
                // has something ringed and picking that same colour is how a gradient is removed.
                ColourRow(strings[Keys.THEME_SECOND_COLOUR], theme.gradientEnd()) {
                    update { t -> t.copy(backgroundGradientColor = it) }
                }
                Explanation(strings[Keys.THEME_SECOND_COLOUR_NOTE])
                SwitchRow(
                    title = strings[Keys.THEME_FULL_WIDTH_BACKGROUND],
                    subtitle = strings[Keys.THEME_FULL_WIDTH_BACKGROUND_NOTE],
                    checked = theme.fullWidthBackground,
                ) { value -> update { it.copy(fullWidthBackground = value) } }
            }
            SettingsSectionCard(strings[Keys.THEME_SHAPE]) {
                ThemeSlider(
                    strings[Keys.THEME_CORNER_RADIUS], theme.keyCornerRadiusDp, 0f..32f,
                    strings[Keys.THEME_DP],
                ) {
                    update { t -> t.copy(keyCornerRadiusDp = it) }
                }
                ThemeSlider(strings[Keys.THEME_GAP_BETWEEN_KEYS], theme.keyGapDp, 0f..16f, "dp") {
                    update { t -> t.copy(keyGapDp = it) }
                }
                ThemeSlider(strings[Keys.THEME_ROW_HEIGHT], theme.rowHeightDp, 28f..96f, "dp") {
                    update { t -> t.copy(rowHeightDp = it) }
                }
                ThemeSlider(strings[Keys.THEME_LABEL_SIZE], theme.labelTextSizeSp, 8f..40f, "sp") {
                    update { t -> t.copy(labelTextSizeSp = it) }
                }
                ThemeSlider(strings[Keys.THEME_PRESS_DEPTH], theme.pressedElevation, 0f..16f, "dp") {
                    update { t -> t.copy(pressedElevation = it) }
                }
                ThemeSlider(strings[Keys.THEME_TRAIL_WIDTH], theme.swipeTrailWidthDp, 1f..24f, "dp") {
                    update { t -> t.copy(swipeTrailWidthDp = it) }
                }
                SwitchRow(
                    title = strings[Keys.THEME_OUTLINE_THE_KEYS],
                    subtitle = strings[Keys.THEME_A_HAIRLINE_BORDER_HELPS_WHEN_THE],
                    checked = theme.showKeyBorders,
                ) { value -> update { it.copy(showKeyBorders = value) } }
                Explanation(
                    strings[Keys.THEME_VALUES_ARE_CLAMPED_WHEN_THEY_ARE],
                )
            }
        }
    }
}

/**
 * The pattern names, as catalogue keys in the order of the PATTERN_ constants.
 *
 * Keys rather than text: this is a file-level value, built before any composition has a
 * catalogue to read from.
 */
private val PATTERN_LABELS = arrayOf(
    Keys.THEME_PATTERN_NONE,
    Keys.THEME_PATTERN_DOTS,
    Keys.THEME_PATTERN_GRID,
    Keys.THEME_PATTERN_DIAGONAL,
    Keys.THEME_PATTERN_CHECKS,
    Keys.THEME_PATTERN_STRIPES,
)

/**
 * A label and the palette under it, with the current colour ringed.
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
private fun ColourRow(
    label: String,
    current: Int,
    preserveAlpha: Boolean = false,
    onPick: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ColourPickerDialog(
            initial = current,
            onDismiss = { picking = false },
            onPick = { colour ->
                picking = false
                onPick(if (preserveAlpha) (current and ALPHA_MASK) or (colour and RGB_MASK) else colour)
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
            for (colour in PALETTE) {
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
            // than one more of them. Ringed when the current colour is not in the row, which
            // is exactly when the colour came from here.
            val custom = PALETTE.none {
                if (preserveAlpha) (it and RGB_MASK) == (current and RGB_MASK) else it == current
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Brush.sweepGradient(HUE_WHEEL), CircleShape)
                    .border(
                        width = if (custom) 3.dp else 1.dp,
                        color = if (custom) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { picking = true },
            )
        }
    }
}

/**
 * The wheel drawn on the custom swatch. Its own list rather than the picker's, because this one
 * is a sweep around a circle and that one is a strip across a rectangle.
 */
private val HUE_WHEEL = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

private const val RGB_MASK = 0x00FFFFFF
private const val ALPHA_MASK = 0xFF000000.toInt()

@Composable
private fun Box(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier = modifier) {}
}

@Composable
private fun ThemeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onChange: (Float) -> Unit,
) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            strings.getString(Keys.THEME_TEXT, label, value.toInt(), unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range),
            valueRange = range,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A fixed palette rather than a colour wheel.
 *
 * Sixteen values a person can pick from at a glance, instead of a picker that lets them choose
 * a label colour one step from the key colour and then wonder why the keyboard is unreadable.
 */
/**
 * The colours a swatch row offers: a neutral ramp from black to white, then accents.
 *
 * Every colour used by any preset below appears here. That is a requirement, not a
 * coincidence: a swatch row draws a ring around the entry that matches the current value, so a
 * preset colour missing from the ramp leaves the row with nothing selected and the user with no
 * idea what the current colour is. `presetColoursAreInPalette` in the unit tests holds the two
 * lists together.
 */
internal val PALETTE = listOf(
    0xFF000000.toInt(), 0xFF14141A.toInt(), 0xFF1E1E26.toInt(), 0xFF2A2A34.toInt(),
    0xFF3D3D4C.toInt(), 0xFF5A5A6E.toInt(), 0xFF9A9AAA.toInt(), 0xFFC6C6D0.toInt(),
    0xFFD4D4DE.toInt(), 0xFFE6E6EE.toInt(), 0xFFF2F2F7.toInt(), 0xFFFFFFFF.toInt(),
    0xFF6EA8FE.toInt(), 0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(),
    0xFFEF4444.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt(), 0xFF14B8A6.toInt(),
)

internal val LIGHT_THEME = KeyboardTheme(
    backgroundColor = 0xFFE6E6EE.toInt(),
    keyColor = 0xFFFFFFFF.toInt(),
    keyPressedColor = 0xFFC6C6D0.toInt(),
    modifierKeyColor = 0xFFD4D4DE.toInt(),
    textColor = 0xFF14141A.toInt(),
    secondaryTextColor = 0xFF5A5A6E.toInt(),
    accentColor = 0xFF3B82F6.toInt(),
    swipeTrailColor = 0xCC3B82F6.toInt(),
)

internal val HIGH_CONTRAST = KeyboardTheme(
    backgroundColor = 0xFF000000.toInt(),
    keyColor = 0xFF000000.toInt(),
    keyPressedColor = 0xFFFFFFFF.toInt(),
    modifierKeyColor = 0xFF000000.toInt(),
    textColor = 0xFFFFFFFF.toInt(),
    secondaryTextColor = 0xFFC6C6D0.toInt(),
    accentColor = 0xFFF59E0B.toInt(),
    showKeyBorders = true,
    swipeTrailColor = 0xFFF59E0B.toInt(),
)
