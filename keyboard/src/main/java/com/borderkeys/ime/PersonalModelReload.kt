// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * One reload of the engine's personal model from the rows: [settle] waits until every commit the
 * engine was handed has reported its rows, [stale] writes what waits and keeps the link rows still
 * to come from being written, [flush] writes the learning not yet saved and waits for the write,
 * [load] replaces the engine's model with the rows, and [fresh] lets link rows be written again.
 */
internal class PersonalModelReload(
    private val settle: suspend () -> Unit,
    private val flush: suspend () -> Unit,
    private val load: suspend () -> Unit,
    private val stale: suspend () -> Unit = {},
    private val fresh: suspend () -> Unit = {},
) {
    /**
     * Settles, marks the links stale, runs [between], flushes and loads, then settles again, so
     * every commit handed before the load has reported, and marks them fresh.
     */
    suspend fun reload(between: suspend () -> Unit = {}) {
        settle()
        stale()
        between()
        flush()
        load()
        settle()
        fresh()
    }
}
