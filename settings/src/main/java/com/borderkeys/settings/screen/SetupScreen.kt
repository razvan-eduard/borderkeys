// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
import com.borderkeys.settings.SectionHeader

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
    val context = LocalContext.current
    var probe by remember { mutableStateOf("") }
    val enabled = isEnabled(context)
    val isDefault = isDefault(context)

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Step 1 — enable it")
        Explanation(
            if (enabled) {
                "Done. BorderKeys appears in the system's list of input methods."
            } else {
                "Android will warn that a keyboard can read everything you type. That warning " +
                    "is shown for every keyboard and it is correct. What makes it a smaller " +
                    "risk here is that this one holds no permissions and has no way to send " +
                    "anything anywhere — which the Privacy screen explains and you can verify."
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
        ) { Text(if (enabled) "Open input method settings" else "Enable BorderKeys") }

        SectionHeader("Step 2 — switch to it")
        Explanation(
            if (isDefault) {
                "Done. BorderKeys is the current keyboard."
            } else {
                "Enabled but not selected. The picker below switches between the keyboards you " +
                    "have enabled."
            },
        )
        Button(
            onClick = {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showInputMethodPicker()
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        ) { Text("Choose keyboard") }

        SectionHeader("Try it")
        Explanation("A real text field. Whatever you type here stays on this screen.")
        OutlinedTextField(
            value = probe,
            onValueChange = { probe = it },
            label = { Text("Type here") },
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        )
        Text(
            "This field is not saved anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
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
