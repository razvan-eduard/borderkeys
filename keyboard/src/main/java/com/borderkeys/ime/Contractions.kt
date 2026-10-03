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

    /** One language's pairs, from `assets/contractions/<tag>.pairs.txt`; none when missing. */
    fun loadPairs(assets: AssetManager, tag: String): List<PairEntry> = runCatching {
        assets.open("$DIRECTORY/$tag$PAIRS_SUFFIX").use { parsePairs(it.readBytes().decodeToString()) }
    }.getOrElse { emptyList() }

    /** `bare<TAB>written<TAB>bare count<TAB>written count`. */
    fun parsePairs(text: String): List<PairEntry> {
        val entries = ArrayList<PairEntry>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue
            }
            val fields = line.split('\t')
            if (fields.size < 4 || fields[0].isEmpty() || fields[1].isEmpty()) {
                continue
            }
            val bareCount = fields[2].toLongOrNull() ?: continue
            val writtenCount = fields[3].toLongOrNull() ?: continue
            entries.add(PairEntry(fields[0], fields[1], bareCount, writtenCount))
        }
        return entries
    }

    /**
     * The twin of each bare spelling for the languages [perLanguage] names, the first language
     * that pairs a spelling keeping it. A twin goes ahead of its bare spelling only in English,
     * only when it is the commoner of the two, and never when it ends in `'s` or `s'`.
     */
    fun twinsOf(perLanguage: List<Pair<String, List<PairEntry>>>): Map<String, Twin> {
        val twins = HashMap<String, Twin>()
        for ((tag, entries) in perLanguage) {
            val english = tag.startsWith("en")
            for (entry in entries) {
                if (entry.bare in twins) {
                    continue
                }
                val lower = entry.written.lowercase()
                val possessive = lower.endsWith("'s") || lower.endsWith("s'")
                twins[entry.bare] = Twin(
                    entry.written,
                    ahead = english && !possessive && entry.writtenCount > entry.bareCount,
                )
            }
        }
        return twins
    }

    /** The twin of [word], case aside, or null. */
    fun twinOf(word: String, twins: Map<String, Twin>): Twin? =
        if (twins.isEmpty() || word.isEmpty()) null else twins[word] ?: twins[word.lowercase()]

    /** One pair line: a bare spelling that is a word, its apostrophe twin, and both counts. */
    data class PairEntry(val bare: String, val written: String, val bareCount: Long, val writtenCount: Long)

    /** The apostrophe spelling offered beside a bare one, and whether it goes first. */
    data class Twin(val written: String, val ahead: Boolean)

    private const val PAIRS_SUFFIX = ".pairs.txt"
}
