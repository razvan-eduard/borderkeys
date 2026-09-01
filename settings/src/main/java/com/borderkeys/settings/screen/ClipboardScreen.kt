// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.LocalStrings

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
    val strings = LocalStrings.current
    val repository = remember { DataGraph.clipboard }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val entries by repository.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = strings[Keys.CLIPBOARD_REMEMBER_WHAT_YOU_COPY],
            subtitle = strings[Keys.CLIPBOARD_ONLY_WHILE_BORDERKEYS_IS_THE_KEYBOARD],
            checked = preferences.clipboardEnabled,
        ) { value ->
            scope.launch { themes.updatePreferences { it.copy(clipboardEnabled = value) } }
        }

        Divider()
        SectionHeader(strings[Keys.CLIPBOARD_KEEP_UNPINNED_ITEMS_FOR])
        Text(
            formatRetention(strings, preferences.clipboardRetentionMinutes),
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
            strings[Keys.CLIPBOARD_EXPIRED_ITEMS_ARE_DELETED_NOT_MERELY],
        )

        Divider()
        SectionHeader(strings.getString(Keys.CLIPBOARD_HISTORY, entries.size))
        if (entries.isEmpty()) {
            SettingRow(title = strings[Keys.CLIPBOARD_EMPTY])
        }
        for (entry in entries) {
            SettingRow(
                title = entry.content.take(80).replace('\n', ' '),
                subtitle = if (entry.isPinned) strings[Keys.CLIPBOARD_PINNED_NEVER_EXPIRES] else strings[Keys.CLIPBOARD_EXPIRES_ON_THE_TIMER],
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                scope.launch { repository.setPinned(entry.id, !entry.isPinned) }
                            },
                        ) { Text(if (entry.isPinned) strings[Keys.CLIPBOARD_UNPIN] else strings[Keys.CLIPBOARD_PIN]) }
                        TextButton(onClick = { scope.launch { repository.delete(entry.id) } }) {
                            Text(strings[Keys.CLIPBOARD_DELETE])
                        }
                    }
                },
            )
        }

        Divider()
        TextButton(
            onClick = { scope.launch { repository.deleteAll() } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(strings[Keys.CLIPBOARD_DELETE_EVERYTHING_INCLUDING_PINNED]) }
    }
}

private fun formatRetention(strings: LanguageManager, minutes: Int): String = when {
    minutes < 60 -> strings.getString(Keys.CLIPBOARD_MINUTES, minutes)
    minutes == 60 -> strings[Keys.CLIPBOARD_1_HOUR]
    minutes % 60 == 0 -> strings.getString(Keys.CLIPBOARD_HOURS, minutes / 60)
    else -> strings.getString(Keys.CLIPBOARD_H_MIN, minutes / 60, minutes % 60)
}
