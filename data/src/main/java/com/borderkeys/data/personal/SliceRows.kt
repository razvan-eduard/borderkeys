// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

/**
 * The slice of one context as the engine is handed it: [header], [links], [hops] and [seen] in the
 * bridge's slots, and the [key] a request names that context by. The arrays are filled again for
 * each slice ([PersonalLookup.slice]), each grown only when the slice needs more of it: the
 * header's counts say how much of each is the slice's.
 */
class SliceRows {
    /** [HEADER_FIELDS] ints, at [LANGUAGE], [CONTEXT1], [CONTEXT2], [PAIR_TOTAL], [TRIPLE_TOTAL], [PAIRS], [TRIPLES] and [HOPS]. */
    val header = IntArray(HEADER_FIELDS)

    /** [LINK_FIELDS] ints per pair, then per triple, at [LINK_WORD], [LINK_COUNT] and [LINK_ASSERTED]. */
    var links = IntArray(0)
        private set

    /** [HOP_FIELDS] ints per hop, at [HOP_WORD], [HOP_COUNT], [HOP_ASSERTED] and [HOP_MIDDLE_TOTAL]. */
    var hops = IntArray(0)
        private set

    /** [CONTEXT_SEEN_FIELDS] longs, at [PAIR_SEEN] and [TRIPLE_SEEN], then one per link, in the links' order. */
    var seen = LongArray(CONTEXT_SEEN_FIELDS)
        private set

    /** The header's first [KEY_SLOTS] ints: the language and the two words before, as a request names them. */
    val key: IntArray get() = header.copyOf(KEY_SLOTS)

    /** Makes room for [linkCount] links and [hopCount] hops, each array kept while it holds enough. */
    internal fun room(linkCount: Int, hopCount: Int) {
        if (links.size < linkCount * LINK_FIELDS) {
            links = IntArray(linkCount * LINK_FIELDS)
        }
        if (hops.size < hopCount * HOP_FIELDS) {
            hops = IntArray(hopCount * HOP_FIELDS)
        }
        if (seen.size < CONTEXT_SEEN_FIELDS + linkCount) {
            seen = LongArray(CONTEXT_SEEN_FIELDS + linkCount)
        }
    }

    companion object {
        /** The header's slots, and how many it holds: the bridge's kSlice constants. */
        const val LANGUAGE = 0
        const val CONTEXT1 = 1
        const val CONTEXT2 = 2
        const val PAIR_TOTAL = 3
        const val TRIPLE_TOTAL = 4
        const val PAIRS = 5
        const val TRIPLES = 6
        const val HOPS = 7
        const val HEADER_FIELDS = 8

        /** A link's slots, and how many it holds. */
        const val LINK_WORD = 0
        const val LINK_COUNT = 1
        const val LINK_ASSERTED = 2
        const val LINK_FIELDS = 3

        /** A hop's slots, and how many it holds. */
        const val HOP_WORD = 0
        const val HOP_COUNT = 1
        const val HOP_ASSERTED = 2
        const val HOP_MIDDLE_TOTAL = 3
        const val HOP_FIELDS = 4

        /** The stamps' slots before the links' own. */
        const val PAIR_SEEN = 0
        const val TRIPLE_SEEN = 1
        const val CONTEXT_SEEN_FIELDS = 2

        /** The most hops a slice holds: one for each of the first pairs. */
        const val MAX_HOPS = 3

        /** How many ints a request's key holds. */
        const val KEY_SLOTS = 3

        /** The id naming no language, no context word and no word. */
        const val NONE = -1
    }
}
