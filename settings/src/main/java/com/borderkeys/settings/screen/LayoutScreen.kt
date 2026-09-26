// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.borderkeys.data.theme.ModifierRowKeys
import com.borderkeys.settings.ReorderRow
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.move
import com.borderkeys.settings.rememberPreferencesUpdater

/**
 * The keys: how many rows, what the long press reaches, how big the letters are, how long a hold
 * takes -- and, at the bottom, the input-method subtypes the platform owns.
 *
 * Separate from Languages on purpose, and the explanation on it is the point: there is one
 * QWERTY, and which accents sit on the letters is decided by the enabled dictionaries, not by a
 * layout. Separate from Size only because a resize is a drag gesture with its own screen; the
 * key toggles that used to live there are here now, where the rest of the keys are.
 */
@Composable
fun LayoutScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })

    val subtypes = remember {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.let { info -> (0 until info.subtypeCount).map { info.getSubtypeAt(it) } }
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        SettingsSectionCard(strings[Keys.LAYOUT_NUMBERS_AND_SYMBOLS]) {
            SwitchRow(
                title = strings[Keys.SIZE_NUMBER_ROW],
                subtitle = strings[Keys.SIZE_COSTS_ABOUT_A_FIFTH_OF_THE],
                checked = preferences.numberRow,
            ) { value -> update { it.copy(numberRow = value) } }
            SwitchRow(
                title = strings[Keys.LAYOUT_MODIFIER_ROW],
                subtitle = strings[Keys.LAYOUT_MODIFIER_ROW_NOTE],
                checked = preferences.modifierRow,
            ) { value -> update { it.copy(modifierRow = value) } }
            Explanation(strings[Keys.LAYOUT_MODIFIER_ROW_POSITION])
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    strings[Keys.LAYOUT_MODIFIER_ROW_ABOVE],
                    preferences.modifierRowPosition == KeyboardPreferences.MODIFIER_ROW_ABOVE,
                ) { update { it.copy(modifierRowPosition = KeyboardPreferences.MODIFIER_ROW_ABOVE) } }
                PickerChip(
                    strings[Keys.LAYOUT_MODIFIER_ROW_BELOW],
                    preferences.modifierRowPosition == KeyboardPreferences.MODIFIER_ROW_BELOW,
                ) { update { it.copy(modifierRowPosition = KeyboardPreferences.MODIFIER_ROW_BELOW) } }
            }
            ModifierRowKeysEditor(preferences.modifierRowKeys, update)
            SwitchRow(
                title = strings[Keys.SIZE_NUMBER_PAD_IN_NUMERIC_FIELDS],
                subtitle = strings[Keys.SIZE_A_PHONE_NUMBER_FIELD_GETS_A],
                checked = preferences.numericKeypad,
            ) { value -> update { it.copy(numericKeypad = value) } }
            AdvancedSection {
                Explanation(strings[Keys.LAYOUT_WHERE_THE_DIGITS_SIT])
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.LAYOUT_DIGITS_TOP],
                        preferences.symbolsNumberPosition == KeyboardPreferences.SYMBOLS_NUMBER_TOP,
                    ) { update { it.copy(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_TOP) } }
                    PickerChip(
                        strings[Keys.LAYOUT_DIGITS_LEFT],
                        preferences.symbolsNumberPosition == KeyboardPreferences.SYMBOLS_NUMBER_LEFT,
                    ) { update { it.copy(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_LEFT) } }
                    PickerChip(
                        strings[Keys.LAYOUT_DIGITS_RIGHT],
                        preferences.symbolsNumberPosition == KeyboardPreferences.SYMBOLS_NUMBER_RIGHT,
                    ) { update { it.copy(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_RIGHT) } }
                }
            }
        }

        SettingsSectionCard(strings[Keys.LAYOUT_KEYS]) {
            SwitchRow(
                title = strings[Keys.LAYOUT_ACCENTED_CHARACTERS],
                subtitle = strings[Keys.LAYOUT_ACCENTED_CHARACTERS_NOTE],
                checked = preferences.accentedCharacters,
            ) { value -> update { it.copy(accentedCharacters = value) } }
            SwitchRow(
                title = strings[Keys.LAYOUT_LONG_PRESS_HINTS],
                subtitle = strings[Keys.LAYOUT_LONG_PRESS_HINTS_NOTE],
                checked = preferences.longPressHints,
            ) { value -> update { it.copy(longPressHints = value) } }
            SwitchRow(
                title = strings[Keys.LAYOUT_KEY_POPUP],
                subtitle = strings[Keys.LAYOUT_KEY_POPUP_NOTE],
                checked = preferences.keyPopup,
            ) { value -> update { it.copy(keyPopup = value) } }
            SwitchRow(
                title = strings[Keys.SIZE_EMOJI_KEY],
                subtitle = strings[Keys.SIZE_EMOJI_KEY_NOTE],
                checked = preferences.emojiKey,
            ) { value -> update { it.copy(emojiKey = value) } }
            SwitchRow(
                title = strings[Keys.SIZE_LANGUAGE_KEY],
                subtitle = strings[Keys.SIZE_LANGUAGE_KEY_NOTE],
                checked = preferences.languageKey,
            ) { value -> update { it.copy(languageKey = value) } }
            SwitchRow(
                title = strings[Keys.SIZE_SPACE_CURSOR],
                subtitle = strings[Keys.SIZE_SPACE_CURSOR_NOTE],
                checked = preferences.spaceCursorControl,
            ) { value -> update { it.copy(spaceCursorControl = value) } }
            // The two ways a key press can be felt without being seen. Here with the keys
            // rather than on a screen of their own: two switches were not a screen.
            SwitchRow(
                title = strings[Keys.SIZE_KEY_SOUND],
                subtitle = strings[Keys.SIZE_KEY_SOUND_NOTE],
                checked = preferences.keySound,
            ) { value -> update { it.copy(keySound = value) } }
            SwitchRow(
                title = strings[Keys.SOUND_HAPTIC_FEEDBACK],
                subtitle = strings[Keys.SOUND_HAPTIC_FEEDBACK_NOTE],
                checked = preferences.hapticFeedback,
            ) { value -> update { it.copy(hapticFeedback = value) } }
            // Nothing to choose while it is off.
            if (preferences.hapticFeedback) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        strings[Keys.LAYOUT_HAPTIC_STRENGTH],
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.LAYOUT_HAPTIC_SYSTEM],
                        preferences.hapticStrength == KeyboardPreferences.HAPTIC_SYSTEM,
                    ) { update { it.copy(hapticStrength = KeyboardPreferences.HAPTIC_SYSTEM) } }
                    PickerChip(
                        strings[Keys.LAYOUT_HAPTIC_LIGHT],
                        preferences.hapticStrength == KeyboardPreferences.HAPTIC_LIGHT,
                    ) { update { it.copy(hapticStrength = KeyboardPreferences.HAPTIC_LIGHT) } }
                    PickerChip(
                        strings[Keys.LAYOUT_HAPTIC_MEDIUM],
                        preferences.hapticStrength == KeyboardPreferences.HAPTIC_MEDIUM,
                    ) { update { it.copy(hapticStrength = KeyboardPreferences.HAPTIC_MEDIUM) } }
                    PickerChip(
                        strings[Keys.LAYOUT_HAPTIC_STRONG],
                        preferences.hapticStrength == KeyboardPreferences.HAPTIC_STRONG,
                    ) { update { it.copy(hapticStrength = KeyboardPreferences.HAPTIC_STRONG) } }
                }
                Explanation(strings[Keys.LAYOUT_HAPTIC_STRENGTH_NOTE])
                SwitchRow(
                    title = strings[Keys.LAYOUT_HAPTIC_KEYS],
                    subtitle = strings[Keys.LAYOUT_HAPTIC_KEYS_NOTE],
                    checked = preferences.hapticKeys,
                ) { value -> update { it.copy(hapticKeys = value) } }
                SwitchRow(
                    title = strings[Keys.LAYOUT_HAPTIC_SUGGESTIONS],
                    subtitle = strings[Keys.LAYOUT_HAPTIC_SUGGESTIONS_NOTE],
                    checked = preferences.hapticSuggestions,
                ) { value -> update { it.copy(hapticSuggestions = value) } }
                SwitchRow(
                    title = strings[Keys.LAYOUT_HAPTIC_RING],
                    subtitle = strings[Keys.LAYOUT_HAPTIC_RING_NOTE],
                    checked = preferences.hapticRing,
                ) { value -> update { it.copy(hapticRing = value) } }
            }

            // Set once and left: how long a hold is, and what the enter key does in a field
            // that has its own action -- the latter folded in from its own card, which was a
            // title over three chips.
            AdvancedSection {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        strings[Keys.LAYOUT_LONG_PRESS_DURATION],
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                DefaultableSlider(
                    label = strings.getString(Keys.LAYOUT_LONG_PRESS_MS, preferences.longPressMillis),
                    value = preferences.longPressMillis.toFloat(),
                    range = KeyboardPreferences.MIN_LONG_PRESS_MILLIS.toFloat()..
                        KeyboardPreferences.MAX_LONG_PRESS_MILLIS.toFloat(),
                    default = KeyboardPreferences.DEFAULT_LONG_PRESS_MILLIS.toFloat(),
                    steps = 10,
                ) { value -> update { it.copy(longPressMillis = value.toInt()) } }
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        strings[Keys.LAYOUT_ENTER_KEY],
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.LAYOUT_ENTER_KEY_AUTO],
                        preferences.enterKeyBehavior == KeyboardPreferences.ENTER_KEY_AUTO,
                    ) { update { it.copy(enterKeyBehavior = KeyboardPreferences.ENTER_KEY_AUTO) } }
                    PickerChip(
                        strings[Keys.LAYOUT_ENTER_KEY_FORCE_ACTION],
                        preferences.enterKeyBehavior == KeyboardPreferences.ENTER_KEY_FORCE_ACTION,
                    ) { update { it.copy(enterKeyBehavior = KeyboardPreferences.ENTER_KEY_FORCE_ACTION) } }
                    PickerChip(
                        strings[Keys.LAYOUT_ENTER_KEY_FORCE_NEWLINE],
                        preferences.enterKeyBehavior == KeyboardPreferences.ENTER_KEY_FORCE_NEWLINE,
                    ) { update { it.copy(enterKeyBehavior = KeyboardPreferences.ENTER_KEY_FORCE_NEWLINE) } }
                }
                Explanation(strings[Keys.LAYOUT_ENTER_KEY_NOTE])
            }
        }

        SettingsSectionCard(strings[Keys.LAYOUT_LAYOUTS_ON_THIS_KEYBOARD]) {
            if (subtypes.isEmpty()) {
                SettingRow(
                    title = strings[Keys.LAYOUT_NONE_ENABLED_YET],
                    subtitle = strings[Keys.LAYOUT_ENABLE_BORDERKEYS_FIRST_THEN_ITS_LAYOUTS],
                )
            }
            for (subtype in subtypes) {
                SettingRow(
                    title = subtype.languageTag.ifEmpty { strings[Keys.LAYOUT_LAYOUT] },
                    subtitle = subtype.extraValue.ifEmpty { strings[Keys.LAYOUT_KEYBOARD] },
                )
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS")
                            // The input method's own component id: a name, not a sentence,
                            // so it is built here from the class rather than read out of the
                            // catalogue, where it sat as a translatable string that one "fixed"
                            // translation would have broken this button with.
                            .putExtra(
                                android.provider.Settings.EXTRA_INPUT_METHOD_ID,
                                "${context.packageName}/${com.borderkeys.ime.BorderKeysService::class.java.name}",
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.LAYOUT_CHOOSE_WHICH_LAYOUTS_ARE_ENABLED]) }
            Explanation(strings[Keys.LAYOUT_A_LAYOUT_AND_A_LANGUAGE_ARE])
            Explanation(strings[Keys.LAYOUT_THE_GLOBE_KEY_CYCLES_BETWEEN_THE])
        }
    }
}

/**
 * The modifier row's keys, in order, each with the controls that move it up or take it off,
 * a reset once the list has left the default, and the keys not yet on it to add. The same
 * list editing the quick actions bar uses, on key names instead of action ids.
 */
@Composable
private fun ModifierRowKeysEditor(
    stored: List<String>,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    val strings = LocalStrings.current
    val chosen = ModifierRowKeys.sanitised(stored)
    var picking by remember { mutableStateOf(false) }
    SectionHeader(strings[Keys.LAYOUT_MODIFIER_ROW_KEYS])
    chosen.forEachIndexed { index, name ->
        ReorderRow(
            label = strings[modifierKeyLabel(name)],
            index = index,
            onMoveTop = { update { it.copy(modifierRowKeys = move(chosen, index, 0)) } },
            onMoveUp = { update { it.copy(modifierRowKeys = move(chosen, index, index - 1)) } },
            onRemove = { update { it.copy(modifierRowKeys = chosen.filterNot { key -> key == name }) } },
        )
    }
    if (chosen != ModifierRowKeys.DEFAULT) {
        TextButton(
            onClick = { update { it.copy(modifierRowKeys = ModifierRowKeys.DEFAULT) } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text(strings[Keys.COMMON_RESET_TO_DEFAULT]) }
    }
    Explanation(strings[Keys.LAYOUT_MODIFIER_ROW_KEYS_NOTE])
    val addable = ModifierRowKeys.ALL.filterNot { it in chosen }
    if (chosen.size < ModifierRowKeys.MAX && addable.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { picking = !picking }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                strings[Keys.LAYOUT_MODIFIER_ROW_ADD],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (picking) {
            for (name in addable) {
                ReorderRow(
                    label = strings[modifierKeyLabel(name)],
                    index = -1,
                    onAdd = {
                        update { it.copy(modifierRowKeys = chosen + name) }
                        picking = false
                    },
                )
            }
        }
    }
}

/** The catalogue key naming a modifier-row key, the same name its accessibility node carries. */
private fun modifierKeyLabel(name: String): String = when (name) {
    ModifierRowKeys.ESCAPE -> Keys.KEY_ESCAPE
    ModifierRowKeys.TAB -> Keys.KEY_TAB
    ModifierRowKeys.CONTROL -> Keys.KEY_CONTROL
    ModifierRowKeys.ALT -> Keys.KEY_ALT
    ModifierRowKeys.LEFT -> Keys.KEY_ARROW_LEFT
    ModifierRowKeys.DOWN -> Keys.KEY_ARROW_DOWN
    ModifierRowKeys.UP -> Keys.KEY_ARROW_UP
    ModifierRowKeys.RIGHT -> Keys.KEY_ARROW_RIGHT
    ModifierRowKeys.HOME -> Keys.KEY_HOME
    ModifierRowKeys.END -> Keys.KEY_END
    ModifierRowKeys.PAGE_UP -> Keys.KEY_PAGE_UP
    ModifierRowKeys.PAGE_DOWN -> Keys.KEY_PAGE_DOWN
    ModifierRowKeys.FORWARD_DELETE -> Keys.KEY_FORWARD_DELETE
    else -> Keys.KEY_INSERT
}
