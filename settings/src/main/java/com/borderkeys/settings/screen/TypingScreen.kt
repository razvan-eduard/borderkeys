// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.SwipeModelAvailability
import com.borderkeys.predict.SwipeModelLoad
import com.borderkeys.settings.CautionNote
import com.borderkeys.settings.Disableable
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater
import kotlin.math.roundToInt

/**
 * How typing itself behaves: what the strip offers, what corrects itself, and how a gesture is
 * read -- everything about the act of typing, one screen rather than two.
 *
 * The default is that nothing is corrected silently: a delimiter commits your letters and a
 * suggestion is applied only when you tap it. That is the behaviour this keyboard argues for,
 * and it is why the switch in "Correcting as you type" starts off rather than on.
 *
 * The swipe probe field itself lives in [com.borderkeys.settings.SettingsActivity]'s own
 * `bottomBar`, not here -- it rides along on every screen, not just this one, since a setting
 * worth trying immediately (a theme colour, a key size) is rarely on the Typing screen itself.
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

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Everything about what the strip offers and how much of it: on/off, how many slots,
        // the clipboard as an extra source, two-word phrases, and the words it must never offer
        // -- one card rather than several, since all of them are the same question ("what shows
        // up in that row") from different angles, not separate decisions.
        SettingsSectionCard(strings[Keys.CORRECTIONS_SUGGESTIONS]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_SHOW_THE_SUGGESTION_STRIP],
                subtitle = strings[Keys.CORRECTIONS_THE_ROW_ABOVE_THE_KEYS_IT],
                checked = preferences.showSuggestionStrip,
            ) { value -> update { it.copy(showSuggestionStrip = value) } }
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
            SwitchRow(
                title = strings[Keys.CORRECTIONS_SUGGEST_WHOLE_PHRASES],
                subtitle = strings[Keys.CORRECTIONS_OFFERS_VREAU_S_WHERE_IT_WOULD],
                checked = preferences.phraseSuggestions,
            ) { value -> update { it.copy(phraseSuggestions = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_ONLY_FROM_WHAT_YOU_HAVE_WRITTEN],
            )
            SwitchRow(
                title = strings[Keys.CORRECTIONS_BLOCK_OFFENSIVE_WORDS],
                subtitle = strings[Keys.CORRECTIONS_BLOCK_OFFENSIVE_WORDS_NOTE],
                checked = preferences.blockOffensiveWords,
            ) { value -> update { it.copy(blockOffensiveWords = value) } }
        }

        // Punctuation/capitals and autocorrect-as-you-type folded into one card: both are edits
        // the keyboard makes to what was just typed, and the mechanical ones (spacing, capitals)
        // read fine ahead of the judgement-call ones (correction strictness) under one heading,
        // with the inline sub-heading below marking where the second half starts -- the same
        // pattern the strictness/length sliders already use for themselves.
        SettingsSectionCard(strings[Keys.CORRECTIONS_CORRECTING_AS_YOU_TYPE]) {
            Text(
                strings[Keys.CORRECTIONS_PUNCTUATION_AND_CAPITALS],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            // One choice, three answers, over two stored switches: "capitalise for me" and
            // "even fields that don't ask" were dependent -- the second did nothing without the
            // first -- and read as two decisions when they are one.
            Text(
                strings[Keys.CORRECTIONS_CAPITALISE],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    strings[Keys.CORRECTIONS_CAPITALISE_OFF],
                    !preferences.autoCapitalise,
                ) { update { it.copy(autoCapitalise = false) } }
                PickerChip(
                    strings[Keys.CORRECTIONS_CAPITALISE_WHEN_ASKED],
                    preferences.autoCapitalise && !preferences.forceCapitaliseSentences,
                ) { update { it.copy(autoCapitalise = true, forceCapitaliseSentences = false) } }
                PickerChip(
                    strings[Keys.CORRECTIONS_CAPITALISE_ALWAYS],
                    preferences.autoCapitalise && preferences.forceCapitaliseSentences,
                ) { update { it.copy(autoCapitalise = true, forceCapitaliseSentences = true) } }
            }
            Explanation(strings[Keys.CORRECTIONS_CAPITALISE_MODE_NOTE])
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
                title = strings[Keys.CORRECTIONS_SPACE_AFTER_PICK],
                subtitle = strings[Keys.CORRECTIONS_SPACE_AFTER_PICK_NOTE],
                checked = preferences.spaceAfterSuggestion,
            ) { value -> update { it.copy(spaceAfterSuggestion = value) } }

            Text(
                strings[Keys.CORRECTIONS_CORRECTING_AS_YOU_TYPE],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            SwitchRow(
                title = strings[Keys.CORRECTIONS_APPLY_THE_FIRST_SUGGESTION_WHEN_YOU],
                subtitle = strings[Keys.CORRECTIONS_OFF_BY_DEFAULT_WITH_IT_OFF],
                checked = preferences.autoCorrectOnSpace,
            ) { value -> update { it.copy(autoCorrectOnSpace = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_THE_USUAL_OBJECTION_TO_AUTOCORRECT_IS],
            )
            Text(
                strings[Keys.CORRECTIONS_DISTANCE],
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    strings[Keys.CORRECTIONS_DISTANCE_STRICT],
                    preferences.correctionDistance == KeyboardPreferences.CORRECTION_DISTANCE_STRICT,
                ) { update { it.copy(correctionDistance = KeyboardPreferences.CORRECTION_DISTANCE_STRICT) } }
                PickerChip(
                    strings[Keys.CORRECTIONS_DISTANCE_NORMAL],
                    preferences.correctionDistance == KeyboardPreferences.CORRECTION_DISTANCE_NORMAL,
                ) { update { it.copy(correctionDistance = KeyboardPreferences.CORRECTION_DISTANCE_NORMAL) } }
                PickerChip(
                    strings[Keys.CORRECTIONS_DISTANCE_LOOSE],
                    preferences.correctionDistance == KeyboardPreferences.CORRECTION_DISTANCE_LOOSE,
                ) { update { it.copy(correctionDistance = KeyboardPreferences.CORRECTION_DISTANCE_LOOSE) } }
            }
            Explanation(strings[Keys.CORRECTIONS_DISTANCE_NOTE])
            SwitchRow(
                title = strings[Keys.CORRECTIONS_BACKSPACE_PUTS_BACK_WHAT_YOU_TYPED],
                subtitle = strings[Keys.CORRECTIONS_THE_BACKSPACE_STRAIGHT_AFTER_A_CORRECTION],
                checked = preferences.revertCorrectionOnBackspace,
            ) { value -> update { it.copy(revertCorrectionOnBackspace = value) } }
            Explanation(
                strings[Keys.CORRECTIONS_A_CORRECTION_IS_ONLY_LEARNED_ONCE],
            )
            // The knobs behind the choices above: how a habit-space after an automatic one is
            // treated, the space before punctuation, and the two finer autocorrect dials that
            // sit under "how different a correction may be" -- the one level most people set.
            AdvancedSection {
                Text(
                    strings[Keys.CORRECTIONS_AUTO_SPACE_HABIT],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.CORRECTIONS_AUTO_SPACE_SWALLOW_FIRST],
                        preferences.autoSpaceHabit == KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST,
                    ) { update { it.copy(autoSpaceHabit = KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST) } }
                    PickerChip(
                        strings[Keys.CORRECTIONS_AUTO_SPACE_SWALLOW_ALL],
                        preferences.autoSpaceHabit == KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL,
                    ) { update { it.copy(autoSpaceHabit = KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL) } }
                    PickerChip(
                        strings[Keys.CORRECTIONS_AUTO_SPACE_KEEP],
                        preferences.autoSpaceHabit == KeyboardPreferences.AUTO_SPACE_KEEP,
                    ) { update { it.copy(autoSpaceHabit = KeyboardPreferences.AUTO_SPACE_KEEP) } }
                }
                Explanation(strings[Keys.CORRECTIONS_AUTO_SPACE_HABIT_NOTE])
                SwitchRow(
                    title = strings[Keys.CORRECTIONS_SPACE_BEFORE],
                    subtitle = strings[Keys.CORRECTIONS_SPACE_BEFORE_NOTE],
                    checked = preferences.removeSpaceBeforePunctuation,
                ) { value -> update { it.copy(removeSpaceBeforePunctuation = value) } }
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
        }

        // Its own card rather than folded into the one above: this is not another correction-as-
        // you-type knob, it is a decision about text already committed, several words back --
        // which is exactly what the warning below it exists to say plainly rather than bury in a
        // subtitle.
        SettingsSectionCard(strings[Keys.LANGUAGES_SWITCH_TITLE]) {
            CautionNote(strings[Keys.LANGUAGES_SWITCH_WARNING])
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerChip(
                    strings[Keys.LANGUAGES_SWITCH_OFF],
                    preferences.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_OFF,
                ) { update { it.copy(languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_OFF) } }
                PickerChip(
                    strings[Keys.LANGUAGES_SWITCH_ASK],
                    preferences.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_ASK,
                ) { update { it.copy(languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_ASK) } }
                PickerChip(
                    strings[Keys.LANGUAGES_SWITCH_AUTO],
                    preferences.languageSwitchCorrectionMode ==
                        KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY,
                ) {
                    update {
                        it.copy(languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY)
                    }
                }
            }
            Explanation(strings[Keys.LANGUAGES_SWITCH_EXPLANATION])
        }

        // Swipe typing, folded in from what used to be its own screen, and its trail width, the
        // decoding explanation, and the experimental model toggle folded into the same card
        // rather than four: every one of them is a fact about the one feature (swipe), not a
        // separate decision, and each keeps its own inline sub-heading so the card still reads
        // as sections rather than one long unbroken list. The live probe field is not among
        // them any more -- see this file's own top-level doc for where it moved.
        SettingsSectionCard(strings[Keys.SWIPE_SWIPE_TYPING]) {
            SwitchRow(
                title = strings[Keys.SWIPE_SWIPE_TYPING],
                subtitle = strings[Keys.SWIPE_DRAG_ACROSS_THE_LETTERS_INSTEAD_OF],
                checked = preferences.swipeEnabled,
            ) { value -> update { it.copy(swipeEnabled = value) } }
            SwitchRow(
                title = strings[Keys.SWIPE_BACKSPACE_WORD],
                subtitle = strings[Keys.SWIPE_BACKSPACE_WORD_NOTE],
                checked = preferences.swipeBackspaceDeletesWord,
            ) { value -> update { it.copy(swipeBackspaceDeletesWord = value) } }

            // The trail's width and colour live together on the Theme screen; the decoding
            // notes and the experimental model are for whoever wants to know how it works.
            AdvancedSection {
                Text(
                    strings[Keys.SWIPE_HOW_IT_DECODES],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Explanation(
                    strings[Keys.SWIPE_YOUR_GESTURE_IS_SMOOTHED_REDUCED_TO],
                )
                Explanation(
                    strings[Keys.SWIPE_ALL_OF_IT_RUNS_ON_THIS],
                )
                if (!preferences.swipeEnabled) {
                    Explanation(strings[Keys.SWIPE_SWIPE_TYPING_IS_CURRENTLY_OFF_SO])
                }

                // `plus`-only: a `core` build compiles no tier B at all, so this section does
                // not exist there rather than existing and doing nothing. See
                // SwipeModelAvailability's own doc for why this is a compile-time check, not a
                // runtime one.
                if (SwipeModelAvailability.neuralSwipeModelSupported) {
                    Text(
                        strings[Keys.SWIPE_EXPERIMENTAL_SWIPE_MODEL],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    CautionNote(strings[Keys.SWIPE_A_PREVIEW_OF_WORK_STILL_IN_PROGRESS])
                    // Turning this on is not a flag: it reads two and a half megabytes of
                    // weights and runs the model once before the first real swipe can use it,
                    // and turning it off frees them again. The row says which of those is
                    // happening rather than pretending the switch settled instantly.
                    val loadState by SwipeModelLoad.state.collectAsStateWithLifecycle()
                    // The stored flag, not just the live one: a failure has to survive a restart,
                    // and the keyboard that recorded it may be long gone by the time this screen
                    // is opened again.
                    val failed = preferences.swipeModelFailed ||
                        loadState == SwipeModelLoad.State.Failed
                    when {
                        failed -> {
                            Disableable(disabled = true) {
                                SwitchRow(
                                    title = strings[Keys.SWIPE_MODEL_FAILED],
                                    subtitle = strings[Keys.SWIPE_DECODES_GESTURES_WITH_A_TRAINED_NEURAL],
                                    checked = false,
                                    enabled = false,
                                ) { }
                            }
                            CautionNote(strings[Keys.SWIPE_MODEL_FAILED_NOTE])
                        }

                        loadState == SwipeModelLoad.State.Loading -> {
                            // A spinner in the switch's own place, rather than the full-width
                            // bar this module uses for an import: what is busy is this one
                            // control, and it is busy for well under a second.
                            Disableable(disabled = true) {
                                SettingRow(
                                    title = strings[Keys.SWIPE_EXPERIMENTAL_SWIPE_MODEL],
                                    subtitle = strings[Keys.SWIPE_MODEL_LOADING],
                                    trailing = {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    },
                                )
                            }
                        }

                        else -> SwitchRow(
                            title = strings[Keys.SWIPE_EXPERIMENTAL_SWIPE_MODEL],
                            subtitle = strings[Keys.SWIPE_DECODES_GESTURES_WITH_A_TRAINED_NEURAL],
                            checked = preferences.experimentalSwipeModelEnabled,
                        ) { value -> update { it.copy(experimentalSwipeModelEnabled = value) } }
                    }
                }
            }
        }

        // A separate card from Swipe typing above rather than folded into it: this is an
        // alternative to the strip, not another fact about how a swipe is decoded, and the three
        // sliders only matter once the switch itself is on -- the same "nothing to tune while
        // it's off" shape the Correcting-as-you-type card already uses for its own toggles.
        SettingsSectionCard(strings[Keys.RADIAL_TITLE]) {
            SwitchRow(
                title = strings[Keys.RADIAL_TITLE],
                subtitle = strings[Keys.RADIAL_ENABLE_NOTE],
                checked = preferences.radialMenuEnabled,
            ) { value -> update { it.copy(radialMenuEnabled = value) } }
            if (preferences.radialMenuEnabled) {
                Text(
                    strings[Keys.RADIAL_TIMEOUT_DEFAULT],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.RADIAL_TIMEOUT_APPLY_TOP],
                        preferences.radialTimeoutDefault == KeyboardPreferences.RADIAL_TIMEOUT_APPLY_TOP,
                    ) {
                        update { it.copy(radialTimeoutDefault = KeyboardPreferences.RADIAL_TIMEOUT_APPLY_TOP) }
                    }
                    PickerChip(
                        strings[Keys.RADIAL_TIMEOUT_CANCEL],
                        preferences.radialTimeoutDefault == KeyboardPreferences.RADIAL_TIMEOUT_CANCEL,
                    ) {
                        update { it.copy(radialTimeoutDefault = KeyboardPreferences.RADIAL_TIMEOUT_CANCEL) }
                    }
                }
                DefaultableSlider(
                    label = strings.getString(
                        // The auto-apply/auto-cancel wording follows what "if nothing is chosen"
                        // is actually set to -- a slider labelled "auto-apply" while it is
                        // configured to cancel would be describing the wrong outcome entirely,
                        // not just using an imprecise word for the right one.
                        if (preferences.radialTimeoutDefault == KeyboardPreferences.RADIAL_TIMEOUT_CANCEL) {
                            Keys.RADIAL_PICK_TIMEOUT_CANCEL_S
                        } else {
                            Keys.RADIAL_PICK_TIMEOUT_S
                        },
                        "%.1f".format(preferences.radialPickTimeoutMillis / 1000f),
                    ),
                    value = preferences.radialPickTimeoutMillis / 1000f,
                    range = (KeyboardPreferences.MIN_RADIAL_PICK_TIMEOUT_MILLIS / 1000f)..
                        (KeyboardPreferences.MAX_RADIAL_PICK_TIMEOUT_MILLIS / 1000f),
                    default = KeyboardPreferences.DEFAULT_RADIAL_PICK_TIMEOUT_MILLIS / 1000f,
                    steps = (KeyboardPreferences.MAX_RADIAL_PICK_TIMEOUT_MILLIS -
                        KeyboardPreferences.MIN_RADIAL_PICK_TIMEOUT_MILLIS) / 100 - 1,
                    // Has nothing left to apply or cancel once radialLiftKeepsOpen is on: the ring
                    // never resolves on its own then, at any point in its lifetime -- see that
                    // field's own doc. Shown disabled rather than hidden, so the setting is still
                    // there, at its last value, for whenever the switch below is turned back off.
                    enabled = !preferences.radialLiftKeepsOpen,
                ) { value ->
                    update { it.copy(radialPickTimeoutMillis = (value * 1000f).roundToInt()) }
                }
                // The two are sequential, not competing for the same instant (pause dwell runs
                // before the ring opens; the pick timeout only starts counting once it has), so
                // this is a usability smell rather than a real conflict -- but a pause set as
                // long as or longer than the reaction window it is immediately followed by is
                // one anybody would want to know about before finding out by hand. Moot, along
                // with the slider above, once radialLiftKeepsOpen turns that reaction window off
                // entirely.
                if (!preferences.radialLiftKeepsOpen &&
                    preferences.radialPauseDwellMillis >= preferences.radialPickTimeoutMillis
                ) {
                    CautionNote(strings[Keys.RADIAL_TIMEOUT_OVERLAP_WARNING])
                }
                SwitchRow(
                    title = strings[Keys.RADIAL_LIFT_KEEPS_OPEN],
                    subtitle = strings[Keys.RADIAL_LIFT_KEEPS_OPEN_NOTE],
                    checked = preferences.radialLiftKeepsOpen,
                ) { value -> update { it.copy(radialLiftKeepsOpen = value) } }
                Text(
                    strings[Keys.RADIAL_POSITION],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.RADIAL_POSITION_FINGER],
                        preferences.radialMenuAnchor == KeyboardPreferences.RADIAL_ANCHOR_FINGER,
                    ) { update { it.copy(radialMenuAnchor = KeyboardPreferences.RADIAL_ANCHOR_FINGER) } }
                    PickerChip(
                        strings[Keys.RADIAL_POSITION_CENTER],
                        preferences.radialMenuAnchor == KeyboardPreferences.RADIAL_ANCHOR_CENTER,
                    ) { update { it.copy(radialMenuAnchor = KeyboardPreferences.RADIAL_ANCHOR_CENTER) } }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.RADIAL_POSITION_TANGENT_LEFT],
                        preferences.radialMenuAnchor == KeyboardPreferences.RADIAL_ANCHOR_TANGENT_LEFT,
                    ) {
                        update { it.copy(radialMenuAnchor = KeyboardPreferences.RADIAL_ANCHOR_TANGENT_LEFT) }
                    }
                    PickerChip(
                        strings[Keys.RADIAL_POSITION_TANGENT_RIGHT],
                        preferences.radialMenuAnchor == KeyboardPreferences.RADIAL_ANCHOR_TANGENT_RIGHT,
                    ) {
                        update { it.copy(radialMenuAnchor = KeyboardPreferences.RADIAL_ANCHOR_TANGENT_RIGHT) }
                    }
                }
                Text(
                    strings[Keys.RADIAL_SIZE],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.RADIAL_SIZE_SMALL],
                        preferences.radialMenuSize == KeyboardPreferences.RADIAL_SIZE_SMALL,
                    ) { update { it.copy(radialMenuSize = KeyboardPreferences.RADIAL_SIZE_SMALL) } }
                    PickerChip(
                        strings[Keys.RADIAL_SIZE_MEDIUM],
                        preferences.radialMenuSize == KeyboardPreferences.RADIAL_SIZE_MEDIUM,
                    ) { update { it.copy(radialMenuSize = KeyboardPreferences.RADIAL_SIZE_MEDIUM) } }
                    PickerChip(
                        strings[Keys.RADIAL_SIZE_LARGE],
                        preferences.radialMenuSize == KeyboardPreferences.RADIAL_SIZE_LARGE,
                    ) { update { it.copy(radialMenuSize = KeyboardPreferences.RADIAL_SIZE_LARGE) } }
                }
                // The dials behind the ring: how many words, how a pause is detected, and
                // what closes it -- set once, if ever.
                AdvancedSection {
                    DefaultableSlider(
                        label = strings.getString(Keys.RADIAL_COUNT, preferences.radialSuggestionCount),
                        value = preferences.radialSuggestionCount.toFloat(),
                        range = KeyboardPreferences.MIN_RADIAL_SUGGESTIONS.toFloat()..
                            KeyboardPreferences.MAX_RADIAL_SUGGESTIONS.toFloat(),
                        default = KeyboardPreferences.DEFAULT_RADIAL_SUGGESTIONS.toFloat(),
                        steps = KeyboardPreferences.MAX_RADIAL_SUGGESTIONS -
                            KeyboardPreferences.MIN_RADIAL_SUGGESTIONS - 1,
                    ) { value -> update { it.copy(radialSuggestionCount = value.toInt()) } }
                    // Shown and stepped in tenths of a second, not milliseconds -- a hundred-odd
                    // possible millisecond values is a false precision nobody can actually feel or
                    // aim for on a slider; the underlying KeyboardPreferences fields stay
                    // millisecond Ints regardless, since that's what Handler.postDelayed wants.
                    DefaultableSlider(
                        label = strings.getString(
                            Keys.RADIAL_PAUSE_DWELL_S,
                            "%.1f".format(preferences.radialPauseDwellMillis / 1000f),
                        ),
                        value = preferences.radialPauseDwellMillis / 1000f,
                        range = (KeyboardPreferences.MIN_RADIAL_PAUSE_DWELL_MILLIS / 1000f)..
                            (KeyboardPreferences.MAX_RADIAL_PAUSE_DWELL_MILLIS / 1000f),
                        default = KeyboardPreferences.DEFAULT_RADIAL_PAUSE_DWELL_MILLIS / 1000f,
                        steps = (KeyboardPreferences.MAX_RADIAL_PAUSE_DWELL_MILLIS -
                            KeyboardPreferences.MIN_RADIAL_PAUSE_DWELL_MILLIS) / 100 - 1,
                    ) { value ->
                        update { it.copy(radialPauseDwellMillis = (value * 1000f).roundToInt()) }
                    }
                    DefaultableSlider(
                        label = strings.getString(
                            Keys.RADIAL_MIN_PATH_LETTERS,
                            "%.1f".format(preferences.radialMinPathLetters),
                        ),
                        value = preferences.radialMinPathLetters,
                        range = KeyboardPreferences.MIN_RADIAL_MIN_PATH_LETTERS..
                            KeyboardPreferences.MAX_RADIAL_MIN_PATH_LETTERS,
                        default = KeyboardPreferences.DEFAULT_RADIAL_MIN_PATH_LETTERS,
                        steps = ((KeyboardPreferences.MAX_RADIAL_MIN_PATH_LETTERS -
                            KeyboardPreferences.MIN_RADIAL_MIN_PATH_LETTERS) / 0.5f).roundToInt() - 1,
                    ) { value ->
                        update { it.copy(radialMinPathLetters = (value * 2f).roundToInt() / 2f) }
                    }
                    SwitchRow(
                        title = strings[Keys.RADIAL_BLUR_BACKGROUND],
                        subtitle = strings[Keys.RADIAL_BLUR_BACKGROUND_NOTE],
                        checked = preferences.radialBlurBackground,
                    ) { value -> update { it.copy(radialBlurBackground = value) } }
                    Text(
                        strings[Keys.RADIAL_OUTSIDE_TAP],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PickerChip(
                            strings[Keys.RADIAL_OUTSIDE_TAP_CLOSES_RING],
                            !preferences.radialOutsideTapHidesKeyboard,
                        ) { update { it.copy(radialOutsideTapHidesKeyboard = false) } }
                        PickerChip(
                            strings[Keys.RADIAL_OUTSIDE_TAP_HIDES_KEYBOARD],
                            preferences.radialOutsideTapHidesKeyboard,
                        ) { update { it.copy(radialOutsideTapHidesKeyboard = true) } }
                    }
                    Explanation(strings[Keys.RADIAL_OUTSIDE_TAP_NOTE])
                    SwitchRow(
                        title = strings[Keys.RADIAL_CLOSE_ON_EDITOR_MOVE],
                        subtitle = strings[Keys.RADIAL_CLOSE_ON_EDITOR_MOVE_NOTE],
                        checked = preferences.radialCloseOnEditorMove,
                    ) { value -> update { it.copy(radialCloseOnEditorMove = value) } }
                    // Debug builds only -- see KeyboardPreferences.debugForceRadialRing's own doc.
                    val debuggable = LocalContext.current.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                    if (debuggable) {
                        SwitchRow(
                            title = strings[Keys.RADIAL_DEBUG_FORCE_OPEN],
                            subtitle = strings[Keys.RADIAL_DEBUG_FORCE_OPEN_NOTE],
                            checked = preferences.debugForceRadialRing,
                        ) { value -> update { it.copy(debugForceRadialRing = value) } }
                    }
                }
                Explanation(strings[Keys.RADIAL_EXPLANATION])
            }
        }
    }
}

/**
 * [preferences] with every field this screen's suggestion and correction cards control put back
 * to [KeyboardPreferences]'s own default -- theme, swipe, language and everything outside those
 * cards untouched. Swipe has no reset of its own here: its two switches are not the kind of
 * setting someone tunes past usefulness and needs a way back from the way the correction knobs
 * above it are, and the trail's width lives with its colour on the Theme screen.
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
        forceCapitaliseSentences = defaults.forceCapitaliseSentences,
        doubleSpacePeriod = defaults.doubleSpacePeriod,
        spaceAfterPunctuation = defaults.spaceAfterPunctuation,
        removeSpaceBeforePunctuation = defaults.removeSpaceBeforePunctuation,
        autoCorrectOnSpace = defaults.autoCorrectOnSpace,
        correctionStrictness = defaults.correctionStrictness,
        revertCorrectionOnBackspace = defaults.revertCorrectionOnBackspace,
        minCorrectionLength = defaults.minCorrectionLength,
    )
}
