// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.ComposerAction
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.move
import com.borderkeys.settings.rememberPreferencesUpdater

/**
 * The draft box: whether it can be opened, what is on its bar, and which instructions were kept.
 *
 * The reordering is the quick-action bar's, arrows and all, for the reason given there: dragging
 * inside a scrolling column needs the list to own the scroll, and two buttons reach any order in
 * a list this short without a gesture that fights it.
 */
@Composable
fun ComposerScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    var picking by remember { mutableStateOf(false) }
    val chosen = ComposerAction.fromIds(preferences.composerBar)

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.COMPOSER_SETTINGS_ENABLE]) {
            SwitchRow(
                title = strings[Keys.COMPOSER_SETTINGS_ENABLE],
                subtitle = strings[Keys.COMPOSER_SETTINGS_ENABLE_NOTE],
                checked = preferences.composerEnabled,
            ) { value -> update { it.copy(composerEnabled = value) } }
        }

        // Everything below describes the box. With the box switched off it would be a screen
        // of settings for something that cannot happen.
        if (!preferences.composerEnabled) {
            return@Column
        }

        SettingsSectionCard(strings[Keys.COMPOSER_SETTINGS_TEXT_SIZE]) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    strings[Keys.COMPOSER_TEXT_SIZE_SMALL],
                    preferences.composerTextSize == KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL,
                ) { update { it.copy(composerTextSize = KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL) } }
                PickerChip(
                    strings[Keys.COMPOSER_TEXT_SIZE_MEDIUM],
                    preferences.composerTextSize == KeyboardPreferences.COMPOSER_TEXT_SIZE_MEDIUM,
                ) { update { it.copy(composerTextSize = KeyboardPreferences.COMPOSER_TEXT_SIZE_MEDIUM) } }
                PickerChip(
                    strings[Keys.COMPOSER_TEXT_SIZE_LARGE],
                    preferences.composerTextSize == KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE,
                ) { update { it.copy(composerTextSize = KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE) } }
            }
        }

        SettingsSectionCard(strings[Keys.COMPOSER_SETTINGS_SELECTION]) {
            SwitchRow(
                title = strings[Keys.COMPOSER_SETTINGS_SNAP_SELECTION],
                subtitle = strings[Keys.COMPOSER_SETTINGS_SNAP_SELECTION_NOTE],
                checked = preferences.composerSnapSelectionToWords,
            ) { value -> update { it.copy(composerSnapSelectionToWords = value) } }
        }

        SettingsSectionCard(strings[Keys.COMPOSER_SETTINGS_BAR]) {
            if (chosen.isEmpty()) {
                Explanation(strings[Keys.COMPOSER_SETTINGS_NONE])
            }
            chosen.forEachIndexed { index, action ->
                BarRow(
                    action = action,
                    index = index,
                    onMoveTop = {
                        update { current ->
                            current.copy(composerBar = move(current.composerBar, index, 0))
                        }
                    },
                    onMoveUp = {
                        update { current ->
                            current.copy(composerBar = move(current.composerBar, index, index - 1))
                        }
                    },
                    onRemove = {
                        update { current ->
                            current.copy(composerBar = current.composerBar.filterNot { it == action.id })
                        }
                    },
                )
            }
            Explanation(strings[Keys.COMPOSER_SETTINGS_BAR_NOTE])
            if (chosen.size < ComposerAction.entries.size) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { picking = !picking }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        strings[Keys.COMPOSER_SETTINGS_ADD],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (picking) {
                    for (action in ComposerAction.entries) {
                        if (action in chosen) continue
                        BarRow(
                            action = action,
                            index = -1,
                            onAdd = {
                                update { current ->
                                    current.copy(composerBar = current.composerBar + action.id)
                                }
                                picking = false
                            },
                        )
                    }
                }
            }
        }

        SettingsSectionCard(strings[Keys.COMPOSER_SETTINGS_SAVED]) {
            if (preferences.savedPrompts.isEmpty()) {
                Explanation(strings[Keys.COMPOSER_SETTINGS_NO_SAVED])
            }
            for (prompt in preferences.savedPrompts) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(prompt.name, style = MaterialTheme.typography.bodyLarge)
                        // The whole instruction, not a preview of it: a button whose contents
                        // are a mystery is a button nobody presses twice.
                        Text(
                            prompt.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        update { current ->
                            current.copy(savedPrompts = current.savedPrompts - prompt)
                        }
                    }) { Text(strings[Keys.COMPOSER_SETTINGS_FORGET]) }
                }
            }
            Explanation(strings[Keys.COMPOSER_SETTINGS_SAVED_NOTE])
        }
    }
}

/** One button on the bar, with the controls that move it. */
@Composable
private fun BarRow(
    action: ComposerAction,
    index: Int,
    onMoveTop: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onRemove: () -> Unit = {},
    onAdd: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onAdd != null) Modifier.clickable(onClick = onAdd) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconFor(action)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            strings[labelFor(action)],
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (onAdd == null) {
            IconButton(onClick = onMoveTop, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_top),
                    contentDescription = strings[Keys.QUICK_MOVE_TOP],
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_up),
                    contentDescription = strings[Keys.QUICK_MOVE_UP],
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_remove),
                    contentDescription = strings[Keys.QUICK_REMOVE],
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun iconFor(action: ComposerAction): Int = when (action) {
    ComposerAction.GRAMMAR -> R.drawable.bk_composer_grammar
    ComposerAction.TRANSLATE -> R.drawable.bk_composer_translate
    ComposerAction.TONE -> R.drawable.bk_composer_tone
    ComposerAction.SHORTEN -> R.drawable.bk_composer_shorten
    ComposerAction.SUMMARISE -> R.drawable.bk_composer_summarise
    ComposerAction.KEEP_SELECTION -> R.drawable.bk_composer_crop
    ComposerAction.PROMPT -> R.drawable.bk_composer_prompt
    ComposerAction.SAVED_PROMPTS -> R.drawable.bk_composer_saved
    ComposerAction.SHOW_ORIGINAL -> R.drawable.bk_composer_original
    ComposerAction.INSERT -> R.drawable.bk_composer_insert
}

private fun labelFor(action: ComposerAction): String = when (action) {
    ComposerAction.GRAMMAR -> Keys.COMPOSER_ACTION_GRAMMAR
    ComposerAction.TRANSLATE -> Keys.COMPOSER_ACTION_TRANSLATE
    ComposerAction.TONE -> Keys.COMPOSER_ACTION_TONE
    ComposerAction.SHORTEN -> Keys.COMPOSER_ACTION_SHORTEN
    ComposerAction.SUMMARISE -> Keys.COMPOSER_ACTION_SUMMARISE
    ComposerAction.KEEP_SELECTION -> Keys.COMPOSER_ACTION_KEEP_SELECTION
    ComposerAction.PROMPT -> Keys.COMPOSER_ACTION_PROMPT
    ComposerAction.SAVED_PROMPTS -> Keys.COMPOSER_ACTION_SAVED
    ComposerAction.SHOW_ORIGINAL -> Keys.COMPOSER_ACTION_SHOW_ORIGINAL
    ComposerAction.INSERT -> Keys.COMPOSER_INSERT
}
