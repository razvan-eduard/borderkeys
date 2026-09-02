// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * What the keyboard does with a word once you have finished typing it.
 *
 * The default is that it does nothing: the delimiter commits your letters and a suggestion is
 * applied only when you tap it. That is the behaviour this keyboard argues for, and it is why
 * the switch below starts off rather than on.
 */
@Composable
fun CorrectionsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val theme by repository.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by repository.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { repository.updatePreferences(transform) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.CORRECTIONS_SUGGESTIONS]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_SHOW_THE_SUGGESTION_STRIP],
                subtitle = strings[Keys.CORRECTIONS_THE_ROW_ABOVE_THE_KEYS_IT],
                checked = preferences.showSuggestionStrip,
            ) { value -> update { it.copy(showSuggestionStrip = value) } }
            SwitchRow(
                title = strings[Keys.CORRECTIONS_OFFER_THE_CLIPBOARD],
                subtitle = strings[Keys.CORRECTIONS_OFFER_THE_CLIPBOARD_NOTE],
                checked = preferences.clipboardSuggestion,
            ) { value -> update { it.copy(clipboardSuggestion = value) } }
        }
        SettingsSectionCard(strings[Keys.CORRECTIONS_HOW_MANY_SUGGESTIONS]) {
            SuggestionStripPreview(theme, preferences, Modifier.padding(vertical = 8.dp))
            Text(
                strings.getString(Keys.CORRECTIONS_AT_A_TIME, preferences.suggestionCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Slider(
                value = preferences.suggestionCount.toFloat(),
                valueRange = KeyboardPreferences.MIN_SUGGESTIONS.toFloat()..
                    KeyboardPreferences.MAX_SUGGESTIONS.toFloat(),
                steps = KeyboardPreferences.MAX_SUGGESTIONS - KeyboardPreferences.MIN_SUGGESTIONS - 1,
                onValueChange = { value ->
                    update { it.copy(suggestionCount = value.toInt()) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Explanation(
                strings[Keys.CORRECTIONS_THE_STRIP_IS_A_FIXED_WIDTH],
            )
        }
        SettingsSectionCard(strings[Keys.CORRECTIONS_TWO_WORDS_AT_ONCE]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_SUGGEST_WHOLE_PHRASES],
                subtitle = strings[Keys.CORRECTIONS_OFFERS_VREAU_S_WHERE_IT_WOULD],
                checked = preferences.phraseSuggestions,
            ) { value -> update { it.copy(phraseSuggestions = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_ONLY_FROM_WHAT_YOU_HAVE_WRITTEN],
            )
        }
        SettingsSectionCard(strings[Keys.CORRECTIONS_CAPITALISE]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_CAPITALISE],
                subtitle = strings[Keys.CORRECTIONS_CAPITALISE_NOTE],
                checked = preferences.autoCapitalise,
            ) { value -> update { it.copy(autoCapitalise = value) } }

            SwitchRow(
                title = strings[Keys.CORRECTIONS_DOUBLE_SPACE],
                subtitle = strings[Keys.CORRECTIONS_DOUBLE_SPACE_NOTE],
                checked = preferences.doubleSpacePeriod,
            ) { value -> update { it.copy(doubleSpacePeriod = value) } }

            SwitchRow(
                title = strings[Keys.CORRECTIONS_SPACE_AFTER],
                subtitle = strings[Keys.CORRECTIONS_SPACE_AFTER_NOTE],
                checked = preferences.spaceAfterPunctuation,
            ) { value -> update { it.copy(spaceAfterPunctuation = value) } }

            SwitchRow(
                title = strings[Keys.CORRECTIONS_SPACE_BEFORE],
                subtitle = strings[Keys.CORRECTIONS_SPACE_BEFORE_NOTE],
                checked = preferences.removeSpaceBeforePunctuation,
            ) { value -> update { it.copy(removeSpaceBeforePunctuation = value) } }
        }

        SettingsSectionCard(strings[Keys.CORRECTIONS_CORRECTING_AS_YOU_TYPE]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_APPLY_THE_FIRST_SUGGESTION_WHEN_YOU],
                subtitle = strings[Keys.CORRECTIONS_OFF_BY_DEFAULT_WITH_IT_OFF],
                checked = preferences.autoCorrectOnSpace,
            ) { value -> update { it.copy(autoCorrectOnSpace = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_THE_USUAL_OBJECTION_TO_AUTOCORRECT_IS],
            )
            SwitchRow(
                title = strings[Keys.CORRECTIONS_BACKSPACE_PUTS_BACK_WHAT_YOU_TYPED],
                subtitle = strings[Keys.CORRECTIONS_THE_BACKSPACE_STRAIGHT_AFTER_A_CORRECTION],
                checked = preferences.revertCorrectionOnBackspace,
            ) { value -> update { it.copy(revertCorrectionOnBackspace = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_A_CORRECTION_IS_ONLY_LEARNED_ONCE],
            )
        }
    }
}
