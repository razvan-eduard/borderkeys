// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.KeyboardPreview
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * Where the keyboard is and how big.
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
    val theme by repository.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by repository.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { repository.updatePreferences(transform) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KeyboardPreview(theme, preferences, Modifier.padding(vertical = 12.dp))
        Divider()

        SectionHeader(strings[Keys.SIZE_HEIGHT])
        Explanation(
            strings[Keys.SIZE_BIGGER_KEYS_ARE_EASIER_TO_HIT],
        )
        LabelledSlider(
            value = preferences.heightScale,
            range = KeyboardPreferences.MIN_HEIGHT_SCALE..KeyboardPreferences.MAX_HEIGHT_SCALE,
            label = strings.getString(Keys.SIZE_TEXT_2, (preferences.heightScale * 100).toInt()),
        ) { value -> update { it.copy(heightScale = value) } }

        SectionHeader(strings[Keys.SIZE_POSITION])
        Explanation(
            strings[Keys.SIZE_ONE_HANDED_MODE_NARROWS_THE_KEYBOARD],
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(strings[Keys.SIZE_DOCKED], KeyboardPreferences.MODE_DOCKED, preferences.positionMode, ::update)
            ModeChip(strings[Keys.SIZE_LEFT], KeyboardPreferences.MODE_ONE_HANDED_LEFT, preferences.positionMode,
                ::update)
            ModeChip(strings[Keys.SIZE_RIGHT], KeyboardPreferences.MODE_ONE_HANDED_RIGHT, preferences.positionMode,
                ::update)
            ModeChip(strings[Keys.SIZE_FLOATING], KeyboardPreferences.MODE_FLOATING, preferences.positionMode,
                ::update)
        }

        if (preferences.positionMode != KeyboardPreferences.MODE_DOCKED) {
            SectionHeader(strings[Keys.SIZE_WIDTH])
            LabelledSlider(
                value = preferences.widthScale,
                range = KeyboardPreferences.MIN_WIDTH_SCALE..1f,
                label = strings.getString(Keys.SIZE_TEXT, (preferences.widthScale * 100).toInt()),
            ) { value -> update { it.copy(widthScale = value) } }

            SectionHeader(strings[Keys.SIZE_DISTANCE_FROM_THE_BOTTOM_EDGE])
            Explanation(
                strings[Keys.SIZE_LIFTS_THE_KEYBOARD_OFF_THE_BOTTOM],
            )
            LabelledSlider(
                value = preferences.bottomOffsetDp,
                range = 0f..KeyboardPreferences.MAX_BOTTOM_OFFSET_DP,
                label = strings.getString(Keys.SIZE_DP_2, preferences.bottomOffsetDp.toInt()),
            ) { value -> update { it.copy(bottomOffsetDp = value) } }
        }

        if (preferences.positionMode == KeyboardPreferences.MODE_FLOATING) {
            SectionHeader(strings[Keys.SIZE_HORIZONTAL_POSITION])
            LabelledSlider(
                value = preferences.horizontalOffsetDp,
                range = -160f..160f,
                label = strings.getString(Keys.SIZE_DP, preferences.horizontalOffsetDp.toInt()),
            ) { value -> update { it.copy(horizontalOffsetDp = value) } }
        }

        if (preferences.positionMode != KeyboardPreferences.MODE_DOCKED) {
            SectionHeader(strings[Keys.SIZE_THE_SPACE_BESIDE_THE_KEYS])
            SwitchRow(
                title = strings[Keys.SIZE_ARROW_TO_MOVE_IT_ACROSS],
                subtitle = strings[Keys.SIZE_AN_ARROW_IN_THE_EMPTY_STRIP],
                checked = preferences.edgeArrows,
            ) { value -> update { it.copy(edgeArrows = value) } }
            SwitchRow(
                title = strings[Keys.SIZE_BLUR_WHAT_SHOWS_THROUGH],
                subtitle = strings[Keys.SIZE_BLURS_THE_APPLICATION_BEHIND_THE_EMPTY],
                checked = preferences.blurBehindKeyboard,
            ) { value -> update { it.copy(blurBehindKeyboard = value) } }
            Divider()
        }

        SectionHeader(strings[Keys.SIZE_NUMBER_ROW])
        SwitchRow(
            title = strings[Keys.SIZE_SHOW_A_ROW_OF_DIGITS],
            subtitle = strings[Keys.SIZE_COSTS_ABOUT_A_FIFTH_OF_THE],
            checked = preferences.numberRow,
        ) { value -> update { it.copy(numberRow = value) } }
        SwitchRow(
            title = strings[Keys.SIZE_NUMBER_PAD_IN_NUMERIC_FIELDS],
            subtitle = strings[Keys.SIZE_A_PHONE_NUMBER_FIELD_GETS_A],
            checked = preferences.numericKeypad,
        ) { value -> update { it.copy(numericKeypad = value) } }

        Divider()
        TextButton(
            onClick = {
                update {
                    it.copy(
                        heightScale = 1f, widthScale = 1f,
                        positionMode = KeyboardPreferences.MODE_DOCKED,
                        bottomOffsetDp = 0f, horizontalOffsetDp = 0f,
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(strings[Keys.SIZE_RESET_SIZE_AND_POSITION]) }
    }
}

@Composable
private fun ModeChip(
    label: String,
    mode: Int,
    current: Int,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    FilterChip(
        selected = current == mode,
        onClick = { update { it.withPositionMode(mode) } },
        label = { Text(label) },
    )
}

@Composable
private fun LabelledSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(range),
            valueRange = range,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
