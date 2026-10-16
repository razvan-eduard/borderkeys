// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.borderkeys.data.PersonalEntries
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SPACE_GUTTER
import com.borderkeys.settings.SPACE_MEDIUM
import com.borderkeys.settings.SPACE_XSMALL

/** The language an entry is added under, one chip per enabled language tag, [selected] marked. */
@Composable
internal fun LanguageChips(languages: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
        horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
    ) {
        for (tag in languages) {
            PickerChip(displayNameFor(tag), selected = tag == selected) { onSelect(tag) }
        }
    }
}

/** The filter by origin: every entry, those learned by use, or those added by hand. */
@Composable
internal fun OriginChips(origin: PersonalEntries.Origin, onSelect: (PersonalEntries.Origin) -> Unit) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
        horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
    ) {
        for ((value, labelKey) in ORIGIN_LABELS) {
            PickerChip(strings[labelKey], selected = origin == value) { onSelect(value) }
        }
    }
}

/** The filter chips, in order: each origin with its label's key. */
private val ORIGIN_LABELS = listOf(
    PersonalEntries.Origin.ANY to Keys.DICTIONARY_FILTER_ALL,
    PersonalEntries.Origin.LEARNED to Keys.DICTIONARY_FILTER_LEARNED,
    PersonalEntries.Origin.ADDED to Keys.DICTIONARY_FILTER_ADDED,
)
