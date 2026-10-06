// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater

/** Animations: the switch that stops every animation, each one's own switch, and the event effects. */
@Composable
fun AnimationsScreen(modifier: Modifier = Modifier) {
    val repository = remember { DataGraph.themes }
    val updateTheme = rememberThemeUpdater()
    val updatePreferences = rememberPreferencesUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EventEffectsSection(
            effects = appearance.preferences.effects,
            customColours = appearance.theme.customColours,
            onCustomColoursChange = { key, colours ->
                updateTheme { t -> t.copy(customColours = t.customColours + (key to colours)) }
            },
            onChange = { change ->
                updatePreferences { it.copy(effects = change(it.effects)) }
            },
        )
    }
}
