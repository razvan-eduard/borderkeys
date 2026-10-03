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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.ModifierRowKeys
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.KeyCodes
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlin.math.roundToInt

/**
 * What a short drag off a key does, by key and direction: a live keyboard to tap the key on, a
 * three-by-three grid of its directions, and a dialog per direction. The two distance sliders
 * sit below.
 */
@Composable
fun KeyFlicksScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val appearance by themes.appearance
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentAppearance() })
    val preferences = appearance.preferences

    var selectedKey by rememberSaveable { mutableIntStateOf(KeyCodes.NONE) }
    var editing by remember { mutableStateOf<Int?>(null) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SCREEN_KEY_FLICKS]) {
            Explanation(strings[Keys.LAYOUT_FLICKS_NOTE])
            Explanation(strings[Keys.LAYOUT_FLICK_EDITOR])
            PlacementPreview(
                appearance,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                onKeyPicked = { code -> selectedKey = code },
            )
            if (selectedKey != KeyCodes.NONE) {
                SectionHeader(strings.getString(Keys.FLICK_KEY_SELECTED, keyName(selectedKey, strings)))
                DirectionGrid(
                    flicksOf = { direction ->
                        preferences.keyFlicks.firstOrNull { it.keyCode == selectedKey && it.direction == direction }
                    },
                    centreLabel = keyName(selectedKey, strings),
                    noneLabel = strings[Keys.FLICK_NONE],
                    onPick = { direction -> editing = direction },
                )
            }
            if (preferences.keyFlicks.isNotEmpty()) {
                TextButton(
                    onClick = { update { it.copy(keyFlicks = emptyList()) } },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(strings[Keys.FLICK_CLEAR_ALL]) }
            }
        }

        SettingsSectionCard(strings[Keys.LAYOUT_FLICK_MIN]) {
            DefaultableSlider(
                label = strings.getString(Keys.LAYOUT_FLICK_PERCENT, (preferences.flickMinFraction * 100).roundToInt()),
                value = preferences.flickMinFraction * 100,
                range = KeyboardPreferences.MIN_FLICK_MIN_FRACTION * 100..KeyboardPreferences.MAX_FLICK_MIN_FRACTION * 100,
                default = KeyboardPreferences.DEFAULT_FLICK_MIN_FRACTION * 100,
            ) { value -> update { it.copy(flickMinFraction = value / 100f) } }
            SectionHeader(strings[Keys.LAYOUT_FLICK_MAX])
            DefaultableSlider(
                label = strings.getString(Keys.LAYOUT_FLICK_PERCENT, (preferences.flickMaxFraction * 100).roundToInt()),
                value = preferences.flickMaxFraction * 100,
                range = KeyboardPreferences.MIN_FLICK_MAX_FRACTION * 100..KeyboardPreferences.MAX_FLICK_MAX_FRACTION * 100,
                default = KeyboardPreferences.DEFAULT_FLICK_MAX_FRACTION * 100,
            ) { value -> update { it.copy(flickMaxFraction = value / 100f) } }
        }
    }

    val direction = editing
    if (direction != null && selectedKey != KeyCodes.NONE) {
        val existing = preferences.keyFlicks.firstOrNull { it.keyCode == selectedKey && it.direction == direction }
        FlickDialog(
            title = strings.getString(
                Keys.KEY_FLICK_ACTION, strings[DIRECTION_KEYS[direction]], keyName(selectedKey, strings),
            ),
            existing = existing,
            onSave = { kind, value, label ->
                val flick = KeyFlick(selectedKey, direction, kind, value, label)
                update { current ->
                    current.copy(
                        keyFlicks = current.keyFlicks.filterNot { it.keyCode == selectedKey && it.direction == direction } + flick,
                    )
                }
                editing = null
            },
            onRemove = {
                update { current ->
                    current.copy(keyFlicks = current.keyFlicks.filterNot { it.keyCode == selectedKey && it.direction == direction })
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** The eight directions around the key, laid out as on the key, the key itself in the middle. */
@Composable
private fun DirectionGrid(
    flicksOf: (Int) -> KeyFlick?,
    centreLabel: String,
    noneLabel: String,
    onPick: (Int) -> Unit,
) {
    val rows = listOf(
        listOf(KeyFlick.NORTH_WEST, KeyFlick.NORTH, KeyFlick.NORTH_EAST),
        listOf(KeyFlick.WEST, -1, KeyFlick.EAST),
        listOf(KeyFlick.SOUTH_WEST, KeyFlick.SOUTH, KeyFlick.SOUTH_EAST),
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (direction in row) {
                    if (direction < 0) {
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                            Text(centreLabel)
                        }
                    } else {
                        val flick = flicksOf(direction)
                        OutlinedButton(onClick = { onPick(direction) }, modifier = Modifier.weight(1f)) {
                            Text(flick?.let { KeyFlick.shownLabel(it).ifEmpty { commandOrKeyLabel(it) } } ?: noneLabel)
                        }
                    }
                }
            }
        }
    }
}

/** A command's or key's name for a flick with no label of its own. */
@Composable
private fun commandOrKeyLabel(flick: KeyFlick): String {
    val strings = LocalStrings.current
    return when (flick.kind) {
        KeyFlick.COMMAND -> flick.value.toIntOrNull()?.let { QuickAction.fromId(it) }?.let { strings[labelFor(it)] } ?: ""
        KeyFlick.KEY -> keyName(KeyCodes.named(flick.value), strings)
        else -> flick.value
    }
}

/** One flick's kind, value and label, edited in a dialog. */
@Composable
private fun FlickDialog(
    title: String,
    existing: KeyFlick?,
    onSave: (kind: Int, value: String, label: String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var kind by remember { mutableIntStateOf(existing?.kind ?: KeyFlick.TEXT) }
    var text by remember { mutableStateOf(if (existing?.kind == KeyFlick.TEXT) existing.value else "") }
    var command by remember { mutableStateOf(if (existing?.kind == KeyFlick.COMMAND) existing.value else "") }
    var keyName by remember { mutableStateOf(if (existing?.kind == KeyFlick.KEY) existing.value else "") }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    val value = when (kind) {
        KeyFlick.TEXT -> text
        KeyFlick.COMMAND -> command
        else -> keyName
    }
    val valid = KeyFlick(0, 0, kind, value, label).isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == KeyFlick.TEXT, onClick = { kind = KeyFlick.TEXT }, label = { Text(strings[Keys.FLICK_KIND_TEXT]) })
                    FilterChip(selected = kind == KeyFlick.COMMAND, onClick = { kind = KeyFlick.COMMAND }, label = { Text(strings[Keys.FLICK_KIND_COMMAND]) })
                    FilterChip(selected = kind == KeyFlick.KEY, onClick = { kind = KeyFlick.KEY }, label = { Text(strings[Keys.FLICK_KIND_KEY]) })
                }
                when (kind) {
                    KeyFlick.TEXT -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(KeyFlick.MAX_TEXT_CHARS) },
                        label = { Text(strings[Keys.FLICK_VALUE_TEXT]) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyFlick.COMMAND -> Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    ) {
                        for (action in QuickAction.entries) {
                            FilterChip(
                                selected = command == action.id.toString(),
                                onClick = { command = action.id.toString() },
                                label = { Text(strings[labelFor(action)]) },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                    else -> Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    ) {
                        for (name in FLICKABLE_KEYS) {
                            FilterChip(
                                selected = keyName == name,
                                onClick = { keyName = name },
                                label = { Text(keyName(KeyCodes.named(name), strings)) },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(KeyFlick.MAX_LABEL_CHARS) },
                    label = { Text(strings[Keys.FLICK_LABEL]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(kind, value, label) }) { Text(strings[Keys.FLICK_SAVE]) }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onRemove) { Text(strings[Keys.FLICK_RESET]) }
                }
                TextButton(onClick = onDismiss) { Text(strings[Keys.FLICK_CANCEL]) }
            }
        },
    )
}

/** The key's name: its character, or the spoken name of a function key. */
private fun keyName(code: Int, strings: com.borderkeys.i18n.LanguageManager): String = when {
    code == KeyCodes.SPACE -> strings[Keys.KEY_SPACE]
    KeyCodes.isCharacter(code) -> String(Character.toChars(code))
    else -> when (code) {
        KeyCodes.SHIFT -> strings[Keys.KEY_SHIFT]
        KeyCodes.DELETE -> strings[Keys.KEY_DELETE]
        KeyCodes.ENTER -> strings[Keys.KEY_ENTER]
        KeyCodes.SYMBOLS -> strings[Keys.KEY_SYMBOLS]
        KeyCodes.SYMBOLS_SHIFT -> strings[Keys.KEY_MORE_SYMBOLS]
        KeyCodes.LANGUAGE -> strings[Keys.KEY_LANGUAGE_HOLD_FOR_OTHER_KEYBOARDS]
        KeyCodes.EMOJI -> strings[Keys.KEY_EMOJI]
        KeyCodes.SETTINGS -> strings[Keys.KEY_SETTINGS]
        KeyCodes.ESCAPE -> strings[Keys.KEY_ESCAPE]
        KeyCodes.TAB -> strings[Keys.KEY_TAB]
        KeyCodes.CONTROL -> strings[Keys.KEY_CONTROL]
        KeyCodes.ALT -> strings[Keys.KEY_ALT]
        KeyCodes.ARROW_LEFT -> strings[Keys.KEY_ARROW_LEFT]
        KeyCodes.ARROW_RIGHT -> strings[Keys.KEY_ARROW_RIGHT]
        KeyCodes.ARROW_UP -> strings[Keys.KEY_ARROW_UP]
        KeyCodes.ARROW_DOWN -> strings[Keys.KEY_ARROW_DOWN]
        KeyCodes.HOME -> strings[Keys.KEY_HOME]
        KeyCodes.END -> strings[Keys.KEY_END]
        KeyCodes.PAGE_UP -> strings[Keys.KEY_PAGE_UP]
        KeyCodes.PAGE_DOWN -> strings[Keys.KEY_PAGE_DOWN]
        KeyCodes.FORWARD_DELETE -> strings[Keys.KEY_FORWARD_DELETE]
        KeyCodes.INSERT -> strings[Keys.KEY_INSERT]
        KeyCodes.KEYBOARD_PICKER -> strings[Keys.KEY_KEYBOARD_PICKER]
        KeyCodes.VOICE -> strings[Keys.KEY_VOICE]
        KeyCodes.COMPOSE -> strings[Keys.KEY_COMPOSE]
        KeyCodes.DEAD_ACUTE -> strings[Keys.KEY_DEAD_ACUTE]
        KeyCodes.DEAD_GRAVE -> strings[Keys.KEY_DEAD_GRAVE]
        KeyCodes.DEAD_CIRCUMFLEX -> strings[Keys.KEY_DEAD_CIRCUMFLEX]
        KeyCodes.DEAD_DIAERESIS -> strings[Keys.KEY_DEAD_DIAERESIS]
        KeyCodes.DEAD_TILDE -> strings[Keys.KEY_DEAD_TILDE]
        KeyCodes.DEAD_CARON -> strings[Keys.KEY_DEAD_CARON]
        KeyCodes.DEAD_BREVE -> strings[Keys.KEY_DEAD_BREVE]
        KeyCodes.DEAD_CEDILLA -> strings[Keys.KEY_DEAD_CEDILLA]
        KeyCodes.DEAD_OGONEK -> strings[Keys.KEY_DEAD_OGONEK]
        KeyCodes.DEAD_RING -> strings[Keys.KEY_DEAD_RING]
        KeyCodes.DEAD_MACRON -> strings[Keys.KEY_DEAD_MACRON]
        KeyCodes.DEAD_COMMA_BELOW -> strings[Keys.KEY_DEAD_COMMA_BELOW]
        else -> ""
    }
}

private val DIRECTION_KEYS = arrayOf(
    Keys.FLICK_DIR_N, Keys.FLICK_DIR_NE, Keys.FLICK_DIR_E, Keys.FLICK_DIR_SE,
    Keys.FLICK_DIR_S, Keys.FLICK_DIR_SW, Keys.FLICK_DIR_W, Keys.FLICK_DIR_NW,
)

/** The named keys a flick may press, by the names the layout assets use. */
private val FLICKABLE_KEYS = listOf(
    "shift", "delete", "enter", "space", "symbols", "symbols_shift", "language", "emoji", "settings",
) + ModifierRowKeys.ALL
