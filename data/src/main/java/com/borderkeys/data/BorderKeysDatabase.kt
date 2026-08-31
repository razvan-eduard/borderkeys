// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.ClipboardDao
import com.borderkeys.data.dao.LanguagePackDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.entity.UserWord
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.Arrays

/**
 * Everything this keyboard remembers, in one encrypted file.
 *
 * `exportSchema = true` and the schemas are committed, so a migration arrives as a reviewable
 * diff rather than as a surprise on somebody's device.
 */
@Database(
    entities = [
        ClipEntry::class,
        UserWord::class,
        BlockedWord::class,
        LanguagePackEntry::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BorderKeysDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao
    abstract fun userWordDao(): UserWordDao
    abstract fun blockedWordDao(): BlockedWordDao
    abstract fun languagePackDao(): LanguagePackDao

    companion object {
        private const val DATABASE_NAME = "borderkeys.db"

        fun open(context: Context): BorderKeysDatabase {
            // sqlcipher-android 4.x has no static initialiser that does this: nothing in the
            // library loads its own .so, so the first call into it would fail with an
            // UnsatisfiedLinkError from inside Room's open path.
            System.loadLibrary("sqlcipher")

            val passphrase = DatabasePassphrase.obtain(context)
            val factory = SupportOpenHelperFactory(passphrase)
            // SQLCipher keeps its own copy, so this one is scrubbed rather than left in the heap
            // waiting for the garbage collector to get around to it.
            Arrays.fill(passphrase, 0)

            return Room.databaseBuilder(
                context.applicationContext,
                BorderKeysDatabase::class.java,
                DATABASE_NAME,
            )
                .openHelperFactory(factory)
                // The settings screen and the IME run in the same process, but the text
                // assistant runs in ":assist" and opens this database too. Without this, a write
                // in one process leaves the other's Flows showing stale rows indefinitely.
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}
