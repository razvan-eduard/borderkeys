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
import com.borderkeys.settings.SettingsSectionCard
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

        SettingsSectionCard(strings[Keys.CLIPBOARD_HOW_MANY_ITEMS]) {
            StepSlider(
                label = strings.getString(
                    Keys.CLIPBOARD_ITEMS, preferences.clipboardMaxEntries,
                ),
                steps = KeyboardPreferences.HISTORY_SIZE_STEPS,
                current = preferences.clipboardMaxEntries,
            ) { value ->
                scope.launch {
                    themes.updatePreferences { it.copy(clipboardMaxEntries = value) }
                }
            }
            Explanation(strings[Keys.CLIPBOARD_SIZE_NOTE])
        }

        SettingsSectionCard(strings[Keys.CLIPBOARD_KEEP_UNPINNED_ITEMS_FOR]) {
            StepSlider(
                label = formatRetention(strings, preferences.clipboardRetentionMinutes),
                steps = KeyboardPreferences.RETENTION_STEPS,
                current = preferences.clipboardRetentionMinutes,
            ) { value ->
                scope.launch {
                    themes.updatePreferences { it.copy(clipboardRetentionMinutes = value) }
                }
            }
            Explanation(strings[Keys.CLIPBOARD_RETENTION_NOTE])
            Explanation(strings[Keys.CLIPBOARD_EXPIRED_ITEMS_ARE_DELETED_NOT_MERELY])
        }
        SettingsSectionCard(strings.getString(Keys.CLIPBOARD_HISTORY, entries.size)) {
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
            TextButton(
                onClick = { scope.launch { repository.deleteAll() } },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(strings[Keys.CLIPBOARD_DELETE_EVERYTHING_INCLUDING_PINNED]) }
        }
    }
}

private fun formatRetention(strings: LanguageManager, minutes: Int): String {
    val day = 24 * 60
    return when {
        minutes < 60 -> strings.getString(Keys.CLIPBOARD_MINUTES, minutes)
        minutes == 60 -> strings[Keys.CLIPBOARD_1_HOUR]
        minutes < day -> if (minutes % 60 == 0) {
            strings.getString(Keys.CLIPBOARD_HOURS, minutes / 60)
        } else {
            strings.getString(Keys.CLIPBOARD_H_MIN, minutes / 60, minutes % 60)
        }
        minutes == day -> strings[Keys.CLIPBOARD_1_DAY]
        minutes == 7 * day -> strings[Keys.CLIPBOARD_1_WEEK]
        minutes == 30 * day -> strings[Keys.CLIPBOARD_1_MONTH]
        minutes % (7 * day) == 0 -> strings.getString(Keys.CLIPBOARD_WEEKS, minutes / (7 * day))
        else -> strings.getString(Keys.CLIPBOARD_DAYS, minutes / day)
    }
}

/**
 * A slider that moves between named values rather than across a range.
 *
 * The useful span for both of these settings covers three orders of magnitude, and a linear
 * slider over that cannot be aimed: most of the travel lands on differences nobody can tell
 * apart, and the interesting end is a few pixels wide. Stepping through a short list of the
 * answers someone actually has makes every position mean something.
 */
@Composable
private fun StepSlider(
    label: String,
    steps: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Slider(
        value = KeyboardPreferences.nearestStep(steps, current).toFloat(),
        valueRange = 0f..(steps.size - 1).toFloat(),
        steps = (steps.size - 2).coerceAtLeast(0),
        onValueChange = { value -> onPick(steps[value.toInt().coerceIn(steps.indices)]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
}
