// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.personal.ActivePacks
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.PersonalLookup
import com.borderkeys.data.personal.SliceRows
import com.borderkeys.data.personal.WordRows
import com.borderkeys.data.personal.WorkingCopy
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit

/**
 * The personal model of the engine at [handle] and the [lookup] it is handed from: the words go
 * from the lookup into the engine's word table, a commit counts its links in the lookup, and the
 * engine is handed the slice of each context a request names. Used on the engine's thread only.
 */
internal class EngineWorkingCopy(private val handle: Long) : WorkingCopy {

    override val lookup = PersonalLookup()

    /** Whether a personal model was loaded ([load]): before, the lookup holds none of the personal dictionary. */
    var loaded = false
        private set

    /** The key of the slice the engine was handed last, and the lookup's version then; none yet. */
    private val pushedKey = IntArray(SliceRows.KEY_SLOTS)
    private var pushedVersion = NONE_PUSHED

    /** The key [contextFor] returns, and the slice it hands, filled again at each call. */
    private val key = IntArray(SliceRows.KEY_SLOTS)
    private val slice = SliceRows()

    /**
     * Replaces the personal model with [model]: the lookup's words, placed by the packs active now,
     * and the word table, then the links.
     */
    fun load(model: PersonalModelLoad) {
        val rows = lookup.loadWords(model.words, model.pairs, model.triples, activePacks())
        val ids = IntArray(rows.size) { WordRows.NEW_WORD }
        val known = BooleanArray(rows.size)
        NativePredictor.nativeLoadWordTable(handle, rows.spellings, rows.fields, ids, known)
        lookup.assigned(rows, ids, known)
        lookup.loadLinks(model.pairs, model.pairContexts, model.triples, model.tripleContexts, model.clocks)
        loaded = true
    }

    /**
     * Learns [commit]: the lookup counts its word and the word table takes every word it changed,
     * then the lookup records the pair and the triple it makes ([PersonalLookup.commitLinks]), the
     * engine judging which of its words are learnable. Returns the link rows it changed. Before the
     * first [load] it learns nothing and returns none: the load reads the word from the personal
     * dictionary.
     */
    fun commit(commit: Commit): LinkWrites {
        if (!loaded) {
            return LinkWrites()
        }
        put(lookup.commit(commit.language, commit.word, commit.previous1, commit.previous2))
        return lookup.commitLinks(
            commit.language, commit.word.word, commit.previous1, commit.previous2, commit.pairAsserted,
            commit.tripleAsserted, commit.word.lastUsedAt, ::learnable,
        )
    }

    /**
     * The key of the context after [previous1] and [previous2] in [language]'s store, as a request
     * names it, the engine handed that context's slice ([PersonalLookup.slice]) unless it holds it
     * already: the same context, and the lookup unchanged since. The key is this working copy's own
     * array, filled again at the next call: a request reads it at once.
     */
    fun contextFor(language: String, previous1: String?, previous2: String?): IntArray {
        lookup.key(language, previous1, previous2, key)
        if (pushedVersion == lookup.version && pushedKey.contentEquals(key)) {
            return key
        }
        lookup.slice(language, previous1, previous2, slice)
        NativePredictor.nativePushSlice(handle, slice.header, slice.links, slice.hops, slice.seen)
        key.copyInto(pushedKey)
        pushedVersion = lookup.version
        return key
    }

    /** The packs open in the engine: the language of each, and which of them spell a word. */
    private fun activePacks(): ActivePacks {
        val languages = Array(LanguagePackRepository.MAX_ENABLED) { slot ->
            NativePredictor.nativePackTag(handle, slot)?.let(LanguageTags::primary)
        }
        return ActivePacks(languages.filterNotNullTo(HashSet())) { words ->
            val slots = IntArray(words.size)
            NativePredictor.nativePacksSpelling(handle, words.toTypedArray(), slots)
            slots.map { spelled ->
                languages.indices.mapNotNullTo(HashSet()) { slot ->
                    languages[slot]?.takeIf { spelled and (1 shl slot) != 0 }
                }
            }
        }
    }

    /** Whether the engine counts each of [words] learnable, in order. */
    private fun learnable(words: List<String>): List<Boolean> {
        val verdicts = BooleanArray(words.size)
        NativePredictor.nativeLearnable(handle, words.toTypedArray(), verdicts)
        return verdicts.toList()
    }

    override fun put(rows: WordRows) {
        if (rows.size == 0) {
            return
        }
        val ids = IntArray(rows.size) { WordRows.NEW_WORD }
        val known = BooleanArray(rows.size)
        NativePredictor.nativePutWords(handle, rows.ids, rows.spellings, rows.fields, ids, known)
        lookup.assigned(rows, ids, known)
    }

    companion object {
        /** The lookup's version before any slice was pushed. */
        private const val NONE_PUSHED = -1L

        /** A key naming no context: a request with it reads no learned pair or triple. */
        fun noContext(): IntArray = IntArray(SliceRows.KEY_SLOTS) { SliceRows.NONE }
    }
}
