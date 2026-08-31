// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow

/**
 * Which keys, and where they are.
 *
 * A separate screen from Languages, and the explanation on it is the point: a layout decides
 * what the keys look like, and the language packs decide what the keyboard knows. A Romanian
 * QWERTY with Romanian, English and French dictionaries all active is a normal configuration,
 * and switching layout does not change what it knows.
 *
 * Layouts are the input method's subtypes, so the platform owns the list and the enabling. That
 * is why this screen opens a system dialog rather than showing checkboxes: two places to enable
 * the same thing is how they end up disagreeing.
 */
@Composable
fun LayoutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val subtypes = remember {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.let { info -> (0 until info.subtypeCount).map { info.getSubtypeAt(it) } }
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Layouts on this keyboard")
        if (subtypes.isEmpty()) {
            SettingRow(
                title = "None enabled yet",
                subtitle = "Enable BorderKeys first, then its layouts appear here.",
            )
        }
        for (subtype in subtypes) {
            SettingRow(
                title = subtype.languageTag.ifEmpty { "Layout" },
                subtitle = subtype.extraValue.ifEmpty { "keyboard" },
            )
        }
        Button(
            onClick = {
                context.startActivity(
                    Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS")
                        .putExtra(android.provider.Settings.EXTRA_INPUT_METHOD_ID,
                            "${context.packageName}/com.borderkeys.ime.BorderKeysService")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) { Text("Choose which layouts are enabled") }

        Divider()
        Explanation(
            "A layout and a language are different things here. The layout decides which keys " +
                "exist and which diacritics sit on which long press. The dictionaries decide " +
                "what the keyboard knows, and up to four of them are consulted at once — so a " +
                "Romanian layout with Romanian and English both active is normal, and switching " +
                "layout does not switch what it knows.",
        )
        Explanation(
            "The globe key cycles between the layouts you enabled above, without leaving " +
                "BorderKeys.",
        )
    }
}
