// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * The two ways a key press can be felt without being seen: a sound and a vibration.
 *
 * Both are independent of what the Size screen shows -- neither changes a key's shape or where
 * the keyboard sits, which is what put them here instead of in one of that screen's cards.
 */
@Composable
fun SoundScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val preferences by repository.preferences
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentPreferences() })

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { repository.updatePreferences(transform) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SCREEN_SOUND_AND_VIBRATION]) {
            SwitchRow(
                title = strings[Keys.SIZE_KEY_SOUND],
                subtitle = strings[Keys.SIZE_KEY_SOUND_NOTE],
                checked = preferences.keySound,
            ) { value -> update { it.copy(keySound = value) } }
            SwitchRow(
                title = strings[Keys.SOUND_HAPTIC_FEEDBACK],
                subtitle = strings[Keys.SOUND_HAPTIC_FEEDBACK_NOTE],
                checked = preferences.hapticFeedback,
            ) { value -> update { it.copy(hapticFeedback = value) } }
        }
    }
}
