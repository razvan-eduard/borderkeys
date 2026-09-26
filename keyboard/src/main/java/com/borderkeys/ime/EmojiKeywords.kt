// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager

/**
 * The keywords the emoji panel searches beside the names: Unicode's CLDR annotations, compiled
 * by tools/build_emoji.py into one file per language under `emoji/keywords/`.
 *
 * Loaded for the languages that are switched on, and for English always, since the names the
 * panel already searches are English too. A language with no file contributes nothing.
 */
internal object EmojiKeywords {

    private const val DIRECTORY = "emoji/keywords"

    /** A language tag's language: "ro-RO" gives "ro". */
    fun languageOf(tag: String): String = tag.substringBefore('-').lowercase()

    /** The keyword files to read for [tags], each language once, English last. */
    fun languagesFor(tags: List<String>): List<String> {
        val languages = ArrayList<String>()
        for (tag in tags) {
            val language = languageOf(tag)
            if (language.isNotEmpty() && language !in languages) {
                languages += language
            }
        }
        if ("en" !in languages) {
            languages += "en"
        }
        return languages
    }

    fun load(assets: AssetManager, tags: List<String>): Map<String, List<String>> {
        val merged = HashMap<String, MutableList<String>>()
        for (language in languagesFor(tags)) {
            val parsed = runCatching {
                assets.open("$DIRECTORY/$language.txt").bufferedReader().useLines { lines ->
                    EmojiSearch.parseKeywords(lines)
                }
            }.getOrDefault(emptyMap())
            for ((emoji, keywords) in parsed) {
                val list = merged.getOrPut(emoji) { ArrayList() }
                for (keyword in keywords) {
                    if (keyword !in list) {
                        list += keyword
                    }
                }
            }
        }
        return merged
    }
}
