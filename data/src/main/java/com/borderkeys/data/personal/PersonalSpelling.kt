// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

/**
 * How a word's case forms, the rows the personal dictionary keeps for its spellings in each case,
 * make one entry: what they count together, and the spelling served for them.
 */
object PersonalSpelling {

    /** One case form of a word: its spelling and what its rows, one per language, count. */
    data class Form(
        val spelling: String,
        val count: Int,
        val lastUsedAt: Long,
        val deliberateCapitals: Int = 0,
        val asserted: Int = 0,
        val added: Int = 0,
    ) {
        /** This form and [other], another row of its spelling, as one: their counts summed and the later last use. */
        fun plus(other: Form) = copy(
            count = count + other.count,
            lastUsedAt = maxOf(lastUsedAt, other.lastUsedAt),
            deliberateCapitals = deliberateCapitals + other.deliberateCapitals,
            asserted = asserted + other.asserted,
            added = added + other.added,
        )
    }

    /** A word's case forms as one entry, under its [key]: their counts summed, and one spelling. */
    data class Merged(
        val key: String,
        val spelling: String,
        val count: Int,
        val deliberateCapitals: Int,
        val asserted: Int,
        val added: Int,
    )

    /** The forms a spelling is served from first: added by hand, then the most used, then the last used. */
    private val served = compareByDescending<Form> { it.added > 0 }
        .thenByDescending { it.count }
        .thenByDescending { it.lastUsedAt }

    /**
     * [forms], the case forms of the word keyed [key], as one entry: every count summed across
     * them, the spelling of the first in [served]'s order, and among equals the first given.
     */
    fun merge(key: String, forms: List<Form>): Merged {
        require(forms.isNotEmpty()) { "a word has at least one form" }
        return Merged(
            key = key,
            spelling = forms.sortedWith(served).first().spelling,
            count = forms.sumOf { it.count },
            deliberateCapitals = forms.sumOf { it.deliberateCapitals },
            asserted = forms.sumOf { it.asserted },
            added = forms.sumOf { it.added },
        )
    }
}
