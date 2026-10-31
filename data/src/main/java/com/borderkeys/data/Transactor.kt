// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.withTransaction

/** Runs a block of reads and writes on the personal tables as one transaction. */
interface Transactor {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** [Transactor] on [database]'s own transactions. */
internal class RoomTransactor(private val database: BorderKeysDatabase) : Transactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}
