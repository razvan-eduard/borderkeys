// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.room.Room
import java.io.Closeable

/** A [DictionaryRepository] on a database of its own in memory, for the tests run under Robolectric. */
class InMemoryDictionary(context: Context) : Closeable {

    internal val database = Room.inMemoryDatabaseBuilder(context, BorderKeysDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val repository = DictionaryRepository(database)

    override fun close() = database.close()
}
