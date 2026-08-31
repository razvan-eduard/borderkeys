// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

@Composable
fun SwipeScreen(modifier: Modifier = Modifier) {
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val theme by themes.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())
    var probe by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = "Swipe typing",
            subtitle = "Drag across the letters instead of tapping them.",
            checked = preferences.swipeEnabled,
        ) { value -> scope.launch { themes.updatePreferences { it.copy(swipeEnabled = value) } } }

        Divider()
        SectionHeader("The trail")
        Text(
            "Width — ${theme.swipeTrailWidthDp.toInt()} dp",
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
        Explanation("The colour is on the Theme screen, next to the rest of the palette.")

        Divider()
        SectionHeader("Try it here")
        OutlinedTextField(
            value = probe,
            onValueChange = { probe = it },
            label = { Text("Swipe a word") },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
        Explanation("A real field. Nothing typed into it is saved.")

        Divider()
        SectionHeader("How it decodes")
        Explanation(
            "Your gesture is smoothed, reduced to sixty-four evenly spaced points, and compared " +
                "against the ideal path through each candidate word's keys — once for its shape " +
                "with position and size normalised away, and once for where it actually happened " +
                "on the keyboard. Shape alone cannot tell \"were\" from \"tie\"; they trace " +
                "almost the same figure in different places.",
        )
        Explanation(
            "All of it runs on this device, in about a tenth of a millisecond, and the free " +
                "build contains no machine-learning model of any kind.",
        )
        if (!preferences.swipeEnabled) {
            Explanation("Swipe typing is currently off, so none of the above is running.")
        }
    }
}
