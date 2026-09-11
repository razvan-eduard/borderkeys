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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.BackupPartSwitches
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingsSectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writing what this keyboard knows to a file, and reading one back.
 *
 * The only way anything moves between the two builds -- they are separate applications with
 * separate private directories -- and the only backup this application has at all, since system
 * backup is switched off on purpose.
 *
 * Four parts, each its own answer, because they are not equally private. A theme is a handful of
 * numbers. The dictionary is every word this device learned from what its owner typed. The
 * screen asks for a passphrase exactly when one of the private two is included, and says why.
 */
@Composable
fun BackupScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val backups = remember { DataGraph.backups }

    var parts by remember { mutableStateOf(BackupRepository.Parts(settings = true)) }
    var passphrase by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    /** Whether what has been chosen would put something private into the file. */
    val private = parts.dictionary || parts.clipboard

    val write = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFile.MIME_TYPE),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                BackupFile.write(backups.gather(parts), if (private) passphrase else "")
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            notice = if (ok) strings[Keys.BACKUP_WRITTEN] else strings[Keys.BACKUP_ERROR_DAMAGED]
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
            val result = withContext(Dispatchers.Default) { BackupFile.read(text, passphrase) }
            val payload = result.payload
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
            // Everything the file turned out to have. Asking again which parts to take would be
            // asking about a file the user has not seen the contents of.
            withContext(Dispatchers.IO) { backups.apply(payload, backups.contentsOf(payload)) }
            notice = strings[Keys.BACKUP_IMPORTED]
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
                // Refused rather than written unprotected: the passphrase is the only thing
                // standing between a dictionary and whatever else can read the folder it lands in.
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
}
