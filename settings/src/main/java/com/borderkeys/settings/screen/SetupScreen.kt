// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.borderkeys.data.DataGraph
import com.borderkeys.data.backup.BackupFile
import com.borderkeys.data.backup.TransferProtocol
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.Screen
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.isBorderKeysDefault
import com.borderkeys.settings.isBorderKeysEnabled
import com.borderkeys.settings.openKeyboardPicker
import com.borderkeys.settings.rememberResumedCount
import com.borderkeys.settings.siblingPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Getting the keyboard switched on, in order. Each step is locked until the one above it is done,
 * and turns green when it is.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, open: (Screen) -> Unit = {}) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf("") }
    var transferred by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    // Re-read whenever this screen comes back to the front.
    val resumed by rememberResumedCount()
    val enabled = remember(resumed) { isBorderKeysEnabled(context) }
    val isDefault = remember(resumed) { isBorderKeysDefault(context) }

    // The core build's package from the plus build, or null: the transfer runs one way only.
    val sibling = remember { siblingPackage(context) }
    val canTransfer = sibling != null && isInstalled(context, sibling)

    val take = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            notice = strings[Keys.SETUP_REFUSED]
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val applied = withContext(Dispatchers.IO) {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull() ?: return@withContext false
                val payload = BackupFile.read(text, passphrase = "").payload
                    ?: return@withContext false
                DataGraph.backups.apply(payload, DataGraph.backups.contentsOf(payload))
                true
            }
            transferred = applied
            notice = if (applied) strings[Keys.SETUP_TAKEN] else strings[Keys.BACKUP_ERROR_DAMAGED]
        }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Step(
            title = strings[Keys.SETUP_STEP_1_ENABLE_IT],
            done = enabled,
            unlocked = true,
        ) {
            Explanation(
                if (enabled) {
                    strings[Keys.SETUP_DONE_BORDERKEYS_APPEARS_IN_THE_SYSTEM]
                } else {
                    strings[Keys.SETUP_ANDROID_WILL_WARN_THAT_A_KEYBOARD]
                },
            )
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    if (enabled) {
                        strings[Keys.SETUP_OPEN_INPUT_METHOD_SETTINGS]
                    } else {
                        strings[Keys.SETUP_ENABLE_BORDERKEYS]
                    },
                )
            }
        }

        Step(
            title = strings[Keys.SETUP_STEP_2_SWITCH_TO_IT],
            done = isDefault,
            unlocked = enabled,
        ) {
            Explanation(
                if (isDefault) {
                    strings[Keys.SETUP_DONE_BORDERKEYS_IS_THE_CURRENT_KEYBOARD]
                } else {
                    strings[Keys.SETUP_ENABLED_BUT_NOT_SELECTED_THE_PICKER]
                },
            )
            Button(
                onClick = { openKeyboardPicker(context) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.SETUP_CHOOSE_KEYBOARD]) }
        }

        if (canTransfer) {
            Step(
                title = strings[Keys.SETUP_STEP_IMPORT],
                done = transferred,
                unlocked = isDefault,
            ) {
                Explanation(strings[Keys.SETUP_IMPORT_NOTE])
                Button(
                    // Asks the other build directly for its data.
                    onClick = {
                        take.launch(
                            Intent()
                                .setClassName(sibling!!, TransferProtocol.SETTINGS_CLASS)
                                .putExtra(TransferProtocol.EXTRA_REQUEST, true),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SETUP_TAKE_SETTINGS]) }
                // From a backup file, for when the other build is no longer installed.
                Button(
                    onClick = { open(Screen.Backup) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) { Text(strings[Keys.SETUP_OPEN_IMPORT]) }
                if (notice.isNotEmpty()) {
                    Explanation(notice)
                }
            }

            Step(
                title = strings[Keys.SETUP_STEP_REMOVE],
                done = false,
                // Only after the transfer, and once this keyboard is the one in use.
                unlocked = transferred && isDefault,
            ) {
                Explanation(strings[Keys.SETUP_REMOVE_NOTE])
                Button(
                    onClick = {
                        // The system confirms and uninstalls.
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, "package:$sibling".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SETUP_REMOVE]) }
            }
        }

        Step(
            title = strings[Keys.SETUP_TRY_IT],
            done = probe.isNotEmpty(),
            unlocked = isDefault,
        ) {
            Explanation(strings[Keys.SETUP_A_REAL_TEXT_FIELD_WHATEVER_YOU])
            OutlinedTextField(
                value = probe,
                onValueChange = { probe = it },
                label = { Text(strings[Keys.SETUP_TYPE_HERE]) },
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
            Text(
                strings[Keys.SETUP_THIS_FIELD_IS_NOT_SAVED_ANYWHERE],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One step: a tick when it is done, and nothing to press until the one above it is. A locked step
 * is shown, not hidden.
 */
@Composable
private fun Step(
    title: String,
    done: Boolean,
    unlocked: Boolean,
    content: @Composable () -> Unit,
) {
    val strings = LocalStrings.current
    SettingsSectionCard(title) {
        if (done) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.checkbox_on_background),
                    contentDescription = null,
                    tint = doneColour(),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    strings[Keys.SETUP_DONE],
                    style = MaterialTheme.typography.labelLarge,
                    color = doneColour(),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (unlocked) {
            content()
        } else {
            Explanation(strings[Keys.SETUP_STEP_LOCKED])
        }
    }
}

/** The green of a finished step, one shade per theme. */
@Composable
private fun doneColour(): Color =
    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/** Whether a package is on this device; from API 30 it needs a <queries> entry in the manifest. */
private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(packageName, 0)
}.isSuccess
