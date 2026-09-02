// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

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
import com.borderkeys.settings.SettingsSectionCard
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
    val strings = LocalStrings.current
    val context = LocalContext.current
    val subtypes = remember {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.enabledInputMethodList
            ?.firstOrNull { it.packageName == context.packageName }
            ?.let { info -> (0 until info.subtypeCount).map { info.getSubtypeAt(it) } }
            .orEmpty()
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.LAYOUT_LAYOUTS_ON_THIS_KEYBOARD]) {
            if (subtypes.isEmpty()) {
                SettingRow(
                    title = strings[Keys.LAYOUT_NONE_ENABLED_YET],
                    subtitle = strings[Keys.LAYOUT_ENABLE_BORDERKEYS_FIRST_THEN_ITS_LAYOUTS],
                )
            }
            for (subtype in subtypes) {
                SettingRow(
                    title = subtype.languageTag.ifEmpty { strings[Keys.LAYOUT_LAYOUT] },
                    subtitle = subtype.extraValue.ifEmpty { strings[Keys.LAYOUT_KEYBOARD] },
                )
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS")
                            .putExtra(android.provider.Settings.EXTRA_INPUT_METHOD_ID,
                                strings.getString(Keys.LAYOUT_COM_BORDERKEYS_IME_BORDERKEYSSERVICE, context.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) { Text(strings[Keys.LAYOUT_CHOOSE_WHICH_LAYOUTS_ARE_ENABLED]) }
            Explanation(
                strings[Keys.LAYOUT_A_LAYOUT_AND_A_LANGUAGE_ARE],
            )
            Explanation(
                strings[Keys.LAYOUT_THE_GLOBE_KEY_CYCLES_BETWEEN_THE],
            )
        }
    }
}
