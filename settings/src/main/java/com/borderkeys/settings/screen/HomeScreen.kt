// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.Screen
import com.borderkeys.settings.SettingsSearch
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.isBorderKeysDefault
import com.borderkeys.settings.isBorderKeysEnabled
import com.borderkeys.settings.rememberResumedCount
import com.borderkeys.settings.siblingPackage

@Composable
fun HomeScreen(query: String, modifier: Modifier = Modifier, open: (Screen) -> Unit) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    // Re-read whenever this screen comes back to the front.
    val resumed by rememberResumedCount()
    val enabled = remember(resumed) { isBorderKeysEnabled(context) }
    val isDefault = remember(resumed) { isBorderKeysDefault(context) }
    // Whether this is the plus build: the assistant service resolves only there.
    val hasAssistant = remember(context) {
        val intent = android.content.Intent()
            .setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        context.packageManager.resolveService(intent, 0) != null
    }

    // The setup card stays while anything is outstanding, including, in the plus build, a core
    // build to transfer from.
    val sibling = remember(context) { siblingPackage(context) }
    val canTransfer = remember(context, sibling) {
        sibling != null && runCatching {
            context.packageManager.getPackageInfo(sibling, 0)
        }.isSuccess
    }

    val matches = remember(query, strings, hasAssistant) {
        SettingsSearch.find(
            query,
            text = { strings[it] },
            hidden = if (hasAssistant) emptySet() else setOf(Screen.Assistant),
        )
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (query.isNotBlank()) {
            SettingsSectionCard(strings.getString(Keys.HOME_SEARCH_MATCHES, matches.size)) {
                if (matches.isEmpty()) {
                    Explanation(strings[Keys.HOME_SEARCH_NOTHING])
                }
                for (match in matches) {
                    SettingRow(title = match.title, subtitle = match.place, onClick = { open(match.screen) })
                }
            }
            return@Column
        }
        if (!enabled || !isDefault || canTransfer) {
            SettingsSectionCard(strings[Keys.HOME_NOT_FINISHED_YET]) {
                SettingRow(
                    title = strings[Keys.HOME_SET_UP_BORDERKEYS],
                    subtitle = when {
                        !enabled -> strings[Keys.HOME_THE_KEYBOARD_IS_NOT_ENABLED_IN]
                        !isDefault -> strings[Keys.HOME_ENABLED_BUT_ANOTHER_KEYBOARD_IS_STILL]
                        else -> strings[Keys.SETUP_IMPORT_NOTE]
                    },
                    onClick = { open(Screen.Setup) },
                )
            }
        }
        SettingsSectionCard(strings[Keys.HOME_TYPING]) {
            SettingRow(strings[Keys.HOME_LANGUAGES], strings[Keys.HOME_WHICH_DICTIONARIES_ARE_ACTIVE_AND_THEIR]) {
                open(Screen.Languages)
            }
            SettingRow(strings[Keys.HOME_LAYOUT], strings[Keys.HOME_WHICH_KEYS_AND_WHERE_SEPARATE_FROM]) {
                open(Screen.Layout)
            }
            SettingRow(
                strings[Keys.HOME_SUGGESTIONS_AND_CORRECTIONS],
                strings[Keys.HOME_THE_SUGGESTION_STRIP_AUTOCORRECT_AND_SWIPE],
            ) { open(Screen.Typing) }
            SettingRow(strings[Keys.HOME_DICTIONARY_AND_HEATMAP], strings[Keys.HOME_WHAT_THIS_DEVICE_HAS_LEARNED]) {
                open(Screen.Dictionary)
            }
            SettingRow(strings[Keys.HOME_CLIPBOARD], strings[Keys.HOME_HISTORY_PINNING_AND_HOW_LONG_IT]) {
                open(Screen.Clipboard)
            }
            SettingRow(
                strings[Keys.HOME_QUICK_ACTIONS],
                strings[Keys.HOME_QUICK_ACTIONS_NOTE],
            ) { open(Screen.QuickActions) }
            // In both builds; the screen says which of its buttons need a model.
            SettingRow(
                strings[Keys.SCREEN_DRAFT_BOX],
                strings[Keys.HOME_DRAFT_BOX],
            ) { open(Screen.Composer) }
            SettingRow(
                strings[Keys.SCREEN_BACKUP],
                strings[Keys.HOME_BACKUP],
            ) { open(Screen.Backup) }
            if (hasAssistant) {
                SettingRow(strings[Keys.HOME_TEXT_ASSISTANT], strings[Keys.HOME_SUMMARISE_CORRECT_AND_TRANSLATE_ON_THIS]) {
                    open(Screen.Assistant)
                }
            }
        }
        SettingsSectionCard(strings[Keys.HOME_APPEARANCE]) {
            SettingRow(strings[Keys.HOME_THEME], strings[Keys.HOME_COLOURS_CORNERS_AND_SPACING_WITH_A]) {
                open(Screen.Theme)
            }
            SettingRow(
                strings[Keys.HOME_SIZE_AND_POSITION],
                strings[Keys.HOME_HEIGHT_ONE_HANDED_MODE_FLOATING_AND],
            ) { open(Screen.Size) }
            SettingRow(
                strings[Keys.EFFECTS_TITLE],
                strings[Keys.HOME_EFFECTS_NOTE],
            ) { open(Screen.Effects) }
        }
        SettingsSectionCard(strings[Keys.SHORTCUTS_TITLE]) {
            // The gestures, each row led by a drawing of its key.
            ShortcutRow(R.drawable.bk_action_newline, strings[Keys.SHORTCUTS_ENTER])
            ShortcutRow(R.drawable.bk_icon_globe, strings[Keys.SHORTCUTS_GLOBE])
            ShortcutRow(R.drawable.bk_icon_space_bar, strings[Keys.SHORTCUTS_SPACE_HOLD])
            ShortcutRow(R.drawable.bk_icon_space_bar, strings[Keys.SHORTCUTS_SPACE])
            ShortcutRow(null, strings[Keys.SHORTCUTS_SHIFT_HOLD])
            ShortcutRow(R.drawable.bk_action_delete_word, strings[Keys.SHORTCUTS_BACKSPACE_HOLD])
            ShortcutRow(null, strings[Keys.SHORTCUTS_SUGGESTION])
        }

        SettingsSectionCard(strings[Keys.HOME_ABOUT]) {
            SettingRow(strings[Keys.FEATURES_TOUR], strings[Keys.FEATURES_TOUR_NOTE]) { open(Screen.Features) }
            SettingRow(strings[Keys.HOME_PRIVACY], strings[Keys.HOME_WHAT_IS_STORED_WHERE_AND_WHAT]) { open(Screen.Privacy) }
            SettingRow(strings[Keys.HOME_ABOUT_BORDERKEYS], strings[Keys.HOME_VERSION_SOURCE_CODE_AND_LICENCE]) { open(Screen.About) }
            Explanation(
                strings[Keys.HOME_BORDERKEYS_REQUESTS_NO_PERMISSIONS_AND_CONTA],
            )
        }
    }
}

/**
 * One gesture in the [Keys.SHORTCUTS_TITLE] card: the key it is about, drawn, and what holding
 * or sliding on it does, in text. [icon] is null for a gesture with no drawing of its key to
 * lead with -- a suggestion chip, or the shift key, whose glyph is the layout's own.
 */
@Composable
private fun ShortcutRow(icon: Int?, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
