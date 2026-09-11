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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater

/**
 * How typing itself behaves: what the strip offers, what corrects itself, and how a gesture is
 * read -- everything about the act of typing, one screen rather than two.
 *
 * The default is that nothing is corrected silently: a delimiter commits your letters and a
 * suggestion is applied only when you tap it. That is the behaviour this keyboard argues for,
 * and it is why the switch in "Correcting as you type" starts off rather than on.
 */
@Composable
fun TypingScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val updateTheme = rememberThemeUpdater()
    val appearance by repository.appearance
        .collectAsStateWithLifecycle(initialValue = remember { repository.currentAppearance() })
    val (theme, _, preferences) = appearance
    var probe by remember { mutableStateOf("") }

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

            SwitchRow(
                title = strings[Keys.CORRECTIONS_CLIPBOARD_ONCE],
                subtitle = strings[Keys.CORRECTIONS_CLIPBOARD_ONCE_NOTE],
                checked = preferences.clipboardSuggestionOnce,
            ) { value -> update { it.copy(clipboardSuggestionOnce = value) } }
        }
        SettingsSectionCard(strings[Keys.CORRECTIONS_HOW_MANY_SUGGESTIONS]) {
            SuggestionStripPreview(appearance, Modifier.padding(vertical = 8.dp))
            DefaultableSlider(
                label = strings.getString(Keys.CORRECTIONS_AT_A_TIME, preferences.suggestionCount),
                value = preferences.suggestionCount.toFloat(),
                range = KeyboardPreferences.MIN_SUGGESTIONS.toFloat()..
                    KeyboardPreferences.MAX_SUGGESTIONS.toFloat(),
                default = KeyboardPreferences.DEFAULT_SUGGESTIONS.toFloat(),
                steps = KeyboardPreferences.MAX_SUGGESTIONS - KeyboardPreferences.MIN_SUGGESTIONS - 1,
            ) { value -> update { it.copy(suggestionCount = value.toInt()) } }
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
        // The card holds capitalisation and the punctuation-spacing rows that follow it, so it
        // gets its own header distinct from the first row's -- the two used to share one key,
        // which read as the card being about nothing but capital letters.
        SettingsSectionCard(strings[Keys.CORRECTIONS_PUNCTUATION_AND_CAPITALS]) {
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
            Text(
                strings[Keys.CORRECTIONS_CORRECTION_STRICTNESS],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            DefaultableSlider(
                label = strings.getString(
                    Keys.CORRECTIONS_TIMES_THE_DEFAULT,
                    "%.1f".format(preferences.correctionStrictness),
                ),
                value = preferences.correctionStrictness,
                range = KeyboardPreferences.MIN_CORRECTION_STRICTNESS..
                    KeyboardPreferences.MAX_CORRECTION_STRICTNESS,
                default = KeyboardPreferences.DEFAULT_CORRECTION_STRICTNESS,
            ) { value -> update { it.copy(correctionStrictness = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_CORRECTION_STRICTNESS_NOTE],
            )
            SwitchRow(
                title = strings[Keys.CORRECTIONS_BACKSPACE_PUTS_BACK_WHAT_YOU_TYPED],
                subtitle = strings[Keys.CORRECTIONS_THE_BACKSPACE_STRAIGHT_AFTER_A_CORRECTION],
                checked = preferences.revertCorrectionOnBackspace,
            ) { value -> update { it.copy(revertCorrectionOnBackspace = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_A_CORRECTION_IS_ONLY_LEARNED_ONCE],
            )
            Text(
                strings[Keys.CORRECTIONS_MIN_CORRECTION_LENGTH],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            DefaultableSlider(
                label = strings.getString(Keys.CORRECTIONS_LETTERS_OR_MORE, preferences.minCorrectionLength),
                value = preferences.minCorrectionLength.toFloat(),
                range = KeyboardPreferences.MIN_CORRECTION_LENGTH.toFloat()..
                    KeyboardPreferences.MAX_CORRECTION_LENGTH.toFloat(),
                default = 3f,
                steps = KeyboardPreferences.MAX_CORRECTION_LENGTH -
                    KeyboardPreferences.MIN_CORRECTION_LENGTH - 1,
            ) { value -> update { it.copy(minCorrectionLength = value.toInt()) } }
            Explanation(
                strings[Keys.CORRECTIONS_MIN_CORRECTION_LENGTH_NOTE],
            )
            Button(
                onClick = { update { resetCorrectionDefaults(it) } },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            ) { Text(strings[Keys.COMMON_RESET_TO_DEFAULTS]) }
            Explanation(strings[Keys.COMMON_RESET_TO_DEFAULTS_NOTE])
        }

        // Swipe typing, folded in from what used to be its own screen. One row, so the card
        // header doubles as the row's own title the same way "Suggestions" does for a card with
        // several rows in it -- unambiguous here because it is the only row this card has.
        SettingsSectionCard(strings[Keys.SWIPE_SWIPE_TYPING]) {
            SwitchRow(
                title = strings[Keys.SWIPE_SWIPE_TYPING],
                subtitle = strings[Keys.SWIPE_DRAG_ACROSS_THE_LETTERS_INSTEAD_OF],
                checked = preferences.swipeEnabled,
            ) { value -> update { it.copy(swipeEnabled = value) } }
        }
        SettingsSectionCard(strings[Keys.SWIPE_THE_TRAIL]) {
            DefaultableSlider(
                label = strings.getString(Keys.SWIPE_WIDTH_DP, theme.swipeTrailWidthDp.toInt()),
                value = theme.swipeTrailWidthDp.coerceIn(1f, 24f),
                range = 1f..24f,
                default = 4f,
            ) { value -> updateTheme { it.copy(swipeTrailWidthDp = value) } }
            Explanation(strings[Keys.SWIPE_THE_COLOUR_IS_ON_THE_THEME])
        }
        SettingsSectionCard(strings[Keys.SWIPE_TRY_IT_HERE]) {
            OutlinedTextField(
                value = probe,
                onValueChange = { probe = it },
                label = { Text(strings[Keys.SWIPE_SWIPE_A_WORD]) },
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
            Explanation(strings[Keys.SWIPE_A_REAL_FIELD_NOTHING_TYPED_INTO])
        }
        SettingsSectionCard(strings[Keys.SWIPE_HOW_IT_DECODES]) {
            Explanation(
                strings[Keys.SWIPE_YOUR_GESTURE_IS_SMOOTHED_REDUCED_TO],
            )
            Explanation(
                strings[Keys.SWIPE_ALL_OF_IT_RUNS_ON_THIS],
            )
            if (!preferences.swipeEnabled) {
                Explanation(strings[Keys.SWIPE_SWIPE_TYPING_IS_CURRENTLY_OFF_SO])
            }
        }
    }
}

/**
 * [preferences] with every field this screen's suggestion and correction cards control put back
 * to [KeyboardPreferences]'s own default -- theme, swipe, language and everything outside those
 * cards untouched. Swipe has no reset of its own here: its two controls are a trail width that
 * is really a theme choice and a live probe field, neither the kind of setting someone tunes
 * past usefulness and needs a way back from the way the correction knobs above it are.
 */
private fun resetCorrectionDefaults(preferences: KeyboardPreferences): KeyboardPreferences {
    val defaults = KeyboardPreferences()
    return preferences.copy(
        showSuggestionStrip = defaults.showSuggestionStrip,
        clipboardSuggestion = defaults.clipboardSuggestion,
        clipboardSuggestionOnce = defaults.clipboardSuggestionOnce,
        suggestionCount = defaults.suggestionCount,
        phraseSuggestions = defaults.phraseSuggestions,
        autoCapitalise = defaults.autoCapitalise,
        doubleSpacePeriod = defaults.doubleSpacePeriod,
        spaceAfterPunctuation = defaults.spaceAfterPunctuation,
        removeSpaceBeforePunctuation = defaults.removeSpaceBeforePunctuation,
        autoCorrectOnSpace = defaults.autoCorrectOnSpace,
        correctionStrictness = defaults.correctionStrictness,
        revertCorrectionOnBackspace = defaults.revertCorrectionOnBackspace,
        minCorrectionLength = defaults.minCorrectionLength,
    )
}
