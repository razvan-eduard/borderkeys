// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.ChoiceSlider
import com.borderkeys.settings.DEFAULT_PREFERENCES
import com.borderkeys.settings.DefaultableSlider
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.rememberPreferencesUpdater
import com.borderkeys.settings.rememberThemeUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The settings worth choosing at the start, one page at a time: typing, swipe and suggestions,
 * learning and privacy, look and feel, then the [FeatureTour] of everything else. Each item has
 * its title and note from its own settings screen, a small preview where one says more than
 * words, and its control, which writes the setting at once. Skip all and Done both mark the
 * onboarding seen and leave through [onDone]; a tour card opens its screen through [open].
 * [hasAssistant] is true in the plus build, the only one with the neural swipe model.
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    hasAssistant: Boolean,
    open: (com.borderkeys.settings.Screen) -> Unit,
    onDone: () -> Unit,
) {
    val strings = LocalStrings.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val appearance by themes.appearance
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentAppearance() })
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = OnboardingPage.entries
    val finish = {
        update { it.copy(onboardingSeen = true) }
        onDone()
    }

    BackHandler(enabled = page > 0) { page-- }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.getString(Keys.ONBOARDING_STEP, page + 1, pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = finish) { Text(strings[Keys.ONBOARDING_SKIP_ALL]) }
        }
        key(page) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (page == 0) {
                    Text(
                        strings[Keys.ONBOARDING_INTRO],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
                Text(
                    strings[pages[page].titleKey],
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                when (pages[page]) {
                    OnboardingPage.Typing -> TypingPage(appearance.preferences, update)
                    OnboardingPage.Swipe -> SwipePage(appearance.preferences, update, hasAssistant)
                    OnboardingPage.Privacy -> PrivacyPage(appearance.preferences, update)
                    OnboardingPage.Look -> LookPage(appearance, update)
                    OnboardingPage.Discover -> FeatureTour(hasAssistant, open)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page > 0) {
                    TextButton(onClick = { page-- }) { Text(strings[Keys.SETTINGS_ACTIVITY_BACK]) }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (page < pages.size - 1) {
                    Button(onClick = { page++ }) { Text(strings[Keys.ONBOARDING_NEXT]) }
                } else {
                    Button(onClick = finish) { Text(strings[Keys.SETUP_DONE]) }
                }
            }
        }
    }
}

/** The pages, in order, each with the catalogue key of its title. */
private enum class OnboardingPage(val titleKey: String) {
    Typing(Keys.FEATURES_GROUP_TYPING),
    Swipe(Keys.ONBOARDING_PAGE_SWIPE),
    Privacy(Keys.FEATURES_GROUP_PRIVACY),
    Look(Keys.ONBOARDING_PAGE_LOOK),
    Discover(Keys.FEATURES_TITLE),
}

// ---- the pages ----------------------------------------------------------------------------

@Composable
private fun TypingPage(
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val sample = strings[Keys.FEATURE_SAMPLE_TYPO_FIXED]
    val words = strings[Keys.FEATURE_SAMPLE_STRIP].split(SAMPLE_SEPARATOR)

    LanguagesItem()
    OnboardingItem(
        title = strings[Keys.LAYOUT_LAYOUTS_ON_THIS_KEYBOARD],
        note = strings[Keys.LAYOUT_A_LAYOUT_AND_A_LANGUAGE_ARE],
        preview = { accent -> ChipsPreview(LAYOUT_SAMPLES, accent, outlined = 0) },
    ) {
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(SUBTYPE_SETTINGS_ACTION)
                            .putExtra(
                                android.provider.Settings.EXTRA_INPUT_METHOD_ID,
                                "${context.packageName}/${com.borderkeys.ime.BorderKeysService::class.java.name}",
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Text(strings[Keys.LAYOUT_CHOOSE_WHICH_LAYOUTS_ARE_ENABLED]) }
    }
    SwitchItem(
        title = strings[Keys.CORRECTIONS_APPLY_THE_FIRST_SUGGESTION_WHEN_YOU],
        note = strings[Keys.CORRECTIONS_OFF_BY_DEFAULT_WITH_IT_OFF],
        checked = preferences.autoCorrectOnSpace,
        preview = { accent -> CorrectionPreview(strings[Keys.FEATURE_SAMPLE_TYPO], sample, accent) },
    ) { value -> update { it.copy(autoCorrectOnSpace = value) } }
    SwitchItem(
        title = strings[Keys.CORRECTIONS_APPLY_THE_FIRST_SUGGESTION_ON_ENTER],
        note = strings[Keys.CORRECTIONS_OFF_BY_DEFAULT_WITH_IT_OFF],
        checked = preferences.autoCorrectOnEnter,
    ) { value -> update { it.copy(autoCorrectOnEnter = value) } }
    OnboardingItem(
        title = strings[Keys.CORRECTIONS_CAPITALISE],
        note = strings[Keys.CORRECTIONS_CAPITALISE_MODE_NOTE],
        active = preferences.autoCapitalise,
        preview = { accent -> ArrowPreview(sample, sample.replaceFirstChar { it.titlecase() }, accent) },
    ) {
        CapitaliseSlider(preferences, update)
    }
    SwitchItem(
        title = strings[Keys.CORRECTIONS_SPACE_AFTER],
        note = strings[Keys.CORRECTIONS_SPACE_AFTER_NOTE],
        checked = preferences.spaceAfterPunctuation,
        preview = { accent ->
            ArrowPreview(words.take(2).joinToString(","), words.take(2).joinToString(", "), accent)
        },
    ) { value -> update { it.copy(spaceAfterPunctuation = value) } }
}

@Composable
private fun SwipePage(
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
    hasAssistant: Boolean,
) {
    val strings = LocalStrings.current
    val words = strings[Keys.FEATURE_SAMPLE_STRIP].split(SAMPLE_SEPARATOR)

    SwitchItem(
        title = strings[Keys.SWIPE_SWIPE_TYPING],
        note = strings[Keys.SWIPE_DRAG_ACROSS_THE_LETTERS_INSTEAD_OF],
        checked = preferences.swipeEnabled,
        preview = { accent -> SwipePreview(accent) },
    ) { value -> update { it.copy(swipeEnabled = value) } }
    if (hasAssistant) {
        SwitchItem(
            title = strings[Keys.SWIPE_EXPERIMENTAL_SWIPE_MODEL],
            note = strings[Keys.SWIPE_DECODES_GESTURES_WITH_A_TRAINED_NEURAL],
            checked = preferences.experimentalSwipeModelEnabled,
            enabled = preferences.swipeEnabled && !preferences.swipeModelFailed,
        ) { value -> update { it.copy(experimentalSwipeModelEnabled = value) } }
    }
    SwitchItem(
        title = strings[Keys.RADIAL_TITLE],
        note = strings[Keys.RADIAL_ENABLE_NOTE],
        checked = preferences.radialMenuEnabled,
        enabled = preferences.swipeEnabled,
        preview = { accent -> ChipsPreview(words, accent, outlined = 0) },
    ) { value -> update { it.copy(radialMenuEnabled = value) } }
    SwitchItem(
        title = strings[Keys.CORRECTIONS_CAPITALISE_NAMES],
        note = strings[Keys.CORRECTIONS_CAPITALISE_NAMES_NOTE],
        checked = preferences.capitaliseNames,
    ) { value -> update { it.copy(capitaliseNames = value) } }
    SwitchItem(
        title = strings[Keys.CORRECTIONS_BLOCK_OFFENSIVE_WORDS],
        note = strings[Keys.CORRECTIONS_BLOCK_OFFENSIVE_WORDS_NOTE],
        checked = preferences.blockOffensiveWords,
    ) { value -> update { it.copy(blockOffensiveWords = value) } }
}

@Composable
private fun PrivacyPage(
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val dictionary = remember { DataGraph.dictionary }
    var confirmingLearningOff by remember { mutableStateOf(false) }
    var confirmingHeatmapOff by remember { mutableStateOf(false) }

    SwitchItem(
        title = strings[Keys.DICTIONARY_LEARN_FROM_TYPING],
        note = strings[Keys.DICTIONARY_OFF_MEANS_NOTHING_NEW_IS_RECORDED],
        checked = preferences.learningEnabled,
        preview = { accent ->
            ChipsPreview(strings[Keys.FEATURE_SAMPLE_LEARNED].split(SAMPLE_SEPARATOR), accent)
        },
    ) { value ->
        if (value) {
            update { it.copy(learningEnabled = true) }
        } else {
            confirmingLearningOff = true
        }
    }
    SwitchItem(
        title = strings[Keys.DICTIONARY_HEATMAP],
        note = if (preferences.learningEnabled) {
            strings[Keys.DICTIONARY_HEATMAP_NOTE]
        } else {
            strings[Keys.DICTIONARY_HEATMAP_NEEDS_LEARNING]
        },
        checked = preferences.heatmapEnabled,
        enabled = preferences.learningEnabled,
        preview = { accent -> KeyCapsPreview(HEATMAP_SAMPLE, accent, highlighted = setOf(2)) },
    ) { value ->
        if (value) {
            update { it.copy(heatmapEnabled = true) }
        } else {
            confirmingHeatmapOff = true
        }
    }
    SwitchItem(
        title = strings[Keys.CLIPBOARD_REMEMBER_WHAT_YOU_COPY],
        note = strings[Keys.CLIPBOARD_ONLY_WHILE_BORDERKEYS_IS_THE_KEYBOARD],
        checked = preferences.clipboardEnabled,
    ) { value -> update { it.copy(clipboardEnabled = value) } }
    OnboardingItem(
        title = strings[Keys.CLIPBOARD_KEEP_UNPINNED_ITEMS_FOR],
        note = strings[Keys.CLIPBOARD_RETENTION_NOTE],
        active = preferences.clipboardEnabled,
    ) {
        StepSlider(
            label = formatRetention(strings, preferences.clipboardRetentionMinutes),
            steps = KeyboardPreferences.RETENTION_STEPS,
            current = preferences.clipboardRetentionMinutes,
            default = KeyboardPreferences().clipboardRetentionMinutes,
        ) { value -> update { it.copy(clipboardRetentionMinutes = value) } }
    }
    SwitchItem(
        title = strings[Keys.CLIPBOARD_REMEMBER_IMAGES],
        note = strings[Keys.CLIPBOARD_REMEMBER_IMAGES_NOTE],
        checked = preferences.clipboardImages,
    ) { value -> scope.launch { setRememberPhotos(value) } }
    ItemCard(active = true) {
        ScreenshotSuggestionSetting(preferences, update)
    }

    if (confirmingLearningOff) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_LEARNING_OFF_TITLE],
            text = strings[Keys.DICTIONARY_LEARNING_OFF_TEXT],
            confirmLabel = strings[Keys.DICTIONARY_LEARNING_OFF_CONFIRM],
            onDismiss = { confirmingLearningOff = false },
        ) {
            update { it.copy(learningEnabled = false) }
            scope.launch { dictionary.forgetEverything() }
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
            scope.launch { dictionary.forgetTouchPattern() }
        }
    }
}

@Composable
private fun LookPage(
    appearance: com.borderkeys.data.theme.KeyboardAppearance,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    val strings = LocalStrings.current
    val updateTheme = rememberThemeUpdater()
    val preferences = appearance.preferences
    val theme = appearance.theme
    val placement = preferences.placementFor(false)

    // The keyboard as these settings draw it.
    PlacementPreview(appearance, Modifier.padding(vertical = 8.dp))

    OnboardingItem(title = strings[Keys.HOME_THEME], note = strings[Keys.HOME_COLOURS_CORNERS_AND_SPACING_WITH_A]) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (preset in PRESETS) {
                // showKeyBorders is carried over; a preset does not change it.
                val presetTheme = preset.theme.copy(showKeyBorders = theme.showKeyBorders)
                PresetCard(
                    name = strings[preset.nameKey],
                    preset = preset.theme,
                    selected = theme == presetTheme,
                ) { updateTheme { presetTheme } }
            }
        }
    }
    OnboardingItem(
        title = strings[Keys.SCREEN_SIZE_AND_POSITION],
        note = strings[Keys.SIZE_BIGGER_KEYS_ARE_EASIER_TO_HIT],
    ) {
        DefaultableSlider(
            value = placement.heightScale,
            range = KeyboardPreferences.MIN_HEIGHT_SCALE..KeyboardPreferences.MAX_HEIGHT_SCALE,
            label = strings.getString(Keys.SIZE_TEXT_2, (placement.heightScale * 100).toInt()),
            default = 1f,
        ) { value -> update { it.withPlacement(false) { p -> p.copy(heightScale = value) } } }
    }
    SwitchItem(
        title = strings[Keys.SIZE_NUMBER_ROW],
        note = strings[Keys.SIZE_COSTS_ABOUT_A_FIFTH_OF_THE],
        checked = preferences.numberRow,
    ) { value -> update { it.copy(numberRow = value) } }
    SwitchItem(
        title = strings[Keys.LAYOUT_KEY_POPUP],
        note = strings[Keys.LAYOUT_KEY_POPUP_NOTE],
        checked = preferences.keyPopup,
    ) { value -> update { it.copy(keyPopup = value) } }
    OnboardingItem(
        title = strings[Keys.SOUND_HAPTIC_FEEDBACK],
        note = strings[Keys.SOUND_HAPTIC_FEEDBACK_NOTE],
        active = preferences.hapticFeedback,
        trailing = {
            Switch(
                checked = preferences.hapticFeedback,
                onCheckedChange = { value -> update { it.copy(hapticFeedback = value) } },
            )
        },
    ) {
        if (preferences.hapticFeedback) {
            ChoiceSlider(
                choices = HAPTIC_STRENGTHS.map { (strength, labelKey) -> strength to strings[labelKey] },
                value = preferences.hapticStrength,
                default = DEFAULT_PREFERENCES.hapticStrength,
            ) { choice -> update { it.copy(hapticStrength = choice) } }
        }
    }
    // On here means as Android's setting says, the Animations screen's middle choice.
    SwitchItem(
        title = strings[Keys.ANIMATIONS_ENABLE],
        note = strings[Keys.EFFECTS_ANIMATIONS_NOTE],
        checked = preferences.effects.anyOn,
    ) { value ->
        update {
            it.copy(
                effects = it.effects.copy(
                    mode = if (value) com.borderkeys.data.theme.EffectsSettings.MODE_SYSTEM else com.borderkeys.data.theme.EffectsSettings.MODE_OFF,
                ),
            )
        }
    }
    SwitchItem(
        title = strings[Keys.QUICK_SHOW],
        note = strings[Keys.QUICK_SHOW_NOTE],
        checked = preferences.quickActionsEnabled,
    ) { value -> update { it.copy(quickActionsEnabled = value) } }
}

/**
 * The languages to type in: every installed pack with its switch, then each bundled dictionary
 * not installed yet, which a tick installs and switches on. Ticks stop at
 * [LanguagePackRepository.MAX_ENABLED] packs switched on.
 */
@Composable
private fun LanguagesItem() {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DataGraph.languagePacks }
    val packs by repository.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val atLimit = packs.count { it.enabled } >= LanguagePackRepository.MAX_ENABLED
    val missing = BundledDictionaries.ALL.filter { entry -> packs.none { it.tag.equals(entry.tag, ignoreCase = true) } }

    OnboardingItem(
        title = strings[Keys.SCREEN_LANGUAGES],
        note = strings[Keys.ONBOARDING_LANGUAGES_NOTE],
        preview = packs.filter { it.enabled }.map { it.displayName }.takeIf { it.isNotEmpty() }?.let { names ->
            { accent: Color -> ChipsPreview(names, accent) }
        },
    ) {
        for (pack in packs) {
            CheckRow(pack.displayName, pack.enabled, enabled = pack.enabled || !atLimit) { value ->
                scope.launch { repository.setEnabled(pack.id, value) }
            }
        }
        for (entry in missing) {
            CheckRow(entry.displayName, checked = false, enabled = !installing && !atLimit) {
                installing = true
                scope.launch {
                    message = withContext(Dispatchers.IO) { installBundled(strings, context, repository, entry) }
                    installing = false
                }
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---- the parts of an item -----------------------------------------------------------------

/** A setting whose control is a switch at the end of its title line. */
@Composable
private fun SwitchItem(
    title: String,
    note: String,
    checked: Boolean,
    enabled: Boolean = true,
    preview: (@Composable (Color) -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    OnboardingItem(
        title = title,
        note = note,
        active = checked && enabled,
        preview = preview,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

/**
 * One setting: [title] with [trailing] beside it, [preview] drawn faded while the setting is not
 * [active], [note], then [content], the control that does not fit beside the title.
 */
@Composable
private fun OnboardingItem(
    title: String,
    note: String,
    active: Boolean = true,
    preview: (@Composable (Color) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    ItemCard(active = active) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        if (preview != null) {
            Box(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp).alpha(if (active) 1f else INACTIVE_ALPHA)) {
                preview(accent)
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (content != null) {
            Column(modifier = Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

/** The tinted, rounded card an item sits on. */
@Composable
private fun ItemCard(active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (active) ACTIVE_TINT else INACTIVE_TINT))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

/** A checkbox with its label, the whole row tappable. */
@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val INACTIVE_ALPHA = 0.4f
private const val ACTIVE_TINT = 0.08f
private const val INACTIVE_TINT = 0.03f

/** The system screen listing an input method's layouts. */
private const val SUBTYPE_SETTINGS_ACTION = "android.settings.INPUT_METHOD_SUBTYPE_SETTINGS"

/** Layout names, as their keys spell them: not translated. */
private val LAYOUT_SAMPLES = listOf("QWERTY", "AZERTY", "Dvorak", "QWERTZ")

/** The top letter row's first keys, as drawn on the keys. */
private val HEATMAP_SAMPLE = listOf("q", "w", "e", "r", "t", "y")

private val HAPTIC_STRENGTHS = listOf(
    KeyboardPreferences.HAPTIC_SYSTEM to Keys.LAYOUT_HAPTIC_SYSTEM,
    KeyboardPreferences.HAPTIC_LIGHT to Keys.LAYOUT_HAPTIC_LIGHT,
    KeyboardPreferences.HAPTIC_MEDIUM to Keys.LAYOUT_HAPTIC_MEDIUM,
    KeyboardPreferences.HAPTIC_STRONG to Keys.LAYOUT_HAPTIC_STRONG,
)
