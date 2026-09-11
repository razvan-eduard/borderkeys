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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * Where the keyboard is and how big -- once per orientation.
 *
 * A `heightScale` tuned to look right on a tall portrait screen is proportionally huge on a
 * landscape screen a third the height, so portrait and landscape each keep their own complete
 * set of values behind a tab, rather than sharing one that can only be right for one of them.
 * [KeyboardPreferences] keeps portrait's values flat, on itself, and landscape's in the nested
 * [KeyboardPlacement] at [KeyboardPreferences.landscape] -- see that field for why the split is
 * there rather than two equal nested objects.
 *
 * Every control writes to the DataStore and the preview redraws from the flow, so what is on
 * screen is what was stored -- and because the input method reads the same flow, a keyboard that
 * happens to be visible in another app moves at the same moment. That is the "live" part: not an
 * animation, but the absence of an apply button and of a second copy of the value.
 */
@Composable
fun SizeScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, preferences) = appearance

    // Which tab is open, not which way the phone is actually held right now -- landscape can be
    // tuned while holding the phone upright, the same way [PlacementPreview] renders whichever
    // tab is selected rather than the live orientation.
    var landscapeTab by remember { mutableStateOf(false) }
    val placement = preferences.placementFor(landscapeTab)

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { repository.updatePreferences(transform) }
    }

    fun updatePlacement(transform: (KeyboardPlacement) -> KeyboardPlacement) {
        update { it.withPlacement(landscapeTab, transform) }
    }

    // The preview is outside the scrolling column, so it stays on screen while the controls
    // under it are scrolled. A preview that scrolls away is a preview you cannot see while
    // you are changing the thing it previews, which is the only moment it is for.
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
                    ModeChip(strings[Keys.SIZE_DOCKED], KeyboardPreferences.MODE_DOCKED,
                        placement.positionMode, landscapeTab, ::update)
                    ModeChip(strings[Keys.SIZE_LEFT], KeyboardPreferences.MODE_ONE_HANDED_LEFT,
                        placement.positionMode, landscapeTab, ::update)
                    ModeChip(strings[Keys.SIZE_RIGHT], KeyboardPreferences.MODE_ONE_HANDED_RIGHT,
                        placement.positionMode, landscapeTab, ::update)
                    ModeChip(strings[Keys.SIZE_FLOATING], KeyboardPreferences.MODE_FLOATING,
                        placement.positionMode, landscapeTab, ::update)
                }

                // Not gated on the position mode: the dock honours the width too, so that a side
                // resize handle does something in the mode most people are in.
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

                // Only the floating keyboard can be moved sideways; in every other mode the
                // position is the mode, so a slider here would be a control with nothing to
                // control.
                if (placement.positionMode == KeyboardPreferences.MODE_FLOATING) {
                    DefaultableSlider(
                        value = placement.horizontalOffsetDp,
                        range = -160f..160f,
                        label = strings.getString(Keys.SIZE_DP, placement.horizontalOffsetDp.toInt()),
                        default = defaultPlacement(landscapeTab).horizontalOffsetDp,
                    ) { value -> updatePlacement { it.copy(horizontalOffsetDp = value) } }
                }
            }
            // Not per-orientation: edge arrows and the blur behind the keyboard are a gutter
            // treatment, the same on both sides of a rotation, not a size or position value.
            SettingsSectionCard(strings[Keys.SIZE_THE_SPACE_BESIDE_THE_KEYS]) {
                SwitchRow(
                    title = strings[Keys.SIZE_ARROW_TO_MOVE_IT_ACROSS],
                    subtitle = strings[Keys.SIZE_AN_ARROW_IN_THE_EMPTY_STRIP],
                    checked = preferences.edgeArrows,
                ) { value -> update { it.copy(edgeArrows = value) } }
                // Only when something can show through. With the background reaching both edges
                // there is nothing behind the gutter to blur, and a switch that does nothing is
                // worse than a switch that is not there.
                if (!theme.fullWidthBackground) {
                    SwitchRow(
                        title = strings[Keys.SIZE_BLUR_WHAT_SHOWS_THROUGH],
                        subtitle = strings[Keys.SIZE_BLURS_THE_APPLICATION_BEHIND_THE_EMPTY],
                        checked = preferences.blurBehindKeyboard,
                    ) { value -> update { it.copy(blurBehindKeyboard = value) } }
                }
            }
            SettingsSectionCard(strings[Keys.SIZE_RESET]) {
                TextButton(
                    onClick = { updatePlacement { defaultPlacement(landscapeTab) } },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SIZE_RESET_SIZE_AND_POSITION]) }
            }
        }
    }
}

/**
 * What Reset puts back, and what each slider's own "x" compares against -- [KeyboardPlacement]'s
 * own no-arg default for landscape (its class default is already the smaller, landscape-shaped
 * one), portrait's explicit `heightScale = 1f` for portrait, since the class default is
 * landscape's.
 */
private fun defaultPlacement(isLandscape: Boolean): KeyboardPlacement =
    if (isLandscape) KeyboardPlacement() else KeyboardPlacement(heightScale = 1f)

@Composable
private fun ModeChip(
    label: String,
    mode: Int,
    current: Int,
    isLandscape: Boolean,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { update { it.withPositionMode(mode, isLandscape) } },
        label = { Text(label) },
    )
}
