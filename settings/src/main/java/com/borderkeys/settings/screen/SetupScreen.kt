// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Getting the keyboard switched on, in order.
 *
 * Each step is locked until the one above it is done, and turns green when it is. Not decoration:
 * the steps genuinely depend on each other, and two of them are destructive in the wrong order --
 * removing the old application before its settings have been brought across loses them, and
 * removing it while it is still the keyboard in use leaves the phone with none.
 *
 * The first two cannot be done for the user. Enabling an input method and choosing it are system
 * decisions by design, because an application that could make itself the keyboard unasked would
 * be a keylogger. All this screen does is say which step is outstanding and open the right dialog.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier, open: (Screen) -> Unit = {}) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf("") }
    var transferred by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    // Re-read whenever this screen comes back to the front. Both answers live in system
    // settings, and every button here sends the user out to change them -- a screen that read
    // them once at composition would show the state they were in before the user did the thing
    // it asked for, and the step below would stay locked after being finished.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumed += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val enabled = remember(resumed) { isEnabled(context) }
    val isDefault = remember(resumed) { isDefault(context) }

    /**
     * Whether the other build is on this device and this one is the newcomer.
     *
     * Only interesting in that direction. Somebody installing the assistant build beside one
     * they have used for months has a dictionary worth carrying; the reverse is a fresh install
     * being offered something from an application it is not sure exists.
     */
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
                onClick = {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showInputMethodPicker()
                },
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
                    // One tap: the other build is asked directly and answers with the data,
                    // rather than the user writing a file, finding it, and deleting it after.
                    onClick = {
                        take.launch(
                            Intent()
                                .setClassName(sibling!!, TransferProtocol.SETTINGS_CLASS)
                                .putExtra(TransferProtocol.EXTRA_REQUEST, true),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SETUP_TAKE_SETTINGS]) }
                // The long way round, for a phone where the other build is not installed any
                // more but its file is.
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
                // Only after the settings are here, and only once this keyboard is the one in
                // use. Removing the other application while it is still the current keyboard
                // would leave the phone with none at all.
                unlocked = transferred && isDefault,
            ) {
                Explanation(strings[Keys.SETUP_REMOVE_NOTE])
                Button(
                    onClick = {
                        // The system asks for confirmation and does the removing. No
                        // application can uninstall another one on its own, which is right.
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
 * One step: a tick when it is done, and nothing to press until the one above it is.
 *
 * A locked step is shown rather than hidden. Hiding it would make the screen shorter and the
 * process longer -- somebody who cannot see what comes next cannot tell whether they are nearly
 * finished, and a screen that grows a new card each time you look away is unsettling.
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

/**
 * Green, in both themes.
 *
 * The scheme has no "finished" colour -- primary means "press this" and would say the opposite
 * of what a completed step means. Two constants, because one green that reads on a white card
 * is a green that disappears on a dark one.
 */
@Composable
private fun doneColour(): Color =
    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

private fun isEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
}

/**
 * Whether *this* build is the keyboard in use.
 *
 * The package is compared exactly, not as a prefix. Since the assistant build's name is this
 * one's with a suffix, "com.borderkeys" is a prefix of "com.borderkeys.plus/..." -- so a prefix
 * test made the core build believe it was the current keyboard whenever the other one was.
 */
private fun isDefault(context: Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return current.substringBefore('/') == context.packageName
}

/**
 * The package name of the other build, or null when this one has no sibling.
 *
 * Derived from this application's own name rather than written down twice: the assistant build
 * is the core one with a suffix, so one of them is the other with the suffix removed.
 */
private fun siblingPackage(context: Context): String? {
    val self = context.packageName
    return if (self.endsWith(PLUS_SUFFIX)) self.removeSuffix(PLUS_SUFFIX) else null
}

/**
 * Whether a package is on this device.
 *
 * Needs a <queries> entry in the manifest since API 30, which is a declaration of what this
 * application may look for and not a permission: nothing is requested and nothing is granted.
 */
private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(packageName, 0)
}.isSuccess

private const val PLUS_SUFFIX = ".plus"
