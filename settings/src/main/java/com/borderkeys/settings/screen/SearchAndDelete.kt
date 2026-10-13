// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
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

/**
 * The search over a list, and below it the button that deletes what the search lists, while it
 * filters, or [clearAllLabel]'s button otherwise. Each asks first: [deleteListedTitle] over the
 * entries listed when it was pressed, [clearAllTitle] over everything.
 */
@Composable
internal fun <T> SearchAndDelete(
    query: String,
    onQuery: (String) -> Unit,
    listed: List<T>,
    deleteListedLabel: String,
    deleteListedTitle: String,
    onDeleteListed: (List<T>) -> Unit,
    clearAllLabel: String,
    clearAllTitle: String,
    clearAllConfirm: String,
    clearAllEnabled: Boolean,
    onClearAll: () -> Unit,
) {
    val strings = LocalStrings.current
    var confirmingClearAll by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<List<T>?>(null) }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(strings[Keys.DICTIONARY_SEARCH]) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_MEDIUM),
    )
    if (query.isNotBlank()) {
        TextButton(
            onClick = { confirmingDelete = listed },
            enabled = listed.isNotEmpty(),
            modifier = Modifier.padding(horizontal = SPACE_LARGE),
        ) { Text(deleteListedLabel) }
    } else {
        TextButton(
            onClick = { confirmingClearAll = true },
            enabled = clearAllEnabled,
            modifier = Modifier.padding(horizontal = SPACE_LARGE),
        ) { Text(clearAllLabel) }
    }

    if (confirmingClearAll) {
        ConfirmDialog(
            title = clearAllTitle,
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = clearAllConfirm,
            onDismiss = { confirmingClearAll = false },
        ) { onClearAll() }
    }
    confirmingDelete?.let { doomed ->
        ConfirmDialog(
            title = deleteListedTitle,
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_DELETE],
            onDismiss = { confirmingDelete = null },
        ) { onDeleteListed(doomed) }
    }
}
