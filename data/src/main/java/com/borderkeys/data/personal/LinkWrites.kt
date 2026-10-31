// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.PhraseKey
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext

/**
 * The link rows of the personal model that commits changed, as a flush writes them: each pair,
 * triple and context at its value now, each language's clocks, and each pair and triple given up.
 * A row of one key is in one list at most.
 */
data class LinkWrites(
    val pairs: List<UserBigram> = emptyList(),
    val triples: List<UserTrigram> = emptyList(),
    val pairContexts: List<UserPairContext> = emptyList(),
    val tripleContexts: List<UserTripleContext> = emptyList(),
    val clocks: List<UserPhraseClock> = emptyList(),
    val gonePairs: List<UserBigram> = emptyList(),
    val goneTriples: List<UserTrigram> = emptyList(),
) {
    /** How many links it writes or gives up. */
    val links: Int get() = pairs.size + triples.size + gonePairs.size + goneTriples.size

    val isEmpty: Boolean
        get() = links == 0 && pairContexts.isEmpty() && tripleContexts.isEmpty() && clocks.isEmpty()

    /**
     * These writes with [later]'s after them: each row [later] holds replaces this one's of its
     * key, a link [later] writes is no longer given up, and one it gives up is no longer written.
     */
    fun then(later: LinkWrites): LinkWrites {
        val laterPairs = later.pairs.mapTo(HashSet(), ::pairKey) + later.gonePairs.map(::pairKey)
        val laterTriples = later.triples.mapTo(HashSet(), ::tripleKey) + later.goneTriples.map(::tripleKey)
        val laterPairContexts = later.pairContexts.mapTo(HashSet()) { it.language to it.previousWord }
        val laterTripleContexts =
            later.tripleContexts.mapTo(HashSet()) { Triple(it.language, it.previousWord2, it.previousWord1) }
        val laterClocks = later.clocks.mapTo(HashSet()) { it.language }
        return LinkWrites(
            pairs = pairs.filterNot { pairKey(it) in laterPairs } + later.pairs,
            triples = triples.filterNot { tripleKey(it) in laterTriples } + later.triples,
            pairContexts = pairContexts.filterNot { (it.language to it.previousWord) in laterPairContexts } +
                later.pairContexts,
            tripleContexts = tripleContexts.filterNot {
                Triple(it.language, it.previousWord2, it.previousWord1) in laterTripleContexts
            } + later.tripleContexts,
            clocks = clocks.filterNot { it.language in laterClocks } + later.clocks,
            gonePairs = gonePairs.filterNot { pairKey(it) in laterPairs } + later.gonePairs,
            goneTriples = goneTriples.filterNot { tripleKey(it) in laterTriples } + later.goneTriples,
        )
    }

    /**
     * These writes without the links [word] takes part in, in any case, nor the contexts it is part
     * of or such a link follows; the links given up stay.
     */
    fun without(word: String): LinkWrites {
        val key = PhraseKey.word(word)
        val pairsKept = pairs.filterNot { key == it.previousWord || key == it.word }
        val triplesKept = triples.filterNot { key == it.previousWord2 || key == it.previousWord1 || key == it.word }
        val pairsLeft = (pairs - pairsKept.toSet()).mapTo(HashSet()) { it.language to it.previousWord }
        val triplesLeft =
            (triples - triplesKept.toSet()).mapTo(HashSet()) { Triple(it.language, it.previousWord2, it.previousWord1) }
        return copy(
            pairs = pairsKept,
            triples = triplesKept,
            pairContexts = pairContexts.filterNot {
                key == it.previousWord || (it.language to it.previousWord) in pairsLeft
            },
            tripleContexts = tripleContexts.filterNot {
                key == it.previousWord2 || key == it.previousWord1 ||
                    Triple(it.language, it.previousWord2, it.previousWord1) in triplesLeft
            },
        )
    }

    private fun pairKey(pair: UserBigram) = Triple(pair.language, pair.previousWord, pair.word)

    private fun tripleKey(triple: UserTrigram) =
        listOf(triple.language, triple.previousWord2, triple.previousWord1, triple.word)
}
