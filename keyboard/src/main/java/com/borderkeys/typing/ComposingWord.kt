// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The word being written: its text and how it began. */
class ComposingWord {

    /** What the field's composing region holds. */
    val text = StringBuilder(MAX_LENGTH)

    /** The word came from a swipe; a letter typed after it starts the next word. */
    var fromGesture = false

    /** Its first letter is a capital the user typed with shift. */
    var capitalisedByUser = false

    /** It is running text rather than an address, a path or code; decided at its first letter. */
    var runningText = true

    /** A space was inserted before it, as before a swiped word. */
    var autoSpaceBefore = false

    private companion object {
        /** The capacity [text] starts with. */
        const val MAX_LENGTH = 48
    }
}
