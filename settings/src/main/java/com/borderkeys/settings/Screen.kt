// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

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
enum class Screen(val title: String) {
    Home("BorderKeys"),
    Setup("Set up"),
    Languages("Languages"),
    Layout("Layout"),
    Theme("Theme"),
    Size("Size and position"),
    Swipe("Swipe typing"),
    Corrections("Suggestions and corrections"),
    Dictionary("Personal dictionary"),
    Clipboard("Clipboard"),
    Assistant("Text assistant"),
    Privacy("Privacy"),
    About("About"),
}
