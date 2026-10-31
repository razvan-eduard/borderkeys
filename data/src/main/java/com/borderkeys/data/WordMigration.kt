// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.borderkeys.data.entity.UserWord

/**
 * The learned words of a version 13 database as version 14 keeps them
 * ([BorderKeysDatabase.MIGRATION_13_14]): each row [upgraded], the words table rebuilt keyed by
 * the word and its language, its index the load's order, and the learned corrections' table and
 * the clean-up's stamp created empty.
 */
internal object WordMigration {

    /**
     * A word learned under the rules before version 14, as version 14 keeps it: [UserWord.UNPLACED],
     * since its tag was the last subtype used, not the language it was counted in, and chosen only
     * as often as it was added by hand, since every pick then counted as a choice. Its uses, last
     * use, deliberate capitals and adds by hand stay.
     */
    fun upgraded(word: String, count: Int, lastUsedAt: Long, deliberateCapitals: Int, added: Int): UserWord =
        UserWord(word, UserWord.UNPLACED, count, lastUsedAt, deliberateCapitals, asserted = added, added = added)

    /** Rewrites the words of a version 13 [db] as version 14 keeps them, and creates its new tables. */
    fun migrate(db: SupportSQLiteDatabase) {
        val words = ArrayList<UserWord>()
        db.query("SELECT `word`, `count`, `lastUsedAt`, `deliberateCapitals`, `added` FROM `user_words`").use { rows ->
            while (rows.moveToNext()) {
                words += upgraded(rows.getString(0), rows.getInt(1), rows.getLong(2), rows.getInt(3), rows.getInt(4))
            }
        }
        db.execSQL("DROP TABLE `user_words`")
        CREATE_STATEMENTS.forEach(db::execSQL)
        for (word in words) {
            val values = ContentValues().apply {
                put("word", word.word)
                put("language", word.language)
                put("count", word.count)
                put("lastUsedAt", word.lastUsedAt)
                put("deliberateCapitals", word.deliberateCapitals)
                put("asserted", word.asserted)
                put("added", word.added)
            }
            db.insert("user_words", SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }

    /** Version 14's words, learned corrections and clean-up stamp tables, as Room creates them. */
    private val CREATE_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `user_words` (`word` TEXT NOT NULL, `language` TEXT NOT NULL, " +
            "`count` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `deliberateCapitals` INTEGER NOT NULL, " +
            "`asserted` INTEGER NOT NULL, `added` INTEGER NOT NULL, PRIMARY KEY(`word`, `language`))",
        "CREATE INDEX IF NOT EXISTS `index_user_words_added_count_lastUsedAt` ON `user_words` " +
            "(`added` ASC, `count` DESC, `lastUsedAt` DESC)",
        "CREATE TABLE IF NOT EXISTS `user_corrections` (`language` TEXT NOT NULL, `key` TEXT NOT NULL, " +
            "`previousWord` TEXT NOT NULL, `word` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
            "`lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`language`, `key`, `previousWord`, `word`))",
        "CREATE INDEX IF NOT EXISTS `index_user_corrections_word` ON `user_corrections` (`word`)",
        "CREATE INDEX IF NOT EXISTS `index_user_corrections_previousWord` ON `user_corrections` (`previousWord`)",
        "CREATE TABLE IF NOT EXISTS `clean_up_stamps` (`id` INTEGER NOT NULL, `ranAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
    )
}
