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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.ColourPickerSheet
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
                // A scrolling row of cards rather than a row of words: at ten of them the names
                // stop being the useful part, and three dots of the actual colours say what a
                // preset is faster than reading "Midnight" does.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (preset in PRESETS) {
                        PresetCard(
                            name = strings[preset.nameKey],
                            preset = preset.theme,
                            selected = theme == preset.theme,
                        ) { update { preset.theme } }
                    }
                }
                Explanation(strings[Keys.THEME_PRESETS_NOTE])
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
        ColourPickerSheet(
            initial = current,
            palette = PALETTE,
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
                PALETTE.none { entry ->
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
            // than one more of them. A pencil rather than a colour, as in VoxApps: it is the
            // thing that opens the editor, not a colour to choose.
            androidx.compose.foundation.layout.Box(
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

/**
 * One preset, drawn as what it looks like.
 *
 * The three dots are the background, the keys and the accent, which is enough to tell the
 * presets apart at a glance and is the same information the name is standing in for.
 */
@Composable
private fun PresetCard(
    name: String,
    preset: KeyboardTheme,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(preset.backgroundColor))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (colour in listOf(preset.keyColor, preset.accentColor, preset.textColor)) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(colour), CircleShape),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            // The preset's own label colour, on the preset's own background: the card is a
            // sample of the theme, so reading it is the same test the keyboard has to pass.
            color = Color(preset.textColor),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A preset and the catalogue key for its name. */
internal class Preset(val nameKey: String, val theme: KeyboardTheme)

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
    // Darker than the dark theme's blue. The accent is text on the suggestion strip, and the
    // lighter blue read at three to one on a pale background, which is a colour you can see
    // and a word you cannot.
    accentColor = 0xFF1D4ED8.toInt(),
    swipeTrailColor = 0xCC1D4ED8.toInt(),
)

internal val HIGH_CONTRAST = KeyboardTheme(
    backgroundColor = 0xFF000000.toInt(),
    keyColor = 0xFF000000.toInt(),
    // Not white. The label is white and is drawn over the pressed fill, so a white pressed key
    // made the letter vanish exactly while the finger was on it -- the one moment it is being
    // looked at. Dark enough to keep the label, light enough to be obviously pressed.
    keyPressedColor = 0xFF5A5A6E.toInt(),
    modifierKeyColor = 0xFF000000.toInt(),
    textColor = 0xFFFFFFFF.toInt(),
    secondaryTextColor = 0xFFC6C6D0.toInt(),
    accentColor = 0xFFF59E0B.toInt(),
    showKeyBorders = true,
    swipeTrailColor = 0xCCF59E0B.toInt(),
)

/**
 * A deep blue that is dark without being black, with a faint grid over it.
 *
 * The pattern is part of the preset, not something to apply afterwards: a preset is what the
 * keyboard looks like, and half of these look like nothing without their surface.
 */
internal val MIDNIGHT = KeyboardTheme(
    backgroundColor = 0xFF0B1020.toInt(),
    keyColor = 0xFF161E36.toInt(),
    keyPressedColor = 0xFF24304F.toInt(),
    modifierKeyColor = 0xFF101728.toInt(),
    textColor = 0xFFE8ECF8.toInt(),
    secondaryTextColor = 0xFF8E9AC0.toInt(),
    accentColor = 0xFF7AA2F7.toInt(),
    swipeTrailColor = 0xCC7AA2F7.toInt(),
    backgroundPattern = KeyboardTheme.PATTERN_GRID,
    patternColor = 0x14FFFFFF,
    patternScaleDp = 28f,
)

internal val OCEAN = KeyboardTheme(
    backgroundColor = 0xFF07222B.toInt(),
    keyColor = 0xFF0E3540.toInt(),
    keyPressedColor = 0xFF175061.toInt(),
    modifierKeyColor = 0xFF0A2B35.toInt(),
    textColor = 0xFFE3F6FB.toInt(),
    secondaryTextColor = 0xFF86B6C4.toInt(),
    accentColor = 0xFF2EC4C6.toInt(),
    swipeTrailColor = 0xCC2EC4C6.toInt(),
    backgroundGradientColor = 0xFF0E4356.toInt(),
)

internal val FOREST = KeyboardTheme(
    backgroundColor = 0xFF0C1A12.toInt(),
    keyColor = 0xFF152A1E.toInt(),
    keyPressedColor = 0xFF23412F.toInt(),
    modifierKeyColor = 0xFF102217.toInt(),
    textColor = 0xFFE7F3EA.toInt(),
    secondaryTextColor = 0xFF8FB39C.toInt(),
    accentColor = 0xFF4ADE80.toInt(),
    swipeTrailColor = 0xCC4ADE80.toInt(),
    backgroundPattern = KeyboardTheme.PATTERN_DIAGONAL,
    patternColor = 0x12FFFFFF,
    patternScaleDp = 20f,
)

internal val SUNSET = KeyboardTheme(
    backgroundColor = 0xFF1B0F14.toInt(),
    keyColor = 0xFF2E1A20.toInt(),
    keyPressedColor = 0xFF452830.toInt(),
    modifierKeyColor = 0xFF241318.toInt(),
    textColor = 0xFFFBEDE6.toInt(),
    secondaryTextColor = 0xFFC79E93.toInt(),
    accentColor = 0xFFFF8A4C.toInt(),
    swipeTrailColor = 0xCCFF8A4C.toInt(),
    backgroundGradientColor = 0xFF3A1A18.toInt(),
)

internal val PAPER = KeyboardTheme(
    backgroundColor = 0xFFEDE6D8.toInt(),
    keyColor = 0xFFFFFBF2.toInt(),
    keyPressedColor = 0xFFDCCFB6.toInt(),
    modifierKeyColor = 0xFFE2D8C4.toInt(),
    textColor = 0xFF2B2419.toInt(),
    secondaryTextColor = 0xFF6B5E48.toInt(),
    // Darker than it looks like it wants to be: the accent is text on the suggestion strip,
    // and a warm mid-brown on cream reads at three to one, which is not enough to read.
    accentColor = 0xFF8A5410.toInt(),
    swipeTrailColor = 0xCC8A5410.toInt(),
    keyCornerRadiusDp = 4f,
    backgroundPattern = KeyboardTheme.PATTERN_DOTS,
    patternColor = 0x14000000,
    patternScaleDp = 18f,
)

internal val MONO = KeyboardTheme(
    // The keys are the same colour as what is behind them; the outline is what separates
    // them. Nothing else in the set does that, which is the point of having it.
    backgroundColor = 0xFF161616.toInt(),
    keyColor = 0xFF161616.toInt(),
    keyPressedColor = 0xFF2E2E2E.toInt(),
    modifierKeyColor = 0xFF101010.toInt(),
    textColor = 0xFFF0F0F0.toInt(),
    secondaryTextColor = 0xFFA0A0A0.toInt(),
    accentColor = 0xFFFFFFFF.toInt(),
    showKeyBorders = true,
    keyCornerRadiusDp = 2f,
    swipeTrailColor = 0xCCFFFFFF.toInt(),
)

internal val NEON = KeyboardTheme(
    backgroundColor = 0xFF05060A.toInt(),
    keyColor = 0xFF101423.toInt(),
    keyPressedColor = 0xFF1E2440.toInt(),
    modifierKeyColor = 0xFF0A0D18.toInt(),
    textColor = 0xFFEAF6FF.toInt(),
    secondaryTextColor = 0xFF7BE0F0.toInt(),
    accentColor = 0xFFFF3DCB.toInt(),
    swipeTrailColor = 0xCCFF3DCB.toInt(),
    keyCornerRadiusDp = 14f,
    backgroundPattern = KeyboardTheme.PATTERN_DOTS,
    patternColor = 0x1AFF3DCB,
    patternScaleDp = 22f,
)

/**
 * Ten of them, in the order the row shows.
 *
 * The first three are where the application started and stay first, because someone who has
 * been using one of them should not have to hunt for it after an update.
 */
internal val PRESETS = listOf(
    Preset(Keys.THEME_DARK, KeyboardTheme()),
    Preset(Keys.THEME_LIGHT, LIGHT_THEME),
    Preset(Keys.THEME_HIGH_CONTRAST, HIGH_CONTRAST),
    Preset(Keys.THEME_MIDNIGHT, MIDNIGHT),
    Preset(Keys.THEME_OCEAN, OCEAN),
    Preset(Keys.THEME_FOREST, FOREST),
    Preset(Keys.THEME_SUNSET, SUNSET),
    Preset(Keys.THEME_PAPER, PAPER),
    Preset(Keys.THEME_MONO, MONO),
    Preset(Keys.THEME_NEON, NEON),
)
