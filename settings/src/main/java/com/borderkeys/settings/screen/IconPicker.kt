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
import com.borderkeys.settings.move

/** The one place a [CustomIcon] becomes a drawable -- shared across every screen that draws
 *  one, the same way [ComposerAction]'s own `iconFor` in ComposerScreen.kt is scoped to that
 *  file alone, except this one genuinely is needed from more than one screen. */
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

/** Every [CustomIcon], in a simple fixed grid -- 16 entries is short enough that a lazy grid
 *  buys nothing a plain row-of-rows does not already give for free, the same call this codebase
 *  already makes for its other short, fixed lists (the bar's own reorder rows, the tone/translate
 *  menus). */
@Composable
internal fun IconPicker(selected: CustomIcon, onPick: (CustomIcon) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in CustomIcon.entries.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (icon in row) {
                    val isSelected = icon == selected
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(48.dp).clickable { onPick(icon) },
                    ) {
                        Icon(
                            painter = painterResource(iconFor(icon)),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** [IconPicker] in a plain dialog -- no existing sheet/dialog convention in this module to
 *  match (checked: there is none), so this is the plainest thing that works: a title, the grid,
 *  a dismiss. */
@Composable
internal fun IconPickerDialog(selected: CustomIcon, onPick: (CustomIcon) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    strings[Keys.COMPOSER_ICON_PICKER_TITLE],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                IconPicker(selected = selected, onPick = onPick)
            }
        }
    }
}

/**
 * Name, instruction, icon and an optional pin to the bar, in one form -- the settings-screen
 * entry point for "create a custom action" (see ComposerScreen.kt's own call site). The
 * ad-hoc-prompt entry point (ProcessTextScreen.kt's `SavePromptRow`) covers the same ground with
 * a name and an icon already offered inline; this is for starting from nothing rather than from
 * a prompt that was just run.
 *
 * [editing] non-null turns this into the same form pre-filled with an existing action's fields,
 * title included -- the one dialog covers both creating and editing (which is what makes editing
 * also cover renaming: the name field is not a special case here) rather than a second, near-
 * identical form existing only to start from something instead of nothing. [pinnedInitially] is
 * whatever [editing]'s own bar membership already is, since that is not something this dialog
 * itself has any way to know -- the caller does.
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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    strings[if (editing != null) Keys.CUSTOM_ACTION_EDIT_TITLE else Keys.COMPOSER_CUSTOM_ACTION_TITLE],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
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
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it.take(CustomAction.MAX_INSTRUCTION_CHARS) },
                    placeholder = { Text(strings[Keys.COMPOSER_CUSTOM_ACTION_INSTRUCTION_HINT]) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pinToBar = !pinToBar }.padding(top = 8.dp),
                ) {
                    Checkbox(checked = pinToBar, onCheckedChange = { pinToBar = it })
                    Text(strings[Keys.COMPOSER_CUSTOM_ACTION_PIN_TO_BAR], style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
 * Name, icon, an ordered list of steps and an optional pin to the bar -- the quick-action bar's
 * equivalent of [CreateCustomActionDialog], for a macro instead of a single free-text instruction.
 *
 * A step is built from either a [QuickAction.macroEligible] builtin or another already-saved
 * [CustomQuickAction] in [existing] -- recursion allowed, per [QuickActionBar.hasCycle]. A custom
 * action whose own addition would close a reference cycle back to the macro being built is simply
 * left off the "add a step" list, so there is no "that would create a loop" error to design a
 * response to; nothing offered here can ever be refused at save time.
 *
 * [editing] non-null pre-fills the form the same way [CreateCustomActionDialog] does, for the same
 * reason -- one form covers creating and editing, which is what makes editing also cover renaming.
 * Removing a step asks first: a step is one tap to add back, but "which two were left" is not
 * obvious from the row alone once one is gone, so this un-does one tap's worth of damage rather
 * than none.
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

    // 0 for a new action -- never a real id (see CustomQuickAction.nextId's NEW_ID_MIN), so a
    // step can never name "itself" by accident before it has an id of its own to collide with.
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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    strings[
                        if (editing != null) Keys.CUSTOM_ACTION_EDIT_TITLE else Keys.COMPOSER_CUSTOM_ACTION_TITLE,
                    ],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
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
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    strings[Keys.QUICK_CUSTOM_STEPS],
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(iconForStep(stepId)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            labelForStep(stepId),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        IconButton(
                            onClick = { steps = move(steps, index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bk_reorder_up),
                                contentDescription = strings[Keys.QUICK_MOVE_UP],
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = { removingStepAt = index }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.bk_reorder_remove),
                                contentDescription = strings[Keys.QUICK_REMOVE],
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (steps.size < CustomQuickAction.MAX_STEPS) {
                    Text(
                        strings[Keys.QUICK_CUSTOM_ADD_STEP],
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    for (builtin in addableBuiltins) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { steps = steps + builtin.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(iconFor(builtin)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                strings[labelFor(builtin)],
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    for (custom in addableCustom) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { steps = steps + custom.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(iconFor(CustomIcon.fromId(custom.icon))),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                custom.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { pinToBar = !pinToBar }.padding(top = 12.dp),
                ) {
                    Checkbox(checked = pinToBar, onCheckedChange = { pinToBar = it })
                    Text(strings[Keys.COMPOSER_CUSTOM_ACTION_PIN_TO_BAR], style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
