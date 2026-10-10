// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.Disableable
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.SettingRow

/**
 * Remember screenshots: a switch that keeps each new screenshot in the clipboard history and
 * offers the newest on the strip, then how it sits beside the clipboard chip, beside it or in its
 * place when newer, disabled while the switch is off. Turning it on with no folder yet opens the
 * system's folder picker, started at [likelyScreenshotFolder]; the folder's grant is kept across
 * restarts. Turning it off deletes the screenshots kept.
 */
@Composable
fun ScreenshotSuggestionSetting(
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
    enabled: Boolean = true,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The arrangement chosen while the picker is open, applied once a folder comes back.
    var pending by remember { mutableIntStateOf(KeyboardPreferences.SCREENSHOT_SUGGESTION_BESIDE) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) {
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess {
            val mode = preferences.screenshotSuggestion.takeIf { it != KeyboardPreferences.SCREENSHOT_SUGGESTION_OFF }
                ?: pending
            val previous = preferences.screenshotFolder
            if (previous.isNotEmpty() && previous != tree.toString()) {
                releaseFolder(context, previous)
            }
            update { it.copy(screenshotFolder = tree.toString(), screenshotSuggestion = mode) }
        }
    }

    fun choose(mode: Int) {
        if (preferences.screenshotFolder.isEmpty()) {
            pending = mode
            pickFolder.launch(likelyScreenshotFolder())
        } else {
            update { it.copy(screenshotSuggestion = mode) }
        }
    }

    SwitchRow(
        title = strings[Keys.CLIPBOARD_SCREENSHOT_SUGGESTION],
        subtitle = strings[Keys.CLIPBOARD_SCREENSHOT_NOTE],
        checked = preferences.screenshotsRemembered,
        enabled = enabled,
    ) { on ->
        if (on) {
            choose(KeyboardPreferences.SCREENSHOT_SUGGESTION_BESIDE)
        } else {
            update { it.copy(screenshotSuggestion = KeyboardPreferences.SCREENSHOT_SUGGESTION_OFF) }
            scope.launch { DataGraph.clipboard.deleteScreenshots() }
        }
    }
    Disableable(disabled = !enabled || !preferences.screenshotsRemembered) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PickerChip(
                strings[Keys.CLIPBOARD_SCREENSHOT_BESIDE],
                preferences.screenshotSuggestion == KeyboardPreferences.SCREENSHOT_SUGGESTION_BESIDE,
            ) { choose(KeyboardPreferences.SCREENSHOT_SUGGESTION_BESIDE) }
            PickerChip(
                strings[Keys.CLIPBOARD_SCREENSHOT_NEWER],
                preferences.screenshotSuggestion == KeyboardPreferences.SCREENSHOT_SUGGESTION_NEWER,
            ) { choose(KeyboardPreferences.SCREENSHOT_SUGGESTION_NEWER) }
        }
    }
    if (preferences.screenshotFolder.isNotEmpty()) {
        SettingRow(
            title = strings.getString(Keys.CLIPBOARD_SCREENSHOT_FOLDER, folderName(preferences.screenshotFolder)),
            trailing = {
                TextButton(onClick = { pickFolder.launch(likelyScreenshotFolder()) }) {
                    Text(strings[Keys.CLIPBOARD_SCREENSHOT_CHANGE_FOLDER])
                }
            },
        )
    }
}

/** The folder the picker starts in: where this phone's maker saves screenshots, most likely. */
private fun likelyScreenshotFolder(): Uri {
    val maker = Build.MANUFACTURER.lowercase()
    val path = if (maker in DCIM_MAKERS) "DCIM/Screenshots" else "Pictures/Screenshots"
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")
}

/** A granted folder's path as the user knows it, from its tree URI. */
private fun folderName(tree: String): String = runCatching {
    DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':')
}.getOrNull()?.ifEmpty { null } ?: tree

/** Gives back the grant for a folder no longer used. */
private fun releaseFolder(context: android.content.Context, tree: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** The authority of the phone's shared storage. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** Makers whose phones save screenshots under DCIM. */
private val DCIM_MAKERS = setOf("samsung", "xiaomi", "redmi", "poco")
