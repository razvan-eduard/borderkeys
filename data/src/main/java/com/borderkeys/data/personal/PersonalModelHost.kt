// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

/**
 * The personal model a running keyboard holds, the lookup and the engine's working copy, as the
 * personal dictionary's edits reach it. Each call runs after every load and edit asked for before
 * it, and after every commit the keyboard handed its engine before it.
 */
interface PersonalModelHost {

    /**
     * Runs [write] on the personal dictionary, then, while the keyboard holds a personal model
     * loaded, [change] on it with what [write] returned: the engine's word table takes the rows
     * [change] puts. The learning waiting drops each of [forgotten], in any case and language, with
     * its pairs and triples, and the link rows [change] returns are written with what is left, over
     * what [write] wrote. Returns what [write] returned.
     */
    suspend fun <T> update(
        write: suspend () -> T,
        forgotten: Collection<String>,
        change: (WorkingCopy, T) -> LinkWrites,
    ): T

    /**
     * Runs [edit] once what waits to be written is written and every link row still to come is kept
     * out, then reads the personal model again from the personal dictionary, the blocked words with
     * it. Returns what [edit] returned.
     */
    suspend fun <T> reload(edit: suspend () -> T): T

    /** The blocked words changed: the engine and the learning take them again. */
    suspend fun blockedChanged()

    /**
     * Runs [write], which forgot the heatmap's stored totals, after the taps waiting to be written
     * are dropped; the totals are read again.
     */
    suspend fun forgetTouches(write: suspend () -> Unit)
}

/** The engine's working copy as an edit changes it: the [lookup], and the word table [put] hands rows to. */
interface WorkingCopy {
    val lookup: PersonalLookup

    /** Hands the engine's word table [rows], and the lookup the ids it gave them. */
    fun put(rows: WordRows)
}
