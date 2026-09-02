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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.i18n.Keys
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * The quick-action bar: whether it is shown, what shape it takes, where it sits, and which
 * buttons are on it in what order.
 *
 * Grouped into cards rather than run as one list, because the four questions are independent
 * and a reader should be able to answer one without holding the other three.
 */
@Composable
fun QuickActionsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())
    val update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit = { transform ->
        scope.launch { themes.updatePreferences(transform) }
    }
    var picking by remember { mutableStateOf(false) }
    val chosen = QuickAction.fromIds(preferences.quickActions)

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.QUICK_TITLE]) {
            SwitchRow(
                title = strings[Keys.QUICK_SHOW],
                subtitle = strings[Keys.QUICK_SHOW_NOTE],
                checked = preferences.quickActionsEnabled,
            ) { value -> update { it.copy(quickActionsEnabled = value) } }
        }

        SettingsSectionCard(strings[Keys.QUICK_MODE]) {
            ChipRow {
                ModeChip(strings[Keys.QUICK_MODE_FULL],
                    KeyboardPreferences.QUICK_ACTIONS_FULL, preferences.quickActionsMode) {
                    update { it.copy(quickActionsMode = it.quickActionsMode.let { _ ->
                        KeyboardPreferences.QUICK_ACTIONS_FULL }) }
                }
                ModeChip(strings[Keys.QUICK_MODE_COLLAPSED],
                    KeyboardPreferences.QUICK_ACTIONS_COLLAPSED, preferences.quickActionsMode) {
                    update { it.copy(
                        quickActionsMode = KeyboardPreferences.QUICK_ACTIONS_COLLAPSED) }
                }
            }
            Explanation(strings[Keys.QUICK_MODE_NOTE])
        }

        SettingsSectionCard(strings[Keys.QUICK_PLACEMENT]) {
            ChipRow {
                PlacementChip(strings[Keys.QUICK_PLACEMENT_ABOVE],
                    KeyboardPreferences.QUICK_ACTIONS_ABOVE_STRIP, preferences, update)
                PlacementChip(strings[Keys.QUICK_PLACEMENT_BELOW],
                    KeyboardPreferences.QUICK_ACTIONS_BELOW_KEYS, preferences, update)
            }
            ChipRow {
                PlacementChip(strings[Keys.QUICK_PLACEMENT_LEFT],
                    KeyboardPreferences.QUICK_ACTIONS_LEFT, preferences, update)
                PlacementChip(strings[Keys.QUICK_PLACEMENT_RIGHT],
                    KeyboardPreferences.QUICK_ACTIONS_RIGHT, preferences, update)
            }
            Explanation(strings[Keys.QUICK_PLACEMENT_NOTE])
        }

        SettingsSectionCard(strings[Keys.QUICK_BUTTONS]) {
            if (chosen.isEmpty()) {
                Explanation(strings[Keys.QUICK_NONE])
            }
            chosen.forEachIndexed { index, action ->
                ButtonRow(
                    action = action,
                    index = index,
                    onMoveTop = { update { current -> current.copy(quickActions = move(
                        current.quickActions, index, 0)) } },
                    onMoveUp = { update { current -> current.copy(quickActions = move(
                        current.quickActions, index, index - 1)) } },
                    onRemove = { update { current -> current.copy(quickActions =
                        current.quickActions.filterNot { it == action.id }) } },
                )
            }
            Explanation(strings[Keys.QUICK_BUTTONS_NOTE])
            if (chosen.size < KeyboardPreferences.MAX_QUICK_ACTIONS) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { picking = !picking }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        strings[Keys.QUICK_ADD],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (picking) {
                    for (action in QuickAction.entries) {
                        if (action in chosen) continue
                        ButtonRow(
                            action = action,
                            index = -1,
                            onAdd = {
                                update { current ->
                                    current.copy(quickActions = current.quickActions + action.id)
                                }
                                picking = false
                            },
                        )
                    }
                }
            }
        }

        SettingsSectionCard(strings[Keys.QUICK_CLEAR_CLIPBOARD]) {
            SwitchRow(
                title = strings[Keys.QUICK_CLEAR_CLIPBOARD],
                subtitle = strings[Keys.QUICK_CLEAR_CLIPBOARD_NOTE],
                checked = preferences.clearClipboardAfterInsert,
            ) { value -> update { it.copy(clearClipboardAfterInsert = value) } }
        }
    }
}

/**
 * One button, with its icon, and the controls that move it.
 *
 * Arrows rather than a drag handle: dragging inside a scrolling column needs the list to own
 * the scroll, and this screen is a plain column of cards. Two buttons -- to the front, and up
 * one -- reach any order in a list of ten without a gesture that fights the scroll.
 */
@Composable
private fun ButtonRow(
    action: QuickAction,
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
                    painter = painterResource(R.drawable.bk_action_cursor_start),
                    contentDescription = strings[Keys.QUICK_MOVE_TOP],
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_action_undo),
                    contentDescription = strings[Keys.QUICK_MOVE_UP],
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_action_delete_word),
                    contentDescription = strings[Keys.QUICK_REMOVE],
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ModeChip(label: String, value: Int, current: Int, onPick: () -> Unit) {
    FilterChip(selected = current == value, onClick = onPick, label = { Text(label) })
}

@Composable
private fun PlacementChip(
    label: String,
    value: Int,
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    FilterChip(
        selected = preferences.quickActionsPlacement == value,
        onClick = { update { it.copy(quickActionsPlacement = value) } },
        label = { Text(label) },
    )
}

/** Moves [from] to [to], clamped, and returns the new order. */
private fun move(ids: List<Int>, from: Int, to: Int): List<Int> {
    if (from !in ids.indices) {
        return ids
    }
    val target = to.coerceIn(0, ids.size - 1)
    val mutable = ids.toMutableList()
    mutable.add(target, mutable.removeAt(from))
    return mutable
}

private fun iconFor(action: QuickAction): Int = when (action) {
    QuickAction.COPY_PREVIOUS_WORD -> R.drawable.bk_action_copy_previous_word
    QuickAction.COPY_LINE -> R.drawable.bk_action_copy_line
    QuickAction.COPY_ALL -> R.drawable.bk_action_copy_all
    QuickAction.PASTE -> R.drawable.bk_action_paste
    QuickAction.CLIPBOARD_HISTORY -> R.drawable.bk_action_clipboard_history
    QuickAction.SELECT_ALL -> R.drawable.bk_action_select_all
    QuickAction.CUT -> R.drawable.bk_action_cut
    QuickAction.SELECT_WORD -> R.drawable.bk_action_select_word
    QuickAction.DELETE_WORD -> R.drawable.bk_action_delete_word
    QuickAction.CURSOR_START -> R.drawable.bk_action_cursor_start
    QuickAction.CURSOR_END -> R.drawable.bk_action_cursor_end
    QuickAction.NEWLINE -> R.drawable.bk_action_newline
    QuickAction.SWITCH_LAYOUT -> R.drawable.bk_action_switch_layout
    QuickAction.SETTINGS -> R.drawable.bk_action_settings
    QuickAction.UNDO -> R.drawable.bk_action_undo
}

private fun labelFor(action: QuickAction): String = when (action) {
    QuickAction.COPY_PREVIOUS_WORD -> Keys.ACTION_COPY_PREVIOUS_WORD
    QuickAction.COPY_LINE -> Keys.ACTION_COPY_LINE
    QuickAction.COPY_ALL -> Keys.ACTION_COPY_ALL
    QuickAction.PASTE -> Keys.ACTION_PASTE
    QuickAction.CLIPBOARD_HISTORY -> Keys.ACTION_CLIPBOARD_HISTORY
    QuickAction.SELECT_ALL -> Keys.ACTION_SELECT_ALL
    QuickAction.CUT -> Keys.ACTION_CUT
    QuickAction.SELECT_WORD -> Keys.ACTION_SELECT_WORD
    QuickAction.DELETE_WORD -> Keys.ACTION_DELETE_WORD
    QuickAction.CURSOR_START -> Keys.ACTION_CURSOR_START
    QuickAction.CURSOR_END -> Keys.ACTION_CURSOR_END
    QuickAction.NEWLINE -> Keys.ACTION_NEWLINE
    QuickAction.SWITCH_LAYOUT -> Keys.ACTION_SWITCH_LAYOUT
    QuickAction.SETTINGS -> Keys.ACTION_SETTINGS
    QuickAction.UNDO -> Keys.ACTION_UNDO
}
