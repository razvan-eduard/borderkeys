// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/** The quick-action bar's two case edits, on text alone. */
object SentenceCase {

    /** The marks that close a sentence, as in [AutoShift]. */
    private const val SENTENCE_ENDINGS = ".!?…。！？"

    /** [word] with its first character's case toggled and the rest untouched. */
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
     * The range of the word the caret at [caret] is inside or touching, else of the word before
     * it; null when there is none. [isWord] says which characters belong to words.
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
     * [text] with the first letter of every sentence capitalised and nothing else changed, same
     * length in and out. A sentence starts the text, follows a line break, or follows a sentence
     * mark and whitespace, with quotes and brackets looked through.
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
