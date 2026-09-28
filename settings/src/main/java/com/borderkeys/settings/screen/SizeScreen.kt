// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPlacement
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater

/**
 * Where the keyboard is and how big, once per orientation, behind a tab: portrait's values are
 * flat on [KeyboardPreferences], landscape's in [KeyboardPreferences.landscape]. Every control
 * writes to the DataStore; the preview and the input method both redraw from the flow.
 */
@Composable
fun SizeScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val updateTheme = rememberThemeUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, preferences) = appearance

    // Which tab is open, not which way the phone is held.
    var landscapeTab by remember { mutableStateOf(false) }
    val placement = preferences.placementFor(landscapeTab)

    fun updatePlacement(transform: (KeyboardPlacement) -> KeyboardPlacement) {
        update { it.withPlacement(landscapeTab, transform) }
    }

    // The preview is outside the scrolling column, so it stays on screen.
    Column(modifier = modifier.fillMaxSize()) {
        PlacementPreview(appearance, Modifier.padding(vertical = 12.dp), isLandscape = landscapeTab)
        Divider()
        TabRow(selectedTabIndex = if (landscapeTab) 1 else 0) {
            Tab(
                selected = !landscapeTab,
                onClick = { landscapeTab = false },
                text = { Text(strings[Keys.SIZE_PORTRAIT]) },
            )
            Tab(
                selected = landscapeTab,
                onClick = { landscapeTab = true },
                text = { Text(strings[Keys.SIZE_LANDSCAPE]) },
            )
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSectionCard(strings[Keys.SIZE_HEIGHT_WIDTH_AND_POSITION]) {
                Explanation(strings[Keys.SIZE_BIGGER_KEYS_ARE_EASIER_TO_HIT])
                Explanation(strings[Keys.SIZE_RESIZE_BY_HAND])
                DefaultableSlider(
                    value = placement.heightScale,
                    range = KeyboardPreferences.MIN_HEIGHT_SCALE..KeyboardPreferences.MAX_HEIGHT_SCALE,
                    label = strings.getString(Keys.SIZE_TEXT_2, (placement.heightScale * 100).toInt()),
                    default = defaultPlacement(landscapeTab).heightScale,
                ) { value -> updatePlacement { it.copy(heightScale = value) } }

                Explanation(strings[Keys.SIZE_ONE_HANDED_MODE_NARROWS_THE_KEYBOARD])
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.SIZE_DOCKED],
                        placement.positionMode == KeyboardPreferences.MODE_DOCKED,
                    ) { update { it.withPositionMode(KeyboardPreferences.MODE_DOCKED, landscapeTab) } }
                    PickerChip(
                        strings[Keys.SIZE_LEFT],
                        placement.positionMode == KeyboardPreferences.MODE_ONE_HANDED_LEFT,
                    ) {
                        update {
                            it.withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_LEFT, landscapeTab)
                        }
                    }
                    PickerChip(
                        strings[Keys.SIZE_RIGHT],
                        placement.positionMode == KeyboardPreferences.MODE_ONE_HANDED_RIGHT,
                    ) {
                        update {
                            it.withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_RIGHT, landscapeTab)
                        }
                    }
                    PickerChip(
                        strings[Keys.SIZE_FLOATING],
                        placement.positionMode == KeyboardPreferences.MODE_FLOATING,
                    ) { update { it.withPositionMode(KeyboardPreferences.MODE_FLOATING, landscapeTab) } }
                }

                // Not gated on the position mode; the dock honours the width too.
                DefaultableSlider(
                    value = placement.widthScale,
                    range = KeyboardPreferences.MIN_WIDTH_SCALE..1f,
                    label = strings.getString(Keys.SIZE_TEXT, (placement.widthScale * 100).toInt()),
                    default = defaultPlacement(landscapeTab).widthScale,
                ) { value -> updatePlacement { it.copy(widthScale = value) } }

                Explanation(strings[Keys.SIZE_LIFTS_THE_KEYBOARD_OFF_THE_BOTTOM])
                DefaultableSlider(
                    value = placement.bottomOffsetDp,
                    range = 0f..KeyboardPreferences.MAX_BOTTOM_OFFSET_DP,
                    label = strings.getString(Keys.SIZE_DP_2, placement.bottomOffsetDp.toInt()),
                    default = defaultPlacement(landscapeTab).bottomOffsetDp,
                ) { value -> updatePlacement { it.copy(bottomOffsetDp = value) } }

                // Only the floating keyboard can be moved sideways.
                if (placement.positionMode == KeyboardPreferences.MODE_FLOATING) {
                    DefaultableSlider(
                        value = placement.horizontalOffsetDp,
                        range = -160f..160f,
                        label = strings.getString(Keys.SIZE_DP, placement.horizontalOffsetDp.toInt()),
                        default = defaultPlacement(landscapeTab).horizontalOffsetDp,
                    ) { value -> updatePlacement { it.copy(horizontalOffsetDp = value) } }
                }
                TextButton(
                    onClick = { updatePlacement { defaultPlacement(landscapeTab) } },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SIZE_RESET_SIZE_AND_POSITION]) }
                // The empty strip a narrowed keyboard leaves beside it: whether the background
                // fills it, whether the app behind is blurred when it does not, and the
                // reach-across arrow. The same in both orientations.
                AdvancedSection(strings[Keys.SIZE_ADVANCED_NOTE]) {
                    SectionHeader(strings[Keys.SIZE_THE_SPACE_BESIDE_THE_KEYS])
                    SwitchRow(
                        title = strings[Keys.THEME_FULL_WIDTH_BACKGROUND],
                        subtitle = strings[Keys.THEME_FULL_WIDTH_BACKGROUND_NOTE],
                        checked = theme.fullWidthBackground,
                    ) { value -> updateTheme { it.copy(fullWidthBackground = value) } }
                    // Disabled, with a note, while the background reaches both edges.
                    SwitchRow(
                        title = strings[Keys.SIZE_BLUR_WHAT_SHOWS_THROUGH],
                        subtitle = strings[Keys.SIZE_BLURS_THE_APPLICATION_BEHIND_THE_EMPTY],
                        checked = preferences.blurBehindKeyboard,
                        enabled = !theme.fullWidthBackground,
                    ) { value -> update { it.copy(blurBehindKeyboard = value) } }
                    if (theme.fullWidthBackground) {
                        Explanation(strings[Keys.SIZE_BLUR_NEEDS_NARROW_BACKGROUND])
                    }
                    SwitchRow(
                        title = strings[Keys.SIZE_ARROW_TO_MOVE_IT_ACROSS],
                        subtitle = strings[Keys.SIZE_AN_ARROW_IN_THE_EMPTY_STRIP],
                        checked = preferences.edgeArrows,
                    ) { value -> update { it.copy(edgeArrows = value) } }
                }
            }
        }
    }
}

/**
 * What Reset puts back and each slider's "x" compares against: [KeyboardPlacement]'s default for
 * landscape, the same with `heightScale = 1f` for portrait.
 */
private fun defaultPlacement(isLandscape: Boolean): KeyboardPlacement =
    if (isLandscape) KeyboardPlacement() else KeyboardPlacement(heightScale = 1f)

