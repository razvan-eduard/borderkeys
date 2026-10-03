// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

/** Where the settings UI can be. */
enum class Screen(val titleKey: String) {
    Home(Keys.SCREEN_BORDERKEYS),
    Setup(Keys.SCREEN_SET_UP),
    Features(Keys.FEATURES_TITLE),
    Languages(Keys.SCREEN_LANGUAGES),
    Layout(Keys.SCREEN_LAYOUT),
    KeyFlicks(Keys.SCREEN_KEY_FLICKS),
    Theme(Keys.SCREEN_THEME),
    Size(Keys.SCREEN_SIZE_AND_POSITION),
    Effects(Keys.SCREEN_PARTICLE_EFFECTS),
    Typing(Keys.SCREEN_SUGGESTIONS_AND_CORRECTIONS),
    Dictionary(Keys.SCREEN_DICTIONARY_AND_HEATMAP),
    LearnedWords(Keys.SCREEN_LEARNED_WORDS),
    LearnedPhrases(Keys.SCREEN_LEARNED_PHRASES),
    Clipboard(Keys.SCREEN_CLIPBOARD),
    QuickActions(Keys.SCREEN_QUICK_ACTIONS),
    Composer(Keys.SCREEN_DRAFT_BOX),
    Backup(Keys.SCREEN_BACKUP),
    Assistant(Keys.SCREEN_TEXT_ASSISTANT),
    Privacy(Keys.SCREEN_PRIVACY),
    About(Keys.SCREEN_ABOUT),
}
