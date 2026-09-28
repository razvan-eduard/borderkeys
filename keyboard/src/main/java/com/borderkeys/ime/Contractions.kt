// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager

/**
 * The apostrophe a word was written without, put back when the word is committed, from the lists
 * `tools/make_contractions.py` writes to `assets/contractions/<tag>.txt`. Each entry names the
 * languages that hold its bare spelling as a word. A missing or malformed list restores nothing.
 */
object Contractions {

    private const val DIRECTORY = "contractions"

    /** What a committed word should be written as, or null to leave it alone. */
    fun expansionFor(typed: String, table: Map<String, String>): String? {
        if (typed.isEmpty() || table.isEmpty()) {
            return null
        }
        val written = table[typed] ?: table[typed.lowercase()] ?: return null
        // A capital the user typed is kept.
        return if (typed[0].isUpperCase()) {
            written.replaceFirstChar { it.uppercaseChar() }
        } else {
            written
        }
    }

    /** One language's list, still carrying each entry's objectors. */
    fun load(assets: AssetManager, tag: String): List<Entry> = runCatching {
        assets.open("$DIRECTORY/$tag.txt").use { parse(it.readBytes().decodeToString()) }
    }.getOrElse { emptyList() }

    /** `typed<TAB>written<TAB>tag,tag`. Pure, so the format is tested on the JVM. */
    fun parse(text: String): List<Entry> {
        val entries = ArrayList<Entry>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            val fields = line.split('\t')
            if (fields.size < 2 || fields[0].isEmpty() || fields[1].isEmpty()) {
                continue
            }
            val objectors = if (fields.size < 3 || fields[2].isEmpty()) {
                emptyList()
            } else {
                fields[2].split(',').filter { it.isNotEmpty() }
            }
            entries.add(Entry(fields[0], fields[1], objectors))
        }
        return entries
    }

    /** The table for the [enabled] languages, without the entries any of them objects to. */
    fun of(perLanguage: List<List<Entry>>, enabled: Collection<String>): Map<String, String> {
        val table = HashMap<String, String>()
        for (entries in perLanguage) {
            for (entry in entries) {
                if (entry.objectors.any { it in enabled }) {
                    continue
                }
                table[entry.typed] = entry.written
            }
        }
        return table
    }

    /** One line: what was typed, what it should read, and who would object. */
    data class Entry(val typed: String, val written: String, val objectors: List<String>)
}
