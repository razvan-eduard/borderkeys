// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/**
 * The word being written: its text, where each of its code points was typed, and how it began.
 * The text is written only through the methods here, which keep [taps] in step with it.
 */
class ComposingWord {

    /** What the field's composing region holds. */
    val text = StringBuilder(MAX_LENGTH)

    /** Where each code point of [text] was typed. */
    val taps = TapTrail()

    /** The word came from a swipe; a letter typed after it starts the next word. */
    var fromGesture = false

    /** Its first letter is a capital the user typed with shift. */
    var capitalisedByUser = false

    /** It is running text rather than an address, a path or code; decided at its first letter. */
    var runningText = true

    /** A space was inserted before it, as before a swiped word. */
    var autoSpaceBefore = false

    /** Appends [code], chosen at [keyIndex] at ([x], [y]); NaN coordinates for no point. */
    fun append(code: Int, keyIndex: Int, x: Float, y: Float) {
        text.appendCodePoint(code)
        taps.add(keyIndex, x, y)
    }

    /** Appends [word], which no tap chose. */
    fun appendUntapped(word: CharSequence) {
        text.append(word)
        taps.addUntapped(Character.codePointCount(word, 0, word.length))
    }

    /** Deletes the last code point and its tap. */
    fun deleteLast() {
        text.setLength(text.offsetByCodePoints(text.length, -1))
        taps.removeLast()
    }

    fun clear() {
        text.setLength(0)
        taps.clear()
    }

    private companion object {
        /** The capacity [text] starts with. */
        const val MAX_LENGTH = 48
    }
}
