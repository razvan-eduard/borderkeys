// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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

        SectionHeader("Height")
        Explanation(
            "Bigger keys are easier to hit and leave less of the app visible. There is no right " +
                "answer; there is only the one that fits your thumb.",
        )
        LabelledSlider(
            value = preferences.heightScale,
            range = KeyboardPreferences.MIN_HEIGHT_SCALE..KeyboardPreferences.MAX_HEIGHT_SCALE,
            label = "${(preferences.heightScale * 100).toInt()}%",
        ) { value -> update { it.copy(heightScale = value) } }

        SectionHeader("Position")
        Explanation(
            "One-handed mode narrows the keyboard and pushes it to one side, so every key is " +
                "inside a thumb's arc. Left and right are separate settings rather than a " +
                "handedness switch, because people change hands.",
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            ModeChip("Docked", KeyboardPreferences.MODE_DOCKED, preferences.positionMode, ::update)
            ModeChip("Left", KeyboardPreferences.MODE_ONE_HANDED_LEFT, preferences.positionMode,
                ::update)
            ModeChip("Right", KeyboardPreferences.MODE_ONE_HANDED_RIGHT, preferences.positionMode,
                ::update)
            ModeChip("Floating", KeyboardPreferences.MODE_FLOATING, preferences.positionMode,
                ::update)
        }

        if (preferences.positionMode != KeyboardPreferences.MODE_DOCKED) {
            SectionHeader("Width")
            LabelledSlider(
                value = preferences.widthScale,
                range = KeyboardPreferences.MIN_WIDTH_SCALE..1f,
                label = "${(preferences.widthScale * 100).toInt()}%",
            ) { value -> update { it.copy(widthScale = value) } }

            SectionHeader("Distance from the bottom edge")
            Explanation(
                "Lifts the keyboard off the bottom of the screen — useful next to a gesture bar, " +
                    "or when the app puts something under it.",
            )
            LabelledSlider(
                value = preferences.bottomOffsetDp,
                range = 0f..KeyboardPreferences.MAX_BOTTOM_OFFSET_DP,
                label = "${preferences.bottomOffsetDp.toInt()} dp",
            ) { value -> update { it.copy(bottomOffsetDp = value) } }
        }

        if (preferences.positionMode == KeyboardPreferences.MODE_FLOATING) {
            SectionHeader("Horizontal position")
            LabelledSlider(
                value = preferences.horizontalOffsetDp,
                range = -160f..160f,
                label = "${preferences.horizontalOffsetDp.toInt()} dp",
            ) { value -> update { it.copy(horizontalOffsetDp = value) } }
        }

        SectionHeader("Number row")
        SwitchRow(
            title = "Show a row of digits",
            subtitle = "Costs about a fifth of the keyboard's height. Without it the digits are " +
                "on the ?123 page; the letters keep their long presses for diacritics either way.",
            checked = preferences.numberRow,
        ) { value -> update { it.copy(numberRow = value) } }
        SwitchRow(
            title = "Number pad in numeric fields",
            subtitle = "A phone number field gets a keypad rather than a QWERTY with the digits " +
                "hidden behind a key.",
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
        ) { Text("Reset size and position") }
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
