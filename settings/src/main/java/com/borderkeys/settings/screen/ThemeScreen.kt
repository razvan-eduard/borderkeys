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
import androidx.compose.foundation.layout.Box
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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.borderkeys.data.theme.BackgroundImages
import com.borderkeys.data.theme.CustomThemeEntry
import com.borderkeys.data.theme.CustomThemeFile
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.ColourRow
import com.borderkeys.settings.Disableable
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater
import com.borderkeys.theme.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The theme editor, with the real keyboard above it: a swatch writes to the DataStore and the
 * keyboard redraws from the flow.
 */
@Composable
fun ThemeScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, lightTheme, preferences) = appearance
    val auto = preferences.themeMode == KeyboardPreferences.THEME_MODE_AUTO_SYSTEM

    val update = rememberThemeUpdater()
    val updatePreferences = rememberPreferencesUpdater()

    val context = androidx.compose.ui.platform.LocalContext.current
    val picture = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Copied and shrunk off the main thread.
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BackgroundImages.import(context, uri)
            }
            if (name != null) {
                update { it.copy(backgroundImage = name) }
            }
        }
    }

    val customThemes by repository.customThemes.collectAsStateWithLifecycle(initialValue = emptyList())
    var savingCurrentTheme by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CustomThemeEntry?>(null) }
    var deleting by remember { mutableStateOf<CustomThemeEntry?>(null) }
    var exporting by remember { mutableStateOf<CustomThemeEntry?>(null) }
    var customThemeNotice by remember { mutableStateOf("") }

    val exportTheme = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CustomThemeFile.MIME_TYPE),
    ) { uri: Uri? ->
        val entry = exporting
        exporting = null
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = CustomThemeFile.write(entry.name, entry.theme)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            customThemeNotice = strings[if (ok) Keys.THEME_EXPORTED else Keys.THEME_EXPORT_FAILED]
        }
    }

    val importTheme = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val parsed = text?.let { CustomThemeFile.read(it) }
            if (parsed == null) {
                customThemeNotice = strings[Keys.THEME_IMPORT_FAILED]
                return@launch
            }
            val (name, importedTheme) = parsed
            val saved = repository.saveCustomTheme(name, importedTheme)
            customThemeNotice = strings[
                if (saved != null) Keys.THEME_IMPORTED else Keys.THEME_CUSTOM_THEME_LIMIT_REACHED,
            ]
        }
    }

    // The preview stays above the scrolling controls.
    Column(modifier = modifier.fillMaxSize()) {
        // The live appearance, auto dark/light included, in its real placement.
        PlacementPreview(appearance, Modifier.padding(vertical = 12.dp))
        Divider()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSectionCard(strings[Keys.SCREEN_THEME]) {
                // Greyed out and untouchable while auto is on.
                Disableable(disabled = auto) {
                    // Grouped by category.
                    for (category in ThemeCategory.entries) {
                        val presetsInCategory = PRESETS.filter { it.category == category }
                        if (presetsInCategory.isEmpty()) continue
                        Text(
                            strings[category.labelKey],
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (preset in presetsInCategory) {
                                // showKeyBorders is carried over; a preset does not change it.
                                val presetTheme = preset.theme.copy(showKeyBorders = theme.showKeyBorders)
                                PresetCard(
                                    name = strings[preset.nameKey],
                                    preset = preset.theme,
                                    selected = theme == presetTheme,
                                ) { update { presetTheme } }
                            }
                        }
                    }
                    Explanation(strings[Keys.THEME_PRESETS_NOTE])
                }
                SwitchRow(
                    title = strings[Keys.THEME_AUTO_FOLLOW_SYSTEM],
                    subtitle = strings[Keys.THEME_AUTO_FOLLOW_SYSTEM_NOTE],
                    checked = auto,
                ) { value ->
                    // The light theme is seeded when auto is turned on while it still holds the
                    // default.
                    if (value && lightTheme == KeyboardTheme()) {
                        scope.launch { repository.updateLightTheme { LIGHT_THEME } }
                    }
                    updatePreferences {
                        it.copy(
                            themeMode = if (value) {
                                KeyboardPreferences.THEME_MODE_AUTO_SYSTEM
                            } else {
                                KeyboardPreferences.THEME_MODE_MANUAL
                            },
                        )
                    }
                }
                if (DynamicColors.available) {
                    SwitchRow(
                        title = strings[Keys.THEME_FOLLOW_SYSTEM_COLOURS],
                        subtitle = strings[Keys.THEME_FOLLOW_SYSTEM_COLOURS_NOTE],
                        checked = preferences.followSystemColors,
                    ) { value -> updatePreferences { it.copy(followSystemColors = value) } }
                }
            }
            SettingsSectionCard(strings[Keys.THEME_MY_THEMES]) {
                if (customThemes.isEmpty()) {
                    Explanation(strings[Keys.THEME_NO_CUSTOM_THEMES])
                } else {
                    // Greyed out while auto is on, like the presets.
                    Disableable(disabled = auto) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (entry in customThemes) {
                                // showKeyBorders is carried over, as for a preset.
                                val entryTheme = entry.theme.copy(showKeyBorders = theme.showKeyBorders)
                                PresetCard(
                                    name = entry.name,
                                    preset = entry.theme,
                                    selected = theme == entryTheme,
                                ) { update { entryTheme } }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { savingCurrentTheme = true }) {
                        Text(strings[Keys.THEME_SAVE_CURRENT_THEME])
                    }
                    TextButton(onClick = { importTheme.launch(arrayOf("*/*")) }) {
                        Text(strings[Keys.THEME_IMPORT_THEME])
                    }
                }
                // The saved theme showing now, by the equality the cards' ring uses.
                val active = customThemes.firstOrNull {
                    it.theme.copy(showKeyBorders = theme.showKeyBorders) == theme
                }
                if (active != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { renaming = active }) {
                            Text(strings[Keys.THEME_RENAME])
                        }
                        TextButton(onClick = {
                            exporting = active
                            exportTheme.launch("${active.name}.json")
                        }) { Text(strings[Keys.THEME_EXPORT]) }
                        TextButton(onClick = { deleting = active }) {
                            Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (customThemeNotice.isNotEmpty()) {
                    Explanation(customThemeNotice)
                }
                Explanation(strings[Keys.THEME_MY_THEMES_NOTE])
            }
            SettingsSectionCard(strings[Keys.THEME_COLOURS]) {
              Disableable(disabled = auto) {
                ColourRow(
                    strings[Keys.THEME_BACKGROUND],
                    theme.backgroundColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_BACKGROUND] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_BACKGROUND to it)) } },
                ) { update { t -> t.copy(backgroundColor = it) } }
                ColourRow(
                    strings[Keys.THEME_KEYS],
                    theme.keyColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_KEY] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_KEY to it)) } },
                ) { update { t -> t.copy(keyColor = it) } }
                ColourRow(
                    strings[Keys.THEME_PRESSED_KEY],
                    theme.keyPressedColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_KEY_PRESSED] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_KEY_PRESSED to it)) } },
                ) { update { t -> t.copy(keyPressedColor = it) } }
                ColourRow(
                    strings[Keys.THEME_MODIFIER_KEYS],
                    theme.modifierKeyColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_MODIFIER_KEY] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_MODIFIER_KEY to it)) } },
                ) { update { t -> t.copy(modifierKeyColor = it) } }
                ColourRow(
                    strings[Keys.THEME_LABELS],
                    theme.textColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_TEXT] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_TEXT to it)) } },
                ) { update { t -> t.copy(textColor = it) } }
                ColourRow(
                    strings[Keys.THEME_SECONDARY_LABELS],
                    theme.secondaryTextColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_SECONDARY_TEXT] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_SECONDARY_TEXT to it)) } },
                ) { update { t -> t.copy(secondaryTextColor = it) } }
                ColourRow(
                    strings[Keys.THEME_ACCENT],
                    theme.accentColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_ACCENT] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_ACCENT to it)) } },
                ) { update { t -> t.copy(accentColor = it) } }
                ColourRow(
                    strings[Keys.THEME_SWIPE_TRAIL],
                    theme.swipeTrailColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_SWIPE_TRAIL] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_SWIPE_TRAIL to it)) } },
                    preserveAlpha = true,
                ) { update { t -> t.copy(swipeTrailColor = it) } }
              }
                AdvancedSection(strings[Keys.THEME_ADVANCED_CHIP_NOTE]) {
                    SwitchRow(
                        title = strings[Keys.THEME_FILL_THE_CORRECTION_CHIP],
                        subtitle = strings[Keys.THEME_FILL_THE_CORRECTION_CHIP_NOTE],
                        checked = theme.appliedHighlightStyle == KeyboardTheme.APPLIED_HIGHLIGHT_BACKGROUND,
                    ) { value ->
                        update {
                            it.copy(
                                appliedHighlightStyle = if (value) {
                                    KeyboardTheme.APPLIED_HIGHLIGHT_BACKGROUND
                                } else {
                                    KeyboardTheme.APPLIED_HIGHLIGHT_OUTLINE
                                },
                            )
                        }
                    }
                    ColourRow(
                        strings[Keys.THEME_APPLIED_HIGHLIGHT_COLOUR],
                        theme.appliedHighlightColorOrDefault(),
                        customColours = theme.customColours[KeyboardTheme.KEY_APPLIED_HIGHLIGHT] ?: emptyList(),
                        onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_APPLIED_HIGHLIGHT to it)) } },
                        preserveAlpha = true,
                    ) { update { t -> t.copy(appliedHighlightColor = it) } }
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
                    // Chips that toggle: patterns layer, and none selected means none.
                    for (index in KeyboardTheme.PATTERN_DOTS until KeyboardTheme.PATTERN_COUNT) {
                        val selected = theme.backgroundPatterns.contains(index)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                update { t ->
                                    t.copy(
                                        backgroundPatterns = if (selected) {
                                            t.backgroundPatterns - index
                                        } else {
                                            t.backgroundPatterns + index
                                        },
                                    )
                                }
                            },
                            label = { Text(strings[PATTERN_LABELS[index]]) },
                        )
                    }
                }
                ColourRow(
                    strings[Keys.THEME_PATTERN_COLOUR],
                    theme.patternColor,
                    customColours = theme.customColours[KeyboardTheme.KEY_PATTERN] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_PATTERN to it)) } },
                    preserveAlpha = true,
                ) { update { t -> t.copy(patternColor = it) } }
                ThemeSlider(
                    strings[Keys.THEME_PATTERN_SIZE], theme.patternScaleDp, 8f..64f,
                    strings[Keys.THEME_DP], default = 24f,
                ) {
                    update { t -> t.copy(patternScaleDp = it) }
                }
                // The picture, a layer with the patterns.
                Text(
                    strings[Keys.THEME_PICTURE],
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Explanation(strings[Keys.THEME_PICTURE_NOTE])
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { picture.launch(arrayOf("image/*")) }) {
                        Text(strings[Keys.THEME_CHOOSE_PICTURE])
                    }
                    if (theme.backgroundImage.isNotEmpty()) {
                        TextButton(onClick = {
                            BackgroundImages.forget(context)
                            update { t -> t.copy(backgroundImage = "") }
                        }) { Text(strings[Keys.THEME_REMOVE_PICTURE]) }
                    }
                }
                if (theme.backgroundImage.isNotEmpty()) {
                    ThemeSlider(
                        strings[Keys.THEME_PICTURE_DIM], theme.backgroundImageDim * 100f, 0f..100f,
                        strings[Keys.THEME_PERCENT], default = 55f,
                    ) { update { t -> t.copy(backgroundImageDim = it / 100f) } }
                    Explanation(strings[Keys.THEME_PICTURE_DIM_NOTE])
                }
                // The background's own colour when there is no second one; picking it removes the
                // gradient.
                ColourRow(
                    strings[Keys.THEME_SECOND_COLOUR],
                    theme.gradientEnd(),
                    customColours = theme.customColours[KeyboardTheme.KEY_GRADIENT_END] ?: emptyList(),
                    onCustomColoursChange = { update { t -> t.copy(customColours = t.customColours + (KeyboardTheme.KEY_GRADIENT_END to it)) } },
                ) { update { t -> t.copy(backgroundGradientColor = it) } }
                Explanation(strings[Keys.THEME_SECOND_COLOUR_NOTE])
                ThemeSlider(
                    strings[Keys.THEME_OPACITY], theme.opacity * 100f,
                    KeyboardTheme.MIN_OPACITY * 100f..100f, strings[Keys.THEME_PERCENT], default = 100f,
                ) { update { t -> t.copy(opacity = it / 100f) } }
                Explanation(strings[Keys.THEME_OPACITY_NOTE])
                AdvancedSection(strings[Keys.THEME_ADVANCED_NAVIGATION_NOTE]) {
                    SwitchRow(
                        title = strings[Keys.THEME_NAVIGATION_BAR_BACKGROUND],
                        subtitle = strings[Keys.THEME_NAVIGATION_BAR_BACKGROUND_NOTE],
                        checked = theme.navigationBarBackground,
                    ) { value -> update { it.copy(navigationBarBackground = value) } }
                }
            }
            SettingsSectionCard(strings[Keys.THEME_SHAPE]) {
                ThemeSlider(
                    strings[Keys.THEME_CORNER_RADIUS], theme.keyCornerRadiusDp, 0f..32f,
                    strings[Keys.THEME_DP], default = 8f,
                ) {
                    update { t -> t.copy(keyCornerRadiusDp = it) }
                }
                ThemeSlider(
                    strings[Keys.THEME_GAP_BETWEEN_KEYS], theme.keyGapDp, 0f..16f,
                    strings[Keys.THEME_DP], default = 4f,
                ) {
                    update { t -> t.copy(keyGapDp = it) }
                }
                ThemeSlider(
                    strings[Keys.THEME_ROW_HEIGHT], theme.rowHeightDp, 28f..96f,
                    strings[Keys.THEME_DP], default = 52f,
                ) {
                    update { t -> t.copy(rowHeightDp = it) }
                }
                ThemeSlider(
                    strings[Keys.THEME_LABEL_SIZE], theme.labelTextSizeSp, 8f..40f,
                    strings[Keys.THEME_SP], default = 20f,
                ) {
                    update { t -> t.copy(labelTextSizeSp = it) }
                }
                // The hint text's size, how far a pressed key sinks, and the swipe trail's width.
                AdvancedSection(strings[Keys.THEME_ADVANCED_TEXT_NOTE]) {
                    ThemeSlider(
                        strings[Keys.THEME_ACCENT_SIZE], theme.accentTextSizeSp, 6f..32f,
                        strings[Keys.THEME_SP], default = 15.5f,
                    ) {
                        update { t -> t.copy(accentTextSizeSp = it) }
                    }
                    ThemeSlider(
                        strings[Keys.THEME_PRESS_DEPTH], theme.pressedElevation, 0f..16f,
                        strings[Keys.THEME_DP], default = 2f,
                    ) {
                        update { t -> t.copy(pressedElevation = it) }
                    }
                    ThemeSlider(
                        strings[Keys.THEME_TRAIL_WIDTH], theme.swipeTrailWidthDp, 1f..24f,
                        strings[Keys.THEME_DP], default = 4f,
                    ) {
                        update { t -> t.copy(swipeTrailWidthDp = it) }
                    }
                }
                Button(
                    onClick = {
                        // KeyboardTheme's own defaults.
                        val defaults = KeyboardTheme()
                        update {
                            it.copy(
                                keyCornerRadiusDp = defaults.keyCornerRadiusDp,
                                keyGapDp = defaults.keyGapDp,
                                rowHeightDp = defaults.rowHeightDp,
                                labelTextSizeSp = defaults.labelTextSizeSp,
                                accentTextSizeSp = defaults.accentTextSizeSp,
                                pressedElevation = defaults.pressedElevation,
                                swipeTrailWidthDp = defaults.swipeTrailWidthDp,
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                ) { Text(strings[Keys.COMMON_RESET_TO_DEFAULTS]) }
                Explanation(strings[Keys.COMMON_RESET_TO_DEFAULTS_NOTE])
                SwitchRow(
                    title = strings[Keys.THEME_OUTLINE_THE_KEYS],
                    subtitle = strings[Keys.THEME_A_HAIRLINE_BORDER_HELPS_WHEN_THE],
                    checked = theme.showKeyBorders,
                ) { value ->
                    // Written to both the dark and the light theme.
                    update { it.copy(showKeyBorders = value) }
                    scope.launch { repository.updateLightTheme { it.copy(showKeyBorders = value) } }
                }
                Explanation(
                    strings[Keys.THEME_VALUES_ARE_CLAMPED_WHEN_THEY_ARE],
                )
            }
        }
    }

    if (savingCurrentTheme) {
        ThemeNameDialog(
            title = strings[Keys.THEME_NAME_THIS_THEME],
            confirmLabel = strings[Keys.THEME_SAVE],
            onDismiss = { savingCurrentTheme = false },
            onConfirm = { name ->
                savingCurrentTheme = false
                scope.launch {
                    val saved = repository.saveCustomTheme(name, theme)
                    if (saved == null) {
                        customThemeNotice = strings[Keys.THEME_CUSTOM_THEME_LIMIT_REACHED]
                    }
                }
            },
        )
    }

    renaming?.let { entry ->
        ThemeNameDialog(
            title = strings[Keys.THEME_RENAME_THEME],
            confirmLabel = strings[Keys.THEME_SAVE],
            initial = entry.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch { repository.renameCustomTheme(entry.id, name) }
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(strings[Keys.THEME_DELETE_THEME_TITLE]) },
            text = { Text(strings.getString(Keys.THEME_DELETE_THEME_MESSAGE, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { repository.deleteCustomTheme(entry.id) }
                }) { Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(strings[Keys.THEME_CANCEL]) }
            },
        )
    }
}

/** Asks for a short name, for "save this as" and "rename this". */
@Composable
private fun ThemeNameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(CustomThemeEntry.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text(strings[Keys.THEME_THEME_NAME_HINT]) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim().ifEmpty { strings[Keys.THEME_CUSTOM] }) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

/** The pattern names, as catalogue keys in the order of the PATTERN_ constants. */
private val PATTERN_LABELS = arrayOf(
    Keys.THEME_PATTERN_NONE,
    Keys.THEME_PATTERN_DOTS,
    Keys.THEME_PATTERN_GRID,
    Keys.THEME_PATTERN_DIAGONAL,
    Keys.THEME_PATTERN_CHECKS,
    Keys.THEME_PATTERN_STRIPES,
)

/** One preset, drawn as three dots: its background, its keys and its accent. */
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
            // The preset's label colour, on its own background.
            color = Color(preset.textColor),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A preset, the catalogue key for its name, and the family of look it belongs to. */
internal class Preset(val nameKey: String, val theme: KeyboardTheme, val category: ThemeCategory)

/** The families the preset row groups by, in the order the row shows them. */
internal enum class ThemeCategory(val labelKey: String) {
    CLASSIC(Keys.THEME_CATEGORY_CLASSIC),
    COOL(Keys.THEME_CATEGORY_COOL),
    NATURE(Keys.THEME_CATEGORY_NATURE),
    WARM(Keys.THEME_CATEGORY_WARM),
    MONOCHROME(Keys.THEME_CATEGORY_MONOCHROME),
    NEON(Keys.THEME_CATEGORY_NEON),
}

@Composable
private fun ThemeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    default: Float,
    onChange: (Float) -> Unit,
) {
    val strings = LocalStrings.current
    DefaultableSlider(
        label = strings.getString(Keys.THEME_TEXT, label, value.toInt(), unit),
        value = value,
        range = range,
        default = default,
        onChange = onChange,
    )
}


internal val LIGHT_THEME = KeyboardTheme(
    backgroundColor = 0xFFE6E6EE.toInt(),
    keyColor = 0xFFFFFFFF.toInt(),
    keyPressedColor = 0xFFC6C6D0.toInt(),
    modifierKeyColor = 0xFFD4D4DE.toInt(),
    textColor = 0xFF14141A.toInt(),
    secondaryTextColor = 0xFF5A5A6E.toInt(),
    // Darker than the dark theme's blue.
    accentColor = 0xFF1D4ED8.toInt(),
    swipeTrailColor = 0xCC1D4ED8.toInt(),
)

internal val HIGH_CONTRAST = KeyboardTheme(
    backgroundColor = 0xFF000000.toInt(),
    keyColor = 0xFF000000.toInt(),
    // A grey the white label stays readable on.
    keyPressedColor = 0xFF5A5A6E.toInt(),
    modifierKeyColor = 0xFF000000.toInt(),
    textColor = 0xFFFFFFFF.toInt(),
    secondaryTextColor = 0xFFC6C6D0.toInt(),
    accentColor = 0xFFF59E0B.toInt(),
    showKeyBorders = true,
    swipeTrailColor = 0xCCF59E0B.toInt(),
)

/** A deep blue, dark without being black, with a faint grid over it. */
internal val MIDNIGHT = KeyboardTheme(
    backgroundColor = 0xFF0B1020.toInt(),
    keyColor = 0xFF161E36.toInt(),
    keyPressedColor = 0xFF24304F.toInt(),
    modifierKeyColor = 0xFF101728.toInt(),
    textColor = 0xFFE8ECF8.toInt(),
    secondaryTextColor = 0xFF8E9AC0.toInt(),
    accentColor = 0xFF7AA2F7.toInt(),
    swipeTrailColor = 0xCC7AA2F7.toInt(),
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_GRID),
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
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_DIAGONAL),
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
    // A dark brown.
    accentColor = 0xFF8A5410.toInt(),
    swipeTrailColor = 0xCC8A5410.toInt(),
    keyCornerRadiusDp = 4f,
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_DOTS),
    patternColor = 0x14000000,
    patternScaleDp = 18f,
)

internal val MONO = KeyboardTheme(
    // Keys the colour of the background, separated by their outlines.
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
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_DOTS),
    patternColor = 0x1AFF3DCB,
    patternScaleDp = 22f,
)

internal val GLACIER = KeyboardTheme(
    // The light one among the cool presets.
    backgroundColor = 0xFFE3F1F6.toInt(),
    keyColor = 0xFFFFFFFF.toInt(),
    keyPressedColor = 0xFFB9DCE8.toInt(),
    modifierKeyColor = 0xFFD9EBF2.toInt(),
    textColor = 0xFF0F2733.toInt(),
    secondaryTextColor = 0xFF3E6B7A.toInt(),
    accentColor = 0xFF0E6E8C.toInt(),
    swipeTrailColor = 0xCC0E6E8C.toInt(),
)

internal val MOSS = KeyboardTheme(
    backgroundColor = 0xFF131C10.toInt(),
    keyColor = 0xFF1E2E1A.toInt(),
    keyPressedColor = 0xFF304826.toInt(),
    modifierKeyColor = 0xFF141F12.toInt(),
    textColor = 0xFFE8F3E0.toInt(),
    secondaryTextColor = 0xFF9BB88A.toInt(),
    accentColor = 0xFF8FCB6B.toInt(),
    swipeTrailColor = 0xCC8FCB6B.toInt(),
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_DIAGONAL),
    patternColor = 0x12FFFFFF,
    patternScaleDp = 22f,
)

internal val CLAY = KeyboardTheme(
    backgroundColor = 0xFF24140D.toInt(),
    keyColor = 0xFF3A2016.toInt(),
    keyPressedColor = 0xFF50301F.toInt(),
    modifierKeyColor = 0xFF2A1710.toInt(),
    textColor = 0xFFF7E9DE.toInt(),
    secondaryTextColor = 0xFFC79A82.toInt(),
    accentColor = 0xFFE07A3F.toInt(),
    swipeTrailColor = 0xCCE07A3F.toInt(),
    keyCornerRadiusDp = 6f,
)

internal val SLATE = KeyboardTheme(
    // Keys one step lighter than the background.
    backgroundColor = 0xFF1C1E23.toInt(),
    keyColor = 0xFF2B2E36.toInt(),
    keyPressedColor = 0xFF3E424C.toInt(),
    modifierKeyColor = 0xFF202329.toInt(),
    textColor = 0xFFEDEFF2.toInt(),
    secondaryTextColor = 0xFF9AA1AD.toInt(),
    accentColor = 0xFFB8C4D6.toInt(),
    swipeTrailColor = 0xCCB8C4D6.toInt(),
    keyCornerRadiusDp = 4f,
)

internal val AURORA = KeyboardTheme(
    backgroundColor = 0xFF060E0D.toInt(),
    keyColor = 0xFF0C1F1C.toInt(),
    keyPressedColor = 0xFF163530.toInt(),
    modifierKeyColor = 0xFF081513.toInt(),
    textColor = 0xFFE6FFF6.toInt(),
    secondaryTextColor = 0xFF6FE0C4.toInt(),
    accentColor = 0xFF2EE6B8.toInt(),
    swipeTrailColor = 0xCC2EE6B8.toInt(),
    keyCornerRadiusDp = 14f,
    backgroundPatterns = listOf(KeyboardTheme.PATTERN_DOTS),
    patternColor = 0x1A2EE6B8,
    patternScaleDp = 22f,
)

/**
 * The fifteen presets, grouped by [ThemeCategory] in the order the row shows the groups, and
 * within each group in the order it shows the cards.
 */
internal val PRESETS = listOf(
    Preset(Keys.THEME_DARK, KeyboardTheme(), ThemeCategory.CLASSIC),
    Preset(Keys.THEME_LIGHT, LIGHT_THEME, ThemeCategory.CLASSIC),
    Preset(Keys.THEME_HIGH_CONTRAST, HIGH_CONTRAST, ThemeCategory.CLASSIC),
    Preset(Keys.THEME_MIDNIGHT, MIDNIGHT, ThemeCategory.COOL),
    Preset(Keys.THEME_OCEAN, OCEAN, ThemeCategory.COOL),
    Preset(Keys.THEME_GLACIER, GLACIER, ThemeCategory.COOL),
    Preset(Keys.THEME_FOREST, FOREST, ThemeCategory.NATURE),
    Preset(Keys.THEME_MOSS, MOSS, ThemeCategory.NATURE),
    Preset(Keys.THEME_SUNSET, SUNSET, ThemeCategory.WARM),
    Preset(Keys.THEME_PAPER, PAPER, ThemeCategory.WARM),
    Preset(Keys.THEME_CLAY, CLAY, ThemeCategory.WARM),
    Preset(Keys.THEME_MONO, MONO, ThemeCategory.MONOCHROME),
    Preset(Keys.THEME_SLATE, SLATE, ThemeCategory.MONOCHROME),
    Preset(Keys.THEME_NEON, NEON, ThemeCategory.NEON),
    Preset(Keys.THEME_AURORA, AURORA, ThemeCategory.NEON),
)
