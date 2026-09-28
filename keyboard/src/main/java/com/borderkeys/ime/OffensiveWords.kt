// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import com.borderkeys.predict.WordFold

/**
 * The words "Block offensive words" keeps out of suggestions, corrections and learning, from
 * `assets/offensive/<tag>.txt` for the enabled languages: one word per line, `#` comments and
 * blank lines allowed, each folded by [WordFold]. A missing list blocks nothing.
 */
object OffensiveWords {

    private const val DIRECTORY = "offensive"

    /** The folded words of one language's list, or empty when there is none. */
    fun load(assets: AssetManager, tag: String): Set<String> = runCatching {
        assets.open("$DIRECTORY/$tag.txt").use { parse(it.readBytes().decodeToString()) }
    }.getOrElse { emptySet() }

    /** Parses one list, folding each word. */
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
