// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

/**
 * Where the settings UI can be.
 *
 * An enum rather than a sealed hierarchy of `data object`s because nothing here carries an
 * argument between screens, `when` over an enum compiles to a switch on the ordinal, and
 * `entries` gives the back stack something cheap to hold.
 *
 * Not `navigation-compose`: that would bring a graph builder, a route parser and argument
 * encoding to move between thirteen screens that pass nothing to each other.
 */
enum class Screen(val titleKey: String) {
    Home(Keys.SCREEN_BORDERKEYS),
    Setup(Keys.SCREEN_SET_UP),
    Languages(Keys.SCREEN_LANGUAGES),
    Layout(Keys.SCREEN_LAYOUT),
    Theme(Keys.SCREEN_THEME),
    Size(Keys.SCREEN_SIZE_AND_POSITION),
    Swipe(Keys.SCREEN_SWIPE_TYPING),
    Corrections(Keys.SCREEN_SUGGESTIONS_AND_CORRECTIONS),
    Dictionary(Keys.SCREEN_PERSONAL_DICTIONARY),
    Clipboard(Keys.SCREEN_CLIPBOARD),
    Assistant(Keys.SCREEN_TEXT_ASSISTANT),
    Privacy(Keys.SCREEN_PRIVACY),
    About(Keys.SCREEN_ABOUT),
}
