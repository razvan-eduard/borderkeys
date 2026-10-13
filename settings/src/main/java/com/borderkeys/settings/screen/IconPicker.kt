// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import com.borderkeys.data.theme.CustomAction
import com.borderkeys.data.theme.CustomIcon
import com.borderkeys.data.theme.CustomQuickAction
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBar
import com.borderkeys.i18n.Keys
import com.borderkeys.keyboard.R
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SMALL_ICON_SIZE
import com.borderkeys.settings.SPACE_GUTTER
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SPACE_MEDIUM
import com.borderkeys.settings.SPACE_TINY
import com.borderkeys.settings.SPACE_XLARGE
import com.borderkeys.settings.SPACE_XSMALL
import com.borderkeys.settings.move

/** The drawable for a [CustomIcon]. */
internal fun iconFor(icon: CustomIcon): Int = when (icon) {
    CustomIcon.WAND -> R.drawable.bk_icon_wand
    CustomIcon.CHAT -> R.drawable.bk_icon_chat
    CustomIcon.STAR -> R.drawable.bk_icon_star
    CustomIcon.TAG -> R.drawable.bk_icon_tag
    CustomIcon.QUOTE -> R.drawable.bk_icon_quote
    CustomIcon.PENCIL -> R.drawable.bk_icon_pencil
    CustomIcon.BOOK -> R.drawable.bk_icon_book
    CustomIcon.GLOBE -> R.drawable.bk_icon_globe
    CustomIcon.LIGHTBULB -> R.drawable.bk_icon_lightbulb
    CustomIcon.FLAG -> R.drawable.bk_icon_flag
    CustomIcon.REFRESH -> R.drawable.bk_icon_refresh
    CustomIcon.CHECK -> R.drawable.bk_icon_check
    CustomIcon.MEGAPHONE -> R.drawable.bk_icon_megaphone
    CustomIcon.HEART -> R.drawable.bk_icon_heart
    CustomIcon.COMPASS -> R.drawable.bk_icon_compass
    CustomIcon.BOOKMARK -> R.drawable.bk_icon_bookmark
}

/** Every [CustomIcon], in a fixed grid. */
@Composable
internal fun IconPicker(selected: CustomIcon, onPick: (CustomIcon) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SPACE_MEDIUM)) {
        for (row in CustomIcon.entries.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM)) {
                for (icon in row) {
                    val isSelected = icon == selected
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(ICON_CHOICE_SIZE).clickable { onPick(icon) },
                    ) {
                        Icon(
                            painter = painterResource(iconFor(icon)),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(SPACE_LARGE),
                        )
                    }
                }
            }
        }
    }
}

/** [IconPicker] in a plain dialog: a title, the grid, a dismiss. */
@Composable
internal fun IconPickerDialog(selected: CustomIcon, onPick: (CustomIcon) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(SPACE_GUTTER)) {
                Text(
                    strings[Keys.COMPOSER_ICON_PICKER_TITLE],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = SPACE_XLARGE),
                )
                IconPicker(selected = selected, onPick = onPick)
            }
        }
    }
}

/**
 * Name, instruction, icon and an optional pin to the bar, in one form, for a custom action. With
 * [editing] the form is pre-filled with that action, name included; [pinnedInitially] is its bar
 * membership.
 */
@Composable
internal fun CreateCustomActionDialog(
    editing: CustomAction? = null,
    pinnedInitially: Boolean = false,
    onSave: (name: String, instruction: String, icon: CustomIcon, pinToBar: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var instruction by remember { mutableStateOf(editing?.instruction.orEmpty()) }
    var icon by remember { mutableStateOf(editing?.let { CustomIcon.fromId(it.icon) } ?: CustomIcon.DEFAULT) }
    var pinToBar by remember { mutableStateOf(if (editing != null) pinnedInitially else true) }
    var pickingIcon by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(SPACE_GUTTER)) {
                Text(
                    strings[if (editing != null) Keys.CUSTOM_ACTION_EDIT_TITLE else Keys.COMPOSER_CUSTOM_ACTION_TITLE],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = SPACE_XLARGE),
                )
                Box {
                    IconButton(onClick = { pickingIcon = true }) {
                        Icon(
                            painter = painterResource(iconFor(icon)),
                            contentDescription = strings[Keys.COMPOSER_ICON_PICK],
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pickingIcon) {
                        IconPickerDialog(
                            selected = icon,
                            onPick = { pickingIcon = false; icon = it },
                            onDismiss = { pickingIcon = false },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomAction.MAX_NAME_CHARS) },
                    placeholder = { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_NAME_HINT]) },
                    singleLine = true,
                    modifier = Modifier.padding(top = SPACE_MEDIUM),
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it.take(CustomAction.MAX_INSTRUCTION_CHARS) },
                    placeholder = { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_INSTRUCTION_HINT]) },
                    modifier = Modifier.padding(top = SPACE_MEDIUM),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pinToBar = !pinToBar }.padding(top = SPACE_MEDIUM),
                ) {
                    Checkbox(checked = pinToBar, onCheckedChange = { pinToBar = it })
                    Text(strings[Keys.COMPOSER_CUSTOM_ACTION_PIN_TO_BAR], style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = SPACE_LARGE),
                ) {
                    TextButton(onClick = onDismiss) { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_CANCEL]) }
                    TextButton(
                        enabled = name.isNotBlank() && instruction.isNotBlank(),
                        onClick = { onSave(name.trim(), instruction.trim(), icon, pinToBar) },
                    ) { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_SAVE]) }
                }
            }
        }
    }
}

/**
 * Name, icon, an ordered list of steps and an optional pin to the bar, for a macro on the quick
 * action bar.
 *
 * A step is a [QuickAction.macroEligible] builtin or a saved [CustomQuickAction] from [existing];
 * one that would close a reference cycle ([QuickActionBar.hasCycle]) is left off the list.
 * [editing] pre-fills the form as in [CreateCustomActionDialog]. Removing a step asks first.
 */
@Composable
internal fun CreateCustomQuickActionDialog(
    existing: List<CustomQuickAction>,
    editing: CustomQuickAction? = null,
    pinnedInitially: Boolean = false,
    onSave: (name: String, steps: List<Int>, icon: CustomIcon, pinToBar: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var steps by remember { mutableStateOf(editing?.steps ?: emptyList()) }
    var icon by remember { mutableStateOf(editing?.let { CustomIcon.fromId(it.icon) } ?: CustomIcon.DEFAULT) }
    var pinToBar by remember { mutableStateOf(if (editing != null) pinnedInitially else true) }
    var pickingIcon by remember { mutableStateOf(false) }
    var removingStepAt by remember { mutableStateOf(-1) }

    // 0 for a new action, never a real id (CustomQuickAction.nextId starts at NEW_ID_MIN).
    val selfId = editing?.id ?: 0
    val byId = existing.associateBy { it.id }

    fun iconForStep(id: Int): Int =
        QuickAction.fromId(id)?.let { iconFor(it) }
            ?: byId[id]?.let { iconFor(CustomIcon.fromId(it.icon)) }
            ?: R.drawable.bk_icon_wand

    fun labelForStep(id: Int): String =
        QuickAction.fromId(id)?.let { strings[labelFor(it)] } ?: byId[id]?.name.orEmpty()

    val addableBuiltins = QuickAction.entries.filter { it.macroEligible }
    val addableCustom = existing.filter { candidate ->
        candidate.id != selfId &&
            !QuickActionBar.hasCycle(
                CustomQuickAction(id = selfId, name = name, steps = steps + candidate.id),
                existing,
            )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(SPACE_GUTTER)) {
                Text(
                    strings[
                        if (editing != null) Keys.CUSTOM_ACTION_EDIT_TITLE else Keys.COMPOSER_CUSTOM_ACTION_TITLE,
                    ],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = SPACE_XLARGE),
                )
                Box {
                    IconButton(onClick = { pickingIcon = true }) {
                        Icon(
                            painter = painterResource(iconFor(icon)),
                            contentDescription = strings[Keys.COMPOSER_ICON_PICK],
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pickingIcon) {
                        IconPickerDialog(
                            selected = icon,
                            onPick = { pickingIcon = false; icon = it },
                            onDismiss = { pickingIcon = false },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomQuickAction.MAX_NAME_CHARS) },
                    placeholder = { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_NAME_HINT]) },
                    singleLine = true,
                    modifier = Modifier.padding(top = SPACE_MEDIUM),
                )
                Text(
                    strings[Keys.QUICK_CUSTOM_STEPS],
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = SPACE_XLARGE, bottom = SPACE_XSMALL),
                )
                if (steps.isEmpty()) {
                    Text(
                        strings[Keys.QUICK_CUSTOM_NO_STEPS],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                steps.forEachIndexed { index, stepId ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = SPACE_TINY),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(iconForStep(stepId)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(SMALL_ICON_SIZE),
                        )
                        Text(
                            labelForStep(stepId),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = SPACE_LARGE),
                        )
                        IconButton(
                            onClick = { steps = move(steps, index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(STEP_BUTTON_SIZE),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bk_reorder_up),
                                contentDescription = strings[Keys.QUICK_MOVE_UP],
                                modifier = Modifier.size(MINI_ICON_SIZE),
                            )
                        }
                        IconButton(onClick = { removingStepAt = index }, modifier = Modifier.size(STEP_BUTTON_SIZE)) {
                            Icon(
                                painter = painterResource(R.drawable.bk_reorder_remove),
                                contentDescription = strings[Keys.QUICK_REMOVE],
                                modifier = Modifier.size(MINI_ICON_SIZE),
                            )
                        }
                    }
                }
                if (steps.size < CustomQuickAction.MAX_STEPS) {
                    Text(
                        strings[Keys.QUICK_CUSTOM_ADD_STEP],
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = SPACE_LARGE, bottom = SPACE_XSMALL),
                    )
                    for (builtin in addableBuiltins) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { steps = steps + builtin.id }
                                .padding(vertical = SPACE_XSMALL),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(iconFor(builtin)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(SMALL_ICON_SIZE),
                            )
                            Text(
                                strings[labelFor(builtin)],
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = SPACE_LARGE),
                            )
                        }
                    }
                    for (custom in addableCustom) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { steps = steps + custom.id }
                                .padding(vertical = SPACE_XSMALL),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(iconFor(CustomIcon.fromId(custom.icon))),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(SMALL_ICON_SIZE),
                            )
                            Text(
                                custom.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = SPACE_LARGE),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pinToBar = !pinToBar }.padding(top = SPACE_LARGE),
                ) {
                    Checkbox(checked = pinToBar, onCheckedChange = { pinToBar = it })
                    Text(strings[Keys.COMPOSER_CUSTOM_ACTION_PIN_TO_BAR], style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = SPACE_LARGE),
                ) {
                    TextButton(onClick = onDismiss) { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_CANCEL]) }
                    TextButton(
                        enabled = name.isNotBlank() && steps.isNotEmpty(),
                        onClick = { onSave(name.trim(), steps, icon, pinToBar) },
                    ) { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_SAVE]) }
                }
            }
        }
    }

    if (removingStepAt >= 0) {
        val stepId = steps[removingStepAt]
        AlertDialog(
            onDismissRequest = { removingStepAt = -1 },
            title = { Text(strings[Keys.QUICK_CUSTOM_REMOVE_STEP_TITLE]) },
            text = { Text(strings.getString(Keys.QUICK_CUSTOM_REMOVE_STEP_MESSAGE, labelForStep(stepId))) },
            confirmButton = {
                TextButton(onClick = {
                    steps = steps.filterIndexed { index, _ -> index != removingStepAt }
                    removingStepAt = -1
                }) { Text(strings[Keys.THEME_DELETE], color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removingStepAt = -1 }) { Text(strings[Keys.THEME_CANCEL]) }
            },
        )
    }
}

/** An icon to choose, a step's button, and the small icon inside it. */
private val ICON_CHOICE_SIZE = 48.dp
private val STEP_BUTTON_SIZE = 32.dp
private val MINI_ICON_SIZE = 16.dp
