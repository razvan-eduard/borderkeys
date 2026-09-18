// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import com.borderkeys.predict.WordFold

/**
 * The words the "Block offensive words" switch keeps out of suggestions, corrections and
 * learning: one list per bundled language, in `assets/offensive/<tag>.txt`, merged for the
 * languages the user has turned on, the same way the accent overlays are.
 *
 * A list is one word per line, `#` comments and blank lines allowed, and every word is folded
 * on the way in ([WordFold]) so the comparison against a candidate never depends on how either
 * side was spelled. Nothing here decides what a person may type: the words only stop being
 * *offered*. Every failure is empty, not an exception -- a missing list means nothing is
 * blocked for that language, never a keyboard that will not start.
 */
object OffensiveWords {

    private const val DIRECTORY = "offensive"

    /** The folded words of one language's list, or empty when there is none. */
    fun load(assets: AssetManager, tag: String): Set<String> = runCatching {
        assets.open("$DIRECTORY/$tag.txt").use { parse(it.readBytes().decodeToString()) }
    }.getOrElse { emptySet() }

    /** Pure, so the format and the folding are tested on the JVM. */
    fun parse(text: String): Set<String> {
        val words = HashSet<String>()
        for (line in text.lineSequence()) {
            val entry = line.trim()
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue
            }
            words.add(WordFold.fold(entry))
        }
        return words
    }

    /** One set from several languages' lists. */
    fun merge(perLanguage: List<Set<String>>): Set<String> {
        if (perLanguage.size == 1) {
            return perLanguage[0]
        }
        val words = HashSet<String>()
        for (list in perLanguage) {
            words.addAll(list)
        }
        return words
    }
}
