// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.borderkeys.data.DataGraph
import com.borderkeys.data.backup.BackupFile
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.data.backup.TransferProtocol
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.BackupPartSwitches
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingsSectionCard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Somebody else's application is asking for this one's settings.
 *
 * Shown instead of the settings when the activity was started with a transfer request from a
 * build signed with the same certificate. The signature has already been checked by the time
 * this appears; what this screen adds is the half a signature cannot: that a person saw the
 * request, saw which application made it, and chose what to answer with.
 *
 * The answer never touches shared storage. It goes into this application's own cache and comes
 * back as a URI with a read grant that dies with the activity result, so there is nothing to
 * find afterwards and nothing to delete.
 */
@Composable
fun TransferScreen(
    askingLabel: String,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backups = remember { DataGraph.backups }
    var parts by remember {
        mutableStateOf(BackupRepository.Parts(settings = true, dictionary = true, languages = true))
    }
    var sending by remember { mutableStateOf(false) }

    fun finish(result: Int, data: Intent? = null) {
        val activity = context as? Activity ?: return
        activity.setResult(result, data)
        activity.finish()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.TRANSFER_TITLE]) {
            Explanation(strings.getString(Keys.TRANSFER_ASKING, askingLabel))
            Explanation(strings[Keys.TRANSFER_NOTE])
        }

        SettingsSectionCard(strings[Keys.BACKUP_WHAT]) {
            BackupPartSwitches(parts) { parts = it }
        }

        SettingsSectionCard(strings[Keys.TRANSFER_SEND]) {
            Button(
                enabled = parts.any && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { writeAnswer(context, backups, parts) }
                        if (uri == null) {
                            finish(Activity.RESULT_CANCELED)
                            return@launch
                        }
                        finish(
                            Activity.RESULT_OK,
                            Intent().setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.TRANSFER_SEND]) }
            TextButton(
                onClick = { finish(Activity.RESULT_CANCELED) },
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(strings[Keys.TRANSFER_REFUSE]) }
        }
    }
}

/**
 * Writes the answer into this application's cache and returns a URI for it.
 *
 * No passphrase. The file is never anywhere the user or another application could reach: it
 * lives in a private cache directory and is handed over as a grant that lasts one read. A
 * passphrase here would be a password to protect a room from its only two occupants.
 */
private suspend fun writeAnswer(
    context: android.content.Context,
    backups: BackupRepository,
    parts: BackupRepository.Parts,
): android.net.Uri? = runCatching {
    val directory = File(context.cacheDir, "transfer").apply { mkdirs() }
    val file = File(directory, TransferProtocol.FILE_NAME)
    file.writeText(BackupFile.write(backups.gather(parts), passphrase = ""))
    FileProvider.getUriForFile(
        context,
        context.packageName + TransferProtocol.PROVIDER_SUFFIX,
        file,
    )
}.getOrNull()
