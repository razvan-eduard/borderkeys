// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.borderkeys.data.DataGraph
import com.borderkeys.data.backup.BackupFile
import com.borderkeys.data.backup.BackupPayload
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.BackupPartSwitches
import com.borderkeys.settings.BackupReviewChecklist
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingsSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writing what this keyboard knows to a file, and reading one back. Seven parts, each chosen
 * separately; a passphrase is required when the dictionary or the clipboard is included. A file
 * that is read is shown as a checklist ([BackupReviewDialog]) before anything is applied.
 */
@Composable
fun BackupScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val backups = remember { DataGraph.backups }

    var parts by remember { mutableStateOf(BackupRepository.Parts(settings = true, theme = true)) }
    var passphrase by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    // A read file awaiting review, or null. reviewDetected is fixed when the file is read;
    // reviewSelection starts as a copy of it and narrows as rows are unticked.
    var pendingImport by remember { mutableStateOf<BackupPayload?>(null) }
    var reviewDetected by remember { mutableStateOf(BackupRepository.Parts()) }
    var reviewSelection by remember { mutableStateOf(BackupRepository.Parts()) }

    /** Whether what has been chosen would put something private into the file. */
    val private = parts.dictionary || parts.clipboard

    val write = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFile.MIME_TYPE),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            notice = strings[Keys.BACKUP_WORKING]
            val text = withContext(Dispatchers.IO) {
                BackupFile.write(backups.gather(parts), if (private) passphrase else "")
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            notice = if (ok) strings[Keys.BACKUP_WRITTEN] else strings[Keys.BACKUP_ERROR_WRITE_FAILED]
        }
    }

    val read = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                notice = strings[Keys.BACKUP_ERROR_DAMAGED]
                return@launch
            }
            notice = strings[Keys.BACKUP_WORKING]
            val result = withContext(Dispatchers.Default) { BackupFile.read(text, passphrase) }
            val payload = result.payload
            notice = ""
            if (payload == null) {
                notice = when (result.failure) {
                    BackupFile.Failure.WRONG_PASSPHRASE ->
                        strings[Keys.BACKUP_ERROR_WRONG_PASSPHRASE]
                    BackupFile.Failure.NEEDS_PASSPHRASE ->
                        strings[Keys.BACKUP_ERROR_NEEDS_PASSPHRASE]
                    BackupFile.Failure.DAMAGED -> strings[Keys.BACKUP_ERROR_DAMAGED]
                    else -> strings[Keys.BACKUP_ERROR_NOT_A_BACKUP]
                }
                return@launch
            }
            // Handed to the review dialog, all ticked, before anything is written.
            val contents = backups.contentsOf(payload)
            reviewDetected = contents
            reviewSelection = contents
            pendingImport = payload
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.BACKUP_WHAT]) {
            BackupPartSwitches(parts) { parts = it }
        }

        SettingsSectionCard(strings[Keys.BACKUP_PASSPHRASE]) {
            Explanation(
                if (private) {
                    strings[Keys.BACKUP_PASSPHRASE_NEEDED]
                } else {
                    strings[Keys.BACKUP_PASSPHRASE_NOT_NEEDED]
                },
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(strings[Keys.BACKUP_PASSPHRASE]) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        SettingsSectionCard(strings[Keys.BACKUP_EXPORT]) {
            Button(
                // The private parts are never written without a passphrase.
                enabled = parts.any && (!private || passphrase.isNotEmpty()),
                onClick = { write.launch(BackupFile.SUGGESTED_NAME) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.BACKUP_WRITE]) }
        }

        SettingsSectionCard(strings[Keys.BACKUP_IMPORT]) {
            Explanation(strings[Keys.BACKUP_IMPORT_NOTE])
            Button(
                onClick = { read.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.BACKUP_CHOOSE]) }
            if (notice.isNotEmpty()) {
                Explanation(notice)
            }
        }
    }

    val importing = pendingImport
    if (importing != null) {
        BackupReviewDialog(
            detected = reviewDetected,
            selected = reviewSelection,
            onSelectedChange = { reviewSelection = it },
            onConfirm = {
                pendingImport = null
                scope.launch {
                    withContext(Dispatchers.IO) { backups.apply(importing, reviewSelection) }
                    notice = strings[Keys.BACKUP_IMPORTED]
                }
            },
            onDismiss = { pendingImport = null },
        )
    }
}

/**
 * What a read file carries, ticked so it can be narrowed before anything is written. [detected]
 * never changes once shown; only [selected] moves as rows are unticked.
 */
@Composable
private fun BackupReviewDialog(
    detected: BackupRepository.Parts,
    selected: BackupRepository.Parts,
    onSelectedChange: (BackupRepository.Parts) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[Keys.BACKUP_REVIEW_TITLE]) },
        text = { BackupReviewChecklist(detected, selected, onSelectedChange) },
        confirmButton = {
            TextButton(enabled = selected.any, onClick = onConfirm) {
                Text(strings[Keys.BACKUP_REVIEW_IMPORT])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[Keys.BACKUP_REVIEW_CANCEL]) }
        },
    )
}
