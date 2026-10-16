// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * One reload of the engine's personal model from the rows: [settle] waits until every learn the
 * engine was handed has reported its steps, [flush] writes the learning not yet saved and waits
 * for the write, [load] replaces the engine's model with the rows.
 */
internal class PersonalModelReload(
    private val settle: suspend () -> Unit,
    private val flush: suspend () -> Unit,
    private val load: suspend () -> Unit,
) {
    /** Runs the three in order, with [between] after the settle and before the flush. */
    suspend fun reload(between: suspend () -> Unit = {}) {
        settle()
        between()
        flush()
        load()
    }
}
