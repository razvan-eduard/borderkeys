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

/** Everything this keyboard remembers, in one encrypted file. The schemas are exported. */
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
    version = 7,
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

        /** Version 1 to 2: the text assistant's model table. */
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

        /** Version 2 to 3: the table of word pairs. Additive. */
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

        /** Version 3 to 4: the table of three-word sequences. Additive. */
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

        /** Version 4 to 5: nullable URI and MIME type columns on clipboard entries, for images. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clip_entries` ADD COLUMN `uri` TEXT")
                db.execSQL("ALTER TABLE `clip_entries` ADD COLUMN `mimeType` TEXT")
            }
        }

        /**
         * Version 5 to 6: [com.borderkeys.data.entity.UserWord.deliberateCapitals]. Additive;
         * existing rows start at zero.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_words` ADD COLUMN `deliberateCapitals` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Version 6 to 7: how many times a learned word was chosen on purpose -- see
         * [com.borderkeys.data.entity.UserWord.asserted]. Additive; existing rows start at zero.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `user_words` ADD COLUMN `asserted` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun open(context: Context): BorderKeysDatabase {
            // sqlcipher-android 4.x does not load its own library.
            System.loadLibrary("sqlcipher")

            val passphrase = DatabasePassphrase.obtain(context)
            val factory = SupportOpenHelperFactory(passphrase)
            // SQLCipher keeps its own copy; this one is scrubbed.
            Arrays.fill(passphrase, 0)

            return Room.databaseBuilder(
                context.applicationContext,
                BorderKeysDatabase::class.java,
                DATABASE_NAME,
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                // The ":assist" process opens this database too.
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}
