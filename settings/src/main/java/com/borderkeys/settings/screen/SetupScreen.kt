// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SettingsSectionCard

/**
 * Getting the keyboard switched on.
 *
 * Two steps, and neither can be done for the user: enabling an input method and choosing it are
 * both system decisions, by design, because an app that could make itself the keyboard without
 * being asked would be a keylogger. All this screen can do is say which step is outstanding and
 * open the right system dialog.
 */
@Composable
fun SetupScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    var probe by remember { mutableStateOf("") }
    val enabled = isEnabled(context)
    val isDefault = isDefault(context)

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SETUP_STEP_1_ENABLE_IT]) {
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
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(if (enabled) strings[Keys.SETUP_OPEN_INPUT_METHOD_SETTINGS] else strings[Keys.SETUP_ENABLE_BORDERKEYS]) }
        }
        SettingsSectionCard(strings[Keys.SETUP_STEP_2_SWITCH_TO_IT]) {
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
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(strings[Keys.SETUP_CHOOSE_KEYBOARD]) }
        }
        SettingsSectionCard(strings[Keys.SETUP_TRY_IT]) {
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

private fun isEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isDefault(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.startsWith(context.packageName) == true
