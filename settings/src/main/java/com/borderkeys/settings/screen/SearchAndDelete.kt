// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SPACE_GUTTER
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SPACE_MEDIUM
import com.borderkeys.settings.TextBox

/** The search box over a list. */
@Composable
internal fun SearchBox(query: String, onQuery: (String) -> Unit) {
    val strings = LocalStrings.current
    TextBox(
        value = query,
        onValueChange = onQuery,
        label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_MEDIUM),
    )
}

/**
 * The button that deletes [listed], enabled while something is listed; it asks first, with
 * [title] over the entries listed when it was pressed, and hands those to [onDelete].
 */
@Composable
internal fun <T> DeleteListed(
    label: String,
    title: String,
    confirmLabel: String,
    listed: List<T>,
    onDelete: (List<T>) -> Unit,
) {
    val strings = LocalStrings.current
    var confirming by remember { mutableStateOf<List<T>?>(null) }
    TextButton(
        onClick = { confirming = listed },
        enabled = listed.isNotEmpty(),
        modifier = Modifier.padding(horizontal = SPACE_LARGE),
    ) { Text(label) }
    confirming?.let { doomed ->
        ConfirmDialog(
            title = title,
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = confirmLabel,
            onDismiss = { confirming = null },
        ) { onDelete(doomed) }
    }
}
