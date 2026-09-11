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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.Screen
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

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
fun LayoutScreen(modifier: Modifier = Modifier, open: (Screen) -> Unit = {}) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    val update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit = { transform ->
        scope.launch { themes.updatePreferences(transform) }
    }

    val subtypes = remember {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.let { info -> (0 until info.subtypeCount).map { info.getSubtypeAt(it) } }
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        SettingsSectionCard(strings[Keys.SCREEN_SIZE_AND_POSITION]) {
            SettingRow(
                title = strings[Keys.LAYOUT_RESIZE],
                subtitle = strings[Keys.LAYOUT_RESIZE_NOTE],
                onClick = { open(Screen.Size) },
            )
        }

        SettingsSectionCard(strings[Keys.LAYOUT_NUMBERS_AND_SYMBOLS]) {
            SwitchRow(
                title = strings[Keys.SIZE_NUMBER_ROW],
                subtitle = strings[Keys.SIZE_COSTS_ABOUT_A_FIFTH_OF_THE],
                checked = preferences.numberRow,
            ) { value -> update { it.copy(numberRow = value) } }

            Explanation(strings[Keys.LAYOUT_WHERE_THE_DIGITS_SIT])
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DigitPositionChip(
                    strings[Keys.LAYOUT_DIGITS_TOP],
                    KeyboardPreferences.SYMBOLS_NUMBER_TOP, preferences.symbolsNumberPosition, update,
                )
                DigitPositionChip(
                    strings[Keys.LAYOUT_DIGITS_LEFT],
                    KeyboardPreferences.SYMBOLS_NUMBER_LEFT, preferences.symbolsNumberPosition, update,
                )
                DigitPositionChip(
                    strings[Keys.LAYOUT_DIGITS_RIGHT],
                    KeyboardPreferences.SYMBOLS_NUMBER_RIGHT, preferences.symbolsNumberPosition, update,
                )
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
                title = strings[Keys.LAYOUT_LARGE_KEY_TEXT],
                subtitle = strings[Keys.LAYOUT_LARGE_KEY_TEXT_NOTE],
                checked = preferences.largeKeyText,
            ) { value -> update { it.copy(largeKeyText = value) } }
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
            SwitchRow(
                title = strings[Keys.SIZE_NUMBER_PAD_IN_NUMERIC_FIELDS],
                subtitle = strings[Keys.SIZE_A_PHONE_NUMBER_FIELD_GETS_A],
                checked = preferences.numericKeypad,
            ) { value -> update { it.copy(numericKeypad = value) } }

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
                            .putExtra(
                                android.provider.Settings.EXTRA_INPUT_METHOD_ID,
                                strings.getString(
                                    Keys.LAYOUT_COM_BORDERKEYS_IME_BORDERKEYSSERVICE,
                                    context.packageName,
                                ),
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

@Composable
private fun DigitPositionChip(
    label: String,
    position: Int,
    current: Int,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    FilterChip(
        selected = current == position,
        onClick = { update { it.copy(symbolsNumberPosition = position) } },
        label = { Text(label) },
    )
}
