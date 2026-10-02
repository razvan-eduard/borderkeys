// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.HeatmapGlows
import com.borderkeys.data.KeyTouches
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.TextShortcut
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.Disableable
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.Screen
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What this device has learned, and where the taps land on each key. */
@Composable
fun DictionaryScreen(modifier: Modifier = Modifier, open: (Screen) -> Unit = {}) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var confirmingLearningOff by remember { mutableStateOf(false) }
    var confirmingHeatmapOff by remember { mutableStateOf(false) }
    // The limit being dragged to, and the one waiting on the question when words would go.
    var wordLimitDraft by remember { mutableStateOf<Int?>(null) }
    var confirmingWordLimit by remember { mutableStateOf<Int?>(null) }

    val wordCount by repository.wordCount.collectAsStateWithLifecycle(initialValue = 0)
    val blocked by repository.blocked.collectAsStateWithLifecycle(initialValue = emptyList())
    val pairCount by repository.pairCount.collectAsStateWithLifecycle(initialValue = 0)
    val tripleCount by repository.tripleCount.collectAsStateWithLifecycle(initialValue = 0)
    val touchTaps by repository.touchTaps.collectAsStateWithLifecycle(initialValue = 0)
    val touchRows by repository.touches.collectAsStateWithLifecycle(initialValue = emptyList())
    var chosenBucket by rememberSaveable { mutableStateOf<String?>(null) }

    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    val appearance by themes.appearance
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentAppearance() })

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.DICTIONARY_LEARN_FROM_TYPING]) {
            SwitchRow(
                title = strings[Keys.DICTIONARY_LEARNING],
                subtitle = strings[Keys.DICTIONARY_OFF_MEANS_NOTHING_NEW_IS_RECORDED],
                checked = preferences.learningEnabled,
            ) { value ->
                if (value) {
                    update { it.copy(learningEnabled = true) }
                } else {
                    confirmingLearningOff = true
                }
            }
            AdvancedSection(strings[Keys.DICTIONARY_ADVANCED_NOTE]) {
                Explanation(
                    strings[Keys.DICTIONARY_THIS_DOES_NOT_CHANGE_WHAT_IS],
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerChip(
                        strings[Keys.DICTIONARY_CAUTIOUS],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_CAUTIOUS,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_CAUTIOUS) } }
                    PickerChip(
                        strings[Keys.DICTIONARY_BALANCED],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_BALANCED,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_BALANCED) } }
                    PickerChip(
                        strings[Keys.DICTIONARY_IMMEDIATE],
                        preferences.learningSpeed == KeyboardPreferences.LEARNING_IMMEDIATE,
                    ) { update { it.copy(learningSpeed = KeyboardPreferences.LEARNING_IMMEDIATE) } }
                }
                Explanation(
                    when (preferences.learningSpeed) {
                        KeyboardPreferences.LEARNING_CAUTIOUS ->
                            strings[Keys.DICTIONARY_ABOUT_SIX_REPETITIONS_BEFORE_A_PHRASE]
                        KeyboardPreferences.LEARNING_IMMEDIATE ->
                            strings[Keys.DICTIONARY_THE_FIRST_TIME_COUNTS_BEST_IF]
                        else ->
                            strings[Keys.DICTIONARY_A_PHRASE_WRITTEN_TWICE_STARTS_TO]
                    },
                )
            }
        }
        // Where the taps land on each key; greyed, not hidden, while Learning is off.
        SettingsSectionCard(strings[Keys.DICTIONARY_HEATMAP]) {
            val learning = preferences.learningEnabled
            val heatmapOn = learning && preferences.heatmapEnabled
            if (!learning) {
                Explanation(strings[Keys.DICTIONARY_HEATMAP_NEEDS_LEARNING])
            }
            Disableable(disabled = !learning) {
                SwitchRow(
                    title = strings[Keys.DICTIONARY_HEATMAP],
                    subtitle = strings[Keys.DICTIONARY_HEATMAP_NOTE],
                    checked = preferences.heatmapEnabled,
                    enabled = learning,
                ) { value ->
                    if (value) {
                        update { it.copy(heatmapEnabled = true) }
                    } else {
                        confirmingHeatmapOff = true
                    }
                }
                SettingRow(
                    title = if (touchTaps > 0) {
                        strings.getString(Keys.DICTIONARY_HEATMAP_TAPS, touchTaps)
                    } else {
                        strings[Keys.DICTIONARY_HEATMAP_NOTHING_YET]
                    },
                )
                if (heatmapOn) {
                    // The portrait bucket first; a chip for each bucket when there are several.
                    val buckets = touchRows.map { it.bucket }.distinct().sorted()
                    val shown = chosenBucket?.takeIf { it in buckets }
                        ?: buckets.firstOrNull { !HeatmapGlows.Bucket.parse(it).landscape }
                        ?: buckets.firstOrNull()
                    if (buckets.size > 1) {
                        val layouts = buckets.map { HeatmapGlows.Bucket.parse(it).baseLayoutId }.distinct()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (bucket in buckets) {
                                val parsed = HeatmapGlows.Bucket.parse(bucket)
                                val orientation = strings[if (parsed.landscape) Keys.SIZE_LANDSCAPE else Keys.SIZE_PORTRAIT]
                                val label = if (layouts.size > 1) "$orientation · ${parsed.baseLayoutId}" else orientation
                                PickerChip(label, bucket == shown) { chosenBucket = bucket }
                            }
                        }
                    }
                    val parsed = shown?.let { HeatmapGlows.Bucket.parse(it) }
                    PlacementPreview(
                        appearance,
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        layoutId = parsed?.baseLayoutId?.takeIf { it.isNotEmpty() } ?: DEFAULT_PREVIEW_LAYOUT,
                        isLandscape = parsed?.landscape ?: false,
                        touchGlows = HeatmapGlows.of(
                            touchRows, shown, System.currentTimeMillis(),
                            KeyTouches.halfLifeMillis(preferences.heatmapHalfLifeDays),
                            preferences.heatmapMinTaps, HeatmapGlows.COLOR,
                        ),
                    )
                    Explanation(strings[Keys.DICTIONARY_HEATMAP_PREVIEW_NOTE])
                }
                AdvancedSection(strings[Keys.DICTIONARY_HEATMAP_ADVANCED_NOTE]) {
                    Text(
                        strings[Keys.DICTIONARY_HEATMAP_WEIGHT],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    DefaultableSlider(
                        label = strings.getString(
                            Keys.CORRECTIONS_TIMES_THE_DEFAULT,
                            "%.1f".format(preferences.heatmapWeight),
                        ),
                        value = preferences.heatmapWeight,
                        range = KeyboardPreferences.MIN_HEATMAP_WEIGHT..
                            KeyboardPreferences.MAX_HEATMAP_WEIGHT,
                        default = KeyboardPreferences.DEFAULT_HEATMAP_WEIGHT,
                        enabled = heatmapOn,
                    ) { value -> update { it.copy(heatmapWeight = value) } }
                    Explanation(strings[Keys.DICTIONARY_HEATMAP_WEIGHT_NOTE])
                    Text(
                        strings[Keys.DICTIONARY_HEATMAP_MIN_TAPS],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    DefaultableSlider(
                        label = strings.getString(
                            Keys.DICTIONARY_HEATMAP_TAPS_COUNT,
                            preferences.heatmapMinTaps,
                        ),
                        value = preferences.heatmapMinTaps.toFloat(),
                        range = KeyboardPreferences.MIN_HEATMAP_MIN_TAPS.toFloat()..
                            KeyboardPreferences.MAX_HEATMAP_MIN_TAPS.toFloat(),
                        default = KeyboardPreferences.DEFAULT_HEATMAP_MIN_TAPS.toFloat(),
                        steps = (KeyboardPreferences.MAX_HEATMAP_MIN_TAPS -
                            KeyboardPreferences.MIN_HEATMAP_MIN_TAPS) /
                            KeyboardPreferences.HEATMAP_MIN_TAPS_STEP - 1,
                        enabled = heatmapOn,
                    ) { value -> update { it.copy(heatmapMinTaps = value.roundToInt()) } }
                    Explanation(strings[Keys.DICTIONARY_HEATMAP_MIN_TAPS_NOTE])
                    Text(
                        strings[Keys.DICTIONARY_HEATMAP_HALF_LIFE],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    DefaultableSlider(
                        label = strings.getString(
                            Keys.DICTIONARY_HEATMAP_DAYS,
                            preferences.heatmapHalfLifeDays,
                        ),
                        value = preferences.heatmapHalfLifeDays.toFloat(),
                        range = KeyboardPreferences.MIN_HEATMAP_HALF_LIFE_DAYS.toFloat()..
                            KeyboardPreferences.MAX_HEATMAP_HALF_LIFE_DAYS.toFloat(),
                        default = KeyboardPreferences.DEFAULT_HEATMAP_HALF_LIFE_DAYS.toFloat(),
                        enabled = heatmapOn,
                    ) { value -> update { it.copy(heatmapHalfLifeDays = value.roundToInt()) } }
                    Explanation(strings[Keys.DICTIONARY_HEATMAP_HALF_LIFE_NOTE])
                    Button(
                        onClick = { update { resetHeatmapDefaults(it) } },
                        enabled = heatmapOn,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) { Text(strings[Keys.COMMON_RESET_TO_DEFAULTS]) }
                    Explanation(strings[Keys.DICTIONARY_HEATMAP_RESET_NOTE])
                }
            }
        }
        // Text shortcuts, beside the learned words.
        SettingsSectionCard(strings[Keys.DICTIONARY_SHORTCUTS]) {
            Explanation(strings[Keys.DICTIONARY_SHORTCUTS_NOTE])
            if (preferences.textShortcuts.isEmpty()) {
                SettingRow(title = strings[Keys.DICTIONARY_SHORTCUTS_NONE])
            }
            for (shortcut in preferences.textShortcuts) {
                SettingRow(
                    title = shortcut.trigger,
                    subtitle = shortcut.expansion,
                    trailing = {
                        TextButton(onClick = {
                            update { it.copy(textShortcuts = it.textShortcuts - shortcut) }
                        }) { Text(strings[Keys.DICTIONARY_SHORTCUT_REMOVE]) }
                    },
                )
            }
            var trigger by remember { mutableStateOf("") }
            var expansion by remember { mutableStateOf("") }
            OutlinedTextField(
                value = trigger,
                onValueChange = { trigger = it },
                label = { Text(strings[Keys.DICTIONARY_SHORTCUT_TRIGGER]) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = expansion,
                onValueChange = { expansion = it },
                label = { Text(strings[Keys.DICTIONARY_SHORTCUT_EXPANSION]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val addable = TextShortcut.isValidTrigger(trigger.trim()) && expansion.isNotBlank() &&
                preferences.textShortcuts.size < TextShortcut.MAX_SHORTCUTS
            TextButton(
                enabled = addable,
                onClick = {
                    val added = TextShortcut(trigger.trim(), expansion.trim())
                    // Replaces a shortcut with the same trigger; sanitised() keeps only the first.
                    update { current ->
                        current.copy(
                            textShortcuts = current.textShortcuts
                                .filterNot { it.trigger.equals(added.trigger, ignoreCase = true) } + added,
                        )
                    }
                    trigger = ""
                    expansion = ""
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(strings[Keys.DICTIONARY_SHORTCUT_ADD]) }
        }
        // Each list is a page of its own.
        SettingsSectionCard(strings[Keys.DICTIONARY_LEARNED]) {
            SettingRow(
                title = strings.getString(Keys.DICTIONARY_WORDS_COUNT, wordCount),
                trailing = { PageChevron() },
            ) { open(Screen.LearnedWords) }
            SettingRow(
                title = strings.getString(Keys.DICTIONARY_PHRASES_COUNT, pairCount + tripleCount),
                trailing = { PageChevron() },
            ) { open(Screen.LearnedPhrases) }
            val shownLimit = wordLimitDraft ?: preferences.learnedWordLimit
            DefaultableSlider(
                label = strings.getString(Keys.DICTIONARY_WORDS_KEPT, shownLimit),
                value = shownLimit.toFloat(),
                range = KeyboardPreferences.MIN_LEARNED_WORD_LIMIT.toFloat()..
                    KeyboardPreferences.MAX_LEARNED_WORD_LIMIT.toFloat(),
                default = KeyboardPreferences.DEFAULT_LEARNED_WORD_LIMIT.toFloat(),
                onChangeFinished = {
                    val chosen = wordLimitDraft
                    when {
                        chosen == null -> Unit
                        chosen == preferences.learnedWordLimit -> wordLimitDraft = null
                        chosen < wordCount -> confirmingWordLimit = chosen
                        else -> update { it.copy(learnedWordLimit = chosen) }
                    }
                },
            ) { value ->
                val step = KeyboardPreferences.LEARNED_WORD_LIMIT_STEP
                wordLimitDraft = (value / step).roundToInt() * step
            }
            Explanation(strings[Keys.DICTIONARY_WORDS_KEPT_NOTE])
        }
        SettingsSectionCard(strings.getString(Keys.DICTIONARY_BLOCKED, blocked.size)) {
            Explanation(
                strings[Keys.DICTIONARY_A_BLOCKED_WORD_IS_NEVER_SUGGESTED],
            )
            for (entry in blocked) {
                SettingRow(
                    title = entry.word,
                    trailing = {
                        TextButton(onClick = { scope.launch { repository.unblock(entry.word) } }) {
                            Text(strings[Keys.DICTIONARY_UNBLOCK])
                        }
                    },
                )
            }
        }
    }

    if (confirmingLearningOff) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_LEARNING_OFF_TITLE],
            text = strings[Keys.DICTIONARY_LEARNING_OFF_TEXT],
            confirmLabel = strings[Keys.DICTIONARY_LEARNING_OFF_CONFIRM],
            onDismiss = { confirmingLearningOff = false },
        ) {
            update { it.copy(learningEnabled = false) }
            scope.launch { repository.forgetEverything() }
        }
    }

    confirmingWordLimit?.let { limit ->
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_WORDS_KEPT_TRIM_TITLE],
            text = strings.getString(Keys.DICTIONARY_WORDS_KEPT_TRIM_TEXT, wordCount - limit),
            confirmLabel = strings[Keys.DICTIONARY_WORDS_KEPT_TRIM_CONFIRM],
            onDismiss = {
                confirmingWordLimit = null
                wordLimitDraft = null
            },
        ) {
            wordLimitDraft = limit
            update { it.copy(learnedWordLimit = limit) }
            scope.launch { repository.keepWords(limit) }
        }
    }

    // The draft gives way once the stored limit reaches it.
    LaunchedEffect(preferences.learnedWordLimit) {
        if (wordLimitDraft == preferences.learnedWordLimit) {
            wordLimitDraft = null
        }
    }

    if (confirmingHeatmapOff) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_HEATMAP_OFF_TITLE],
            text = strings[Keys.DICTIONARY_HEATMAP_OFF_TEXT],
            confirmLabel = strings[Keys.DICTIONARY_LEARNING_OFF_CONFIRM],
            onDismiss = { confirmingHeatmapOff = false },
        ) {
            update { it.copy(heatmapEnabled = false) }
            scope.launch { repository.forgetTouchPattern() }
        }
    }
}

/** [preferences] with the heatmap's three settings, and nothing else, at their defaults. */
private fun resetHeatmapDefaults(preferences: KeyboardPreferences): KeyboardPreferences {
    val defaults = KeyboardPreferences()
    return preferences.copy(
        heatmapWeight = defaults.heatmapWeight,
        heatmapMinTaps = defaults.heatmapMinTaps,
        heatmapHalfLifeDays = defaults.heatmapHalfLifeDays,
    )
}

/**
 * A question with a destructive answer in the error colour and a cancel; [onConfirm] runs after
 * it closes.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.THEME_CANCEL]) }
        },
    )
}

/** The arrow at the end of a row that opens a page of its own. */
@Composable
private fun PageChevron() {
    Icon(
        painter = painterResource(com.borderkeys.keyboard.R.drawable.bk_chevron_down),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.rotate(-90f),
    )
}

/** The layout the heatmap preview shows before anything is learned. */
private const val DEFAULT_PREVIEW_LAYOUT = "qwerty"
