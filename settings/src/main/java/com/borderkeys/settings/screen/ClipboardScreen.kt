// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * The clipboard history, and how long it lives.
 *
 * The most sensitive table in the application: what people copy on a phone is routinely a
 * password, a code or an address. The default retention is an hour, expiry is a DELETE rather
 * than a filter, and there is a button here that empties it now.
 */
@Composable
fun ClipboardScreen(modifier: Modifier = Modifier) {
    val repository = remember { DataGraph.clipboard }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val entries by repository.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = "Remember what you copy",
            subtitle = "Only while BorderKeys is the keyboard in use. The platform delivers " +
                "clipboard changes to the focused input method, which is why this needs no " +
                "permission.",
            checked = preferences.clipboardEnabled,
        ) { value ->
            scope.launch { themes.updatePreferences { it.copy(clipboardEnabled = value) } }
        }

        Divider()
        SectionHeader("Keep unpinned items for")
        Text(
            formatRetention(preferences.clipboardRetentionMinutes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Slider(
            value = preferences.clipboardRetentionMinutes.toFloat().coerceIn(1f, 1440f),
            valueRange = 1f..1440f,
            onValueChange = { value ->
                scope.launch {
                    themes.updatePreferences { it.copy(clipboardRetentionMinutes = value.toInt()) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Explanation(
            "Expired items are deleted, not merely hidden. Filtering them out of the list would " +
                "leave the text sitting in the database for anything that got hold of the file.",
        )

        Divider()
        SectionHeader("History (${entries.size})")
        if (entries.isEmpty()) {
            SettingRow(title = "Empty")
        }
        for (entry in entries) {
            SettingRow(
                title = entry.content.take(80).replace('\n', ' '),
                subtitle = if (entry.isPinned) "Pinned — never expires" else "Expires on the timer",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch { repository.setPinned(entry.id, !entry.isPinned) }
                            },
                        ) { Text(if (entry.isPinned) "Unpin" else "Pin") }
                        TextButton(onClick = { scope.launch { repository.delete(entry.id) } }) {
                            Text("Delete")
                        }
                    }
                },
            )
        }

        Divider()
        TextButton(
            onClick = { scope.launch { repository.deleteAll() } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("Delete everything, including pinned") }
    }
}

private fun formatRetention(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
