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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun SwipeScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val theme by themes.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())
    var probe by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = strings[Keys.SWIPE_SWIPE_TYPING],
            subtitle = strings[Keys.SWIPE_DRAG_ACROSS_THE_LETTERS_INSTEAD_OF],
            checked = preferences.swipeEnabled,
        ) { value -> scope.launch { themes.updatePreferences { it.copy(swipeEnabled = value) } } }

        SettingsSectionCard(strings[Keys.SWIPE_THE_TRAIL]) {
            Text(
                strings.getString(Keys.SWIPE_WIDTH_DP, theme.swipeTrailWidthDp.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Slider(
                value = theme.swipeTrailWidthDp.coerceIn(1f, 24f),
                valueRange = 1f..24f,
                onValueChange = { value ->
                    scope.launch { themes.updateTheme { it.copy(swipeTrailWidthDp = value) } }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Explanation(strings[Keys.SWIPE_THE_COLOUR_IS_ON_THE_THEME])
        }
        SettingsSectionCard(strings[Keys.SWIPE_TRY_IT_HERE]) {
            OutlinedTextField(
                value = probe,
                onValueChange = { probe = it },
                label = { Text(strings[Keys.SWIPE_SWIPE_A_WORD]) },
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
            Explanation(strings[Keys.SWIPE_A_REAL_FIELD_NOTHING_TYPED_INTO])
        }
        SettingsSectionCard(strings[Keys.SWIPE_HOW_IT_DECODES]) {
            Explanation(
                strings[Keys.SWIPE_YOUR_GESTURE_IS_SMOOTHED_REDUCED_TO],
            )
            Explanation(
                strings[Keys.SWIPE_ALL_OF_IT_RUNS_ON_THIS],
            )
            if (!preferences.swipeEnabled) {
                Explanation(strings[Keys.SWIPE_SWIPE_TYPING_IS_CURRENTLY_OFF_SO])
            }
        }
    }
}
