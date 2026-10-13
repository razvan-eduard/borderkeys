// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.ClipSearch
import com.borderkeys.data.ClipboardExclusions
import com.borderkeys.data.DataGraph
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.data.theme.Feature
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.ChoiceSlider
import com.borderkeys.settings.DEFAULT_PREFERENCES
import com.borderkeys.settings.Disableable
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.NodeSlider
import com.borderkeys.settings.PackageListEditor
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.PrivateCopyActivity
import com.borderkeys.settings.SPACE_GUTTER
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SPACE_MEDIUM
import com.borderkeys.settings.SPACE_XSMALL
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The clipboard history, and how long it lives: an hour by default, expired rows are deleted, and
 * a button here empties it now.
 */
@Composable
fun ClipboardScreen(modifier: Modifier = Modifier, editClipId: Long? = null) {
    val strings = LocalStrings.current
    val repository = remember { DataGraph.clipboard }
    val themes = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val update = rememberPreferencesUpdater()
    val entries by repository.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(ClipSearch.Mode.PLAIN) }
    val search = ClipSearch.Query.of(query, searchMode)

    // The entry whose text is open in the edit dialog, and the text as edited so far.
    var editing by remember { mutableStateOf<ClipEntry?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    // The keyboard's own "Edit" arrives as an id; opened once the history has loaded, once.
    var openedRequestedEdit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editClipId, entries) {
        if (editClipId == null || openedRequestedEdit || entries.isEmpty()) {
            return@LaunchedEffect
        }
        openedRequestedEdit = true
        val entry = entries.firstOrNull { it.id == editClipId && !it.isImage } ?: return@LaunchedEffect
        draft = entry.content
        editing = entry
    }

    // The same table the keyboard reads: each row is greyed while a feature it sits under is off.
    val history = Feature.CLIPBOARD_HISTORY.on(preferences)
    val offers = Feature.CLIPBOARD_OFFER.on(preferences)
    val offersImages = Feature.PHOTO_OFFER.on(preferences) || Feature.SCREENSHOT_OFFER.on(preferences)

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.CLIPBOARD_REMEMBERED_TITLE]) {
            SwitchRow(
                title = strings[Keys.CLIPBOARD_HISTORY_SWITCH],
                subtitle = strings[Keys.CLIPBOARD_ONLY_WHILE_BORDERKEYS_IS_THE_KEYBOARD],
                checked = preferences.clipboardEnabled,
            ) { value -> update { it.copy(clipboardEnabled = value) } }

            // Copied photos and screenshots, each kept in the history.
            SwitchRow(
                title = strings[Keys.CLIPBOARD_REMEMBER_IMAGES],
                subtitle = strings[Keys.CLIPBOARD_REMEMBER_IMAGES_NOTE],
                checked = preferences.clipboardImages,
                enabled = history,
            ) { value -> scope.launch { setRememberPhotos(value) } }
            ScreenshotSuggestionSetting(preferences, update, enabled = history)

            // Keeping privately, the largest image kept, and the two ways the history forgets, each
            // independent.
            AdvancedSection(strings[Keys.CLIPBOARD_ADVANCED_NOTE]) {
                SwitchRow(
                    title = strings[Keys.CLIPBOARD_PRIVATE_MENU],
                    subtitle = strings[Keys.CLIPBOARD_PRIVATE_NOTE],
                    checked = preferences.privateCopyInTextMenu,
                    enabled = history,
                ) { value ->
                    PrivateCopyActivity.setOffered(context, value)
                    update { it.copy(privateCopyInTextMenu = value) }
                }
                Disableable(disabled = !history) {
                    SectionHeader(strings[Keys.CLIPBOARD_IMAGE_MAX_SIZE])
                    NodeSlider(
                        label = strings.getString(Keys.CLIPBOARD_MEGABYTES, preferences.clipboardImageMaxMb),
                        options = KeyboardPreferences.IMAGE_SIZE_STEPS,
                        value = preferences.clipboardImageMaxMb,
                        default = KeyboardPreferences.DEFAULT_CLIPBOARD_IMAGE_MAX_MB,
                    ) { value -> update { it.copy(clipboardImageMaxMb = value) } }
                    Explanation(strings[Keys.CLIPBOARD_IMAGE_MAX_SIZE_NOTE])
                }
                SwitchRow(
                    title = strings[Keys.CLIPBOARD_CLEAR_ON_CLOSE],
                    subtitle = strings[Keys.CLIPBOARD_CLEAR_ON_CLOSE_NOTE],
                    checked = preferences.clearClipboardOnClose,
                    enabled = history,
                ) { value -> update { it.copy(clearClipboardOnClose = value) } }
                SwitchRow(
                    title = strings[Keys.CLIPBOARD_DELETE_AFTER_USE],
                    subtitle = strings[Keys.CLIPBOARD_DELETE_AFTER_USE_NOTE],
                    checked = preferences.clipboardDeleteAfterUse,
                    enabled = history,
                ) { value -> update { it.copy(clipboardDeleteAfterUse = value) } }
            }
        }

        // The chips on the suggestion strip: the clipboard's text or photo, and the screenshot.
        SettingsSectionCard(strings[Keys.CLIPBOARD_OFFERED_TITLE]) {
            SwitchRow(
                title = strings[Keys.CORRECTIONS_OFFER_THE_CLIPBOARD],
                subtitle = strings[Keys.CORRECTIONS_OFFER_THE_CLIPBOARD_NOTE],
                checked = preferences.clipboardSuggestion,
                enabled = history,
            ) { value -> update { it.copy(clipboardSuggestion = value) } }

            // How recent a photo or screenshot must be for the strip, off meaning any age.
            Disableable(disabled = !offersImages) {
                SectionHeader(strings[Keys.CLIPBOARD_IMAGE_OFFER_WINDOW])
                ChoiceSlider(
                    choices = KeyboardPreferences.IMAGE_OFFER_STEPS.map { minutes ->
                        minutes to if (minutes == 0) strings[Keys.CLIPBOARD_SCREENSHOT_OFF] else formatRetention(strings, minutes)
                    },
                    value = preferences.imageOfferMinutes,
                    default = DEFAULT_PREFERENCES.imageOfferMinutes,
                ) { minutes -> update { it.copy(imageOfferMinutes = minutes) } }
                Explanation(strings[Keys.CLIPBOARD_IMAGE_OFFER_WINDOW_NOTE])
            }

            // The screenshots within that window offered in turn, the oldest first.
            SwitchRow(
                title = strings[Keys.CLIPBOARD_SCREENSHOT_CASCADE],
                subtitle = strings[Keys.CLIPBOARD_SCREENSHOT_CASCADE_NOTE],
                checked = preferences.screenshotCascade,
                enabled = Feature.SCREENSHOT_CASCADE.canSwitch(preferences),
            ) { value -> update { it.copy(screenshotCascade = value) } }

            // A small preview of the image on the photo and screenshot chips.
            SwitchRow(
                title = strings[Keys.CLIPBOARD_CHIP_PREVIEW],
                subtitle = strings[Keys.CLIPBOARD_CHIP_PREVIEW_NOTE],
                checked = preferences.chipImagePreview,
                enabled = offersImages,
            ) { value -> update { it.copy(chipImagePreview = value) } }

            SwitchRow(
                title = strings[Keys.CORRECTIONS_CLIPBOARD_ONCE],
                subtitle = strings[Keys.CORRECTIONS_CLIPBOARD_ONCE_NOTE],
                checked = preferences.clipboardSuggestionOnce,
                enabled = offers,
            ) { value -> update { it.copy(clipboardSuggestionOnce = value) } }
            SwitchRow(
                title = strings[Keys.CLIPBOARD_CLEAR_AFTER_INSERT],
                subtitle = strings[Keys.CLIPBOARD_CLEAR_AFTER_INSERT_NOTE],
                checked = preferences.clearClipboardAfterInsert,
                enabled = offers,
            ) { value -> update { it.copy(clearClipboardAfterInsert = value) } }
        }

        SettingsSectionCard(strings[Keys.CLIPBOARD_EXCLUDED_TITLE]) {
            Explanation(strings[Keys.CLIPBOARD_EXCLUDED_NOTE])
            Explanation(strings.counted(Keys.CLIPBOARD_EXCLUDED_KNOWN, ClipboardExclusions.KNOWN.size))
            PackageListEditor(preferences.clipboardExcludedPackages, strings[Keys.CLIPBOARD_EXCLUDED_PACKAGE]) { change ->
                update { it.copy(clipboardExcludedPackages = change(it.clipboardExcludedPackages)) }
            }
        }

        SettingsSectionCard(strings[Keys.CLIPBOARD_RETENTION_TITLE]) {
            SectionHeader(strings[Keys.CLIPBOARD_HOW_MANY_ITEMS])
            NodeSlider(
                label = strings.counted(
                    Keys.CLIPBOARD_ITEMS, preferences.clipboardMaxEntries,
                ),
                options = KeyboardPreferences.HISTORY_SIZE_STEPS,
                value = preferences.clipboardMaxEntries,
                default = DEFAULT_PREFERENCES.clipboardMaxEntries,
            ) { value -> update { it.copy(clipboardMaxEntries = value) } }
            Explanation(strings[Keys.CLIPBOARD_SIZE_NOTE])
            SectionHeader(strings[Keys.CLIPBOARD_KEEP_UNPINNED_ITEMS_FOR])
            NodeSlider(
                label = formatRetention(strings, preferences.clipboardRetentionMinutes),
                options = KeyboardPreferences.RETENTION_STEPS,
                value = preferences.clipboardRetentionMinutes,
                default = DEFAULT_PREFERENCES.clipboardRetentionMinutes,
            ) { value -> update { it.copy(clipboardRetentionMinutes = value) } }
            Explanation(strings[Keys.CLIPBOARD_RETENTION_NOTE])
            Explanation(strings[Keys.CLIPBOARD_EXPIRED_ITEMS_ARE_DELETED_NOT_MERELY])
        }
        SettingsSectionCard(strings.getString(Keys.CLIPBOARD_HISTORY, entries.size)) {
            if (entries.isEmpty()) {
                SettingRow(title = strings[Keys.CLIPBOARD_EMPTY])
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(strings[Keys.CLIPBOARD_SEARCH]) },
                    singleLine = true,
                    isError = search.invalid,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
                    horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
                    verticalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
                ) {
                    for ((mode, labelKey) in listOf(
                        ClipSearch.Mode.PLAIN to Keys.CLIPBOARD_SEARCH_PLAIN,
                        ClipSearch.Mode.WILDCARDS to Keys.CLIPBOARD_SEARCH_WILDCARDS,
                        ClipSearch.Mode.REGEX to Keys.CLIPBOARD_SEARCH_REGEX,
                    )) {
                        PickerChip(strings[labelKey], selected = searchMode == mode) { searchMode = mode }
                    }
                }
                when {
                    search.invalid -> Explanation(strings[Keys.CLIPBOARD_SEARCH_INVALID])
                    searchMode == ClipSearch.Mode.WILDCARDS -> Explanation(strings[Keys.CLIPBOARD_SEARCH_WILDCARDS_NOTE])
                    searchMode == ClipSearch.Mode.REGEX -> Explanation(strings[Keys.CLIPBOARD_SEARCH_REGEX_NOTE])
                }
            }
            for (entry in ClipSearch.filter(entries, search)) {
                SettingRow(
                    title = if (entry.isImage) strings[Keys.CLIP_IMAGE] else entry.content.take(80).replace('\n', ' '),
                    subtitle = when {
                        entry.isPrivate -> listOfNotNull(
                            strings[Keys.CLIP_PRIVATE],
                            entry.sourcePackage?.let { strings.getString(Keys.CLIPBOARD_PRIVATE_FROM, it) },
                        ).joinToString(" · ")
                        entry.isPinned -> strings[Keys.CLIPBOARD_PINNED_NEVER_EXPIRES]
                        else -> strings[Keys.CLIPBOARD_EXPIRES_ON_THE_TIMER]
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(SPACE_XSMALL)) {
                            TextButton(
                                onClick = {
                                    scope.launch { repository.setPinned(entry.id, !entry.isPinned) }
                                },
                            ) { Text(if (entry.isPinned) strings[Keys.CLIPBOARD_UNPIN] else strings[Keys.CLIPBOARD_PIN]) }
                            if (!entry.isImage) {
                                TextButton(onClick = { draft = entry.content; editing = entry }) {
                                    Text(strings[Keys.CLIPBOARD_EDIT])
                                }
                            }
                            TextButton(onClick = { scope.launch { repository.delete(entry.id) } }) {
                                Text(strings[Keys.CLIPBOARD_DELETE])
                            }
                        }
                    },
                )
            }
            TextButton(
                onClick = { confirmingDeleteAll = true },
                modifier = Modifier.padding(horizontal = SPACE_LARGE, vertical = SPACE_MEDIUM),
            ) { Text(strings[Keys.CLIPBOARD_DELETE_EVERYTHING_INCLUDING_PINNED]) }
        }
    }

    val edited = editing
    if (edited != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(strings[Keys.CLIPBOARD_EDIT_TITLE]) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank() && draft != edited.content,
                    onClick = {
                        editing = null
                        scope.launch { repository.update(edited.id, draft) }
                    },
                ) { Text(strings[Keys.CLIPBOARD_SAVE]) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(strings[Keys.THEME_CANCEL]) }
            },
        )
    }

    // Asked first: this removes pinned entries too.
    if (confirmingDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text(strings[Keys.CLIPBOARD_DELETE_EVERYTHING_TITLE]) },
            text = { Text(strings[Keys.COMMON_CANNOT_BE_UNDONE]) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDeleteAll = false
                    scope.launch { repository.deleteAll() }
                }) { Text(strings[Keys.CLIPBOARD_DELETE], color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteAll = false }) { Text(strings[Keys.THEME_CANCEL]) }
            },
        )
    }
}

/** Remember photos: on or off, and turned off, the copied photos kept are deleted. */
internal suspend fun setRememberPhotos(value: Boolean) {
    DataGraph.themes.updatePreferences { it.copy(clipboardImages = value) }
    if (!value) {
        DataGraph.clipboard.deleteCopiedImages()
    }
}

internal fun formatRetention(strings: LanguageManager, minutes: Int): String {
    val day = MINUTES_PER_DAY
    return when {
        minutes < MINUTES_PER_HOUR -> strings.counted(Keys.CLIPBOARD_MINUTES, minutes)
        minutes == MINUTES_PER_HOUR -> strings[Keys.CLIPBOARD_1_HOUR]
        minutes < day -> if (minutes % MINUTES_PER_HOUR == 0) {
            strings.counted(Keys.CLIPBOARD_HOURS, minutes / MINUTES_PER_HOUR)
        } else {
            strings.getString(Keys.CLIPBOARD_H_MIN, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
        }
        minutes == day -> strings[Keys.CLIPBOARD_1_DAY]
        minutes == DAYS_PER_WEEK * day -> strings[Keys.CLIPBOARD_1_WEEK]
        minutes == DAYS_PER_MONTH * day -> strings[Keys.CLIPBOARD_1_MONTH]
        minutes % (DAYS_PER_WEEK * day) == 0 -> strings.counted(Keys.CLIPBOARD_WEEKS, minutes / (DAYS_PER_WEEK * day))
        else -> strings.counted(Keys.CLIPBOARD_DAYS, minutes / day)
    }
}

/** Minutes in an hour and in a day, for the windows and the retention shown in hours and days. */
private val MINUTES_PER_HOUR = TimeUnit.HOURS.toMinutes(1).toInt()
private val MINUTES_PER_DAY = TimeUnit.DAYS.toMinutes(1).toInt()

/** The days the retention's week and month stand for. */
private const val DAYS_PER_WEEK = 7
private const val DAYS_PER_MONTH = 30
