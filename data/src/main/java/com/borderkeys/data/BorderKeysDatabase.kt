// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.borderkeys.data.dao.AssistModelDao
import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.ClipboardDao
import com.borderkeys.data.dao.LanguagePackDao
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.AssistModelEntry
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
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
        AssistModelEntry::class,
        UserBigram::class,
        UserTrigram::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class BorderKeysDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao
    abstract fun userWordDao(): UserWordDao
    abstract fun blockedWordDao(): BlockedWordDao
    abstract fun languagePackDao(): LanguagePackDao
    abstract fun assistModelDao(): AssistModelDao
    abstract fun userBigramDao(): UserBigramDao
    abstract fun userTrigramDao(): UserTrigramDao

    companion object {
        private const val DATABASE_NAME = "borderkeys.db"

        /**
         * Version 1 to 2: the text assistant's model table.
         *
         * Written rather than destroyed-and-recreated, and the reason is not that anything has
         * shipped yet. This database holds the personal dictionary and the clipboard history --
         * things the user cannot get back -- so "recreate on a schema change" is a policy that
         * would eventually delete them, on some future version, on somebody's phone. The habit
         * of writing the migration is the point.
         *
         * The failure this fixes was found by installing: adding an entity without moving the
         * version made Room refuse to open the database at all, and the input method died on
         * start with it. A keyboard that cannot start is one the user cannot replace without
         * already having another keyboard installed.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `assist_models` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `license` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `contextTokens` INTEGER NOT NULL,
                        `importedAt` INTEGER NOT NULL,
                        `active` INTEGER NOT NULL,
                        `integrityFailedAt` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_assist_models_fileName` " +
                        "ON `assist_models` (`fileName`)",
                )
            }
        }

        /**
         * Adds the table of word pairs, for predicting the next word from what this person
         * actually writes rather than from what a corpus says.
         *
         * Purely additive, like the one before it: nothing existing is read, rewritten or
         * dropped, so an install that fails here is an install that was already broken.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_bigrams` (
                        `previousWord` TEXT NOT NULL,
                        `word` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`previousWord`, `word`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_bigrams_count` " +
                        "ON `user_bigrams` (`count` DESC)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_bigrams_word` " +
                        "ON `user_bigrams` (`word`)",
                )
            }
        }

        /**
         * Adds the table of three-word sequences, for predicting from two words of context
         * rather than one. Additive like the two before it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_trigrams` (
                        `previousWord2` TEXT NOT NULL,
                        `previousWord1` TEXT NOT NULL,
                        `word` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`previousWord2`, `previousWord1`, `word`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_trigrams_count` " +
                        "ON `user_trigrams` (`count` DESC)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_trigrams_word` " +
                        "ON `user_trigrams` (`word`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_trigrams_previousWord1` " +
                        "ON `user_trigrams` (`previousWord1`)",
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // The settings screen and the IME run in the same process, but the text
                // assistant runs in ":assist" and opens this database too. Without this, a write
                // in one process leaves the other's Flows showing stale rows indefinitely.
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}
