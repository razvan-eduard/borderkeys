// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The two case edits the quick-action bar offers, on text alone -- no InputConnection here, so
 * both are testable and neither can move a cursor. [BorderKeysService] reads the field, asks
 * this what it should become, and writes back only what differs.
 */
object SentenceCase {

    /** The marks that close a sentence -- the same set [AutoShift] decides a sentence start by. */
    private const val SENTENCE_ENDINGS = ".!?…。！？"

    /**
     * "apple" becomes "Apple", "Apple" becomes "apple", and a word whose first character has
     * no case ("42", "élan" in a script without one) comes back as it was. The rest of the
     * word is never touched: "iPhone" toggles to "IPhone", not "Iphone", because the button
     * says one letter and means it.
     */
    fun toggleInitial(word: String): String {
        if (word.isEmpty()) {
            return word
        }
        val first = word[0]
        val flipped = when {
            first.isUpperCase() || first.isTitleCase() -> first.lowercaseChar()
            first.isLowerCase() -> first.titlecaseChar()
            else -> return word
        }
        return flipped + word.substring(1)
    }

    /**
     * The word the caret at [caret] is inside or touching, as a start/end pair -- the word's
     * characters are what [isWord] says they are. A caret between two words takes the one
     * before it, the same word "copy previous word" copies; null when there is no word at all
     * before or at the caret.
     */
    fun wordAt(text: CharSequence, caret: Int, isWord: (Int) -> Boolean): IntRange? {
        val at = caret.coerceIn(0, text.length)
        var start = at
        while (start > 0 && isWord(text[start - 1].code)) {
            start--
        }
        var end = at
        while (end < text.length && isWord(text[end].code)) {
            end++
        }
        if (start == end) {
            // Between words, or after a space: back over the separators to the word before.
            end = at
            while (end > 0 && !isWord(text[end - 1].code)) {
                end--
            }
            start = end
            while (start > 0 && isWord(text[start - 1].code)) {
                start--
            }
            if (start == end) {
                return null
            }
        }
        return start until end
    }

    /**
     * [text] with the first letter of every sentence capitalised and nothing else changed:
     * the first word, and the first word after a sentence mark or a line break. Same length
     * in, same length out -- a character is only ever swapped for its own capital -- which is
     * what lets the caller keep the caret exactly where it was.
     *
     * A sentence mark only ends a sentence when whitespace follows it, the rule [AutoShift]
     * applies while typing: "3.5 apples" and "www.example.com" keep their lower case after
     * the dot, "hello there. i am here" gets its I -- and so does "e.g. this", the same as
     * typing it would, since nothing here knows an abbreviation from a sentence. Quotes and
     * brackets between the mark and the next word ("he left." she said) are looked through,
     * the way a sentence start is looked for through them.
     */
    fun capitaliseSentences(text: String): String {
        val out = StringBuilder(text.length)
        // sentenceStart: the next letter begins a sentence. afterMark: a sentence mark was
        // seen and whitespace has not followed it yet.
        var sentenceStart = true
        var afterMark = false
        for (c in text) {
            when {
                c == '\n' -> {
                    sentenceStart = true
                    afterMark = false
                }
                c.isWhitespace() -> {
                    if (afterMark) {
                        sentenceStart = true
                        afterMark = false
                    }
                }
                c in SENTENCE_ENDINGS -> {
                    afterMark = true
                    sentenceStart = false
                }
                c.isLetter() -> {
                    if (sentenceStart) {
                        out.append(if (c.isLowerCase()) c.titlecaseChar() else c)
                        sentenceStart = false
                        afterMark = false
                        continue
                    }
                    sentenceStart = false
                    afterMark = false
                }
                c.isDigit() -> {
                    sentenceStart = false
                    afterMark = false
                }
                // Anything else -- quotes, brackets, dashes, emoji -- neither starts nor ends
                // a sentence; whatever state the text was in carries through it.
            }
            out.append(c)
        }
        return out.toString()
    }
}
