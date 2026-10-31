// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.borderkeys.data.entity.UserWord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A database left by each version a build has opened, from the released 9 on, holding what that
 * version wrote, migrated to the current version and validated against its exported schema.
 */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class UpgradeMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BorderKeysDatabase::class.java)

    /** A learned word as the current version reads it back. */
    private data class Word(
        val word: String,
        val language: String,
        val count: Int,
        val lastUsedAt: Long,
        val deliberateCapitals: Int,
        val asserted: Int,
        val added: Int,
    )

    @Test
    fun `version 9, the released one, keeps every word unplaced with its uses, and no choice but an add by hand`() {
        upgrade(9) { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted) VALUES " +
                    "('Nadia', 'en-US', 5, 1000, 2, 3), ('met', 'en-US', 4, 1100, 0, 1), " +
                    "('salut', 'ro-RO', 2, 2000, 0, 1), ('acasă', 'und', 1, 3000, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO user_bigrams (previousWord, word, count, lastUsedAt) VALUES " +
                    "('I', 'met', 2, 1500), ('met', 'Nadia', 3, 1500), ('vreau', 'salut', 4, 1600)",
            )
            db.execSQL(
                "INSERT INTO user_trigrams (previousWord2, previousWord1, word, count, lastUsedAt) " +
                    "VALUES ('I', 'met', 'Nadia', 1, 1500)",
            )
        }.use { db ->
            assertEquals(
                listOf(
                    Word("Nadia", UNPLACED, 5, 1000, 2, 0, 0),
                    Word("acasă", UNPLACED, 1, 3000, 0, 0, 0),
                    Word("met", UNPLACED, 4, 1100, 0, 0, 0),
                    Word("salut", UNPLACED, 2, 2000, 0, 0, 0),
                ),
                words(db),
            )
            assertEquals(
                listOf("en i met 2 1500", "en met nadia 3 1500", "ro vreau salut 4 1600"),
                rows(db, "SELECT language, previousWord, word, count, lastUsedAt FROM user_bigrams ORDER BY language, previousWord"),
            )
            assertEquals(listOf("en i met nadia 1"), rows(db, "SELECT language, previousWord2, previousWord1, word, count FROM user_trigrams"))
            assertReleasedRowsKept(db)
            assertNewTablesEmpty(db)
        }
    }

    @Test
    fun `version 10 keeps its words unplaced and its links in their languages`() {
        upgrade(10) { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted) VALUES " +
                    "('Nadia', 'en-US', 5, 1000, 2, 3), ('salut', 'ro-RO', 2, 2000, 0, 1)",
            )
            db.execSQL("INSERT INTO user_bigrams (previousWord, word, count, lastUsedAt) VALUES ('hi', 'Nadia', 3, 1500)")
        }.use { db ->
            assertEquals(
                listOf(Word("Nadia", UNPLACED, 5, 1000, 2, 0, 0), Word("salut", UNPLACED, 2, 2000, 0, 0, 0)),
                words(db),
            )
            assertEquals(listOf("en hi nadia 3 1500"), rows(db, "SELECT language, previousWord, word, count, lastUsedAt FROM user_bigrams"))
            assertReleasedRowsKept(db)
            assertNewTablesEmpty(db)
        }
    }

    @Test
    fun `version 11 keeps its links, their contexts and clocks as they were, its words unplaced`() {
        upgrade(11) { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted) VALUES " +
                    "('Nadia', 'en-US', 5, 1000, 2, 3)",
            )
            links(db, marks = false)
        }.use { db ->
            assertEquals(listOf(Word("Nadia", UNPLACED, 5, 1000, 2, 0, 0)), words(db))
            assertLinksKept(db, marks = false)
            assertReleasedRowsKept(db)
            assertNewTablesEmpty(db)
        }
    }

    @Test
    fun `version 12 keeps a word added by hand as chosen, and no other choice`() {
        upgrade(12) { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted, added) VALUES " +
                    "('Nadia', 'en-US', 5, 1000, 2, 3, 0), ('Kelpa', 'en-US', 2, 1200, 1, 2, 1)",
            )
            links(db, marks = false)
        }.use { db ->
            assertEquals(
                listOf(Word("Kelpa", UNPLACED, 2, 1200, 1, 1, 1), Word("Nadia", UNPLACED, 5, 1000, 2, 0, 0)),
                words(db),
            )
            assertLinksKept(db, marks = false)
            assertReleasedRowsKept(db)
            assertNewTablesEmpty(db)
        }
    }

    @Test
    fun `version 13 leaves every word unplaced, a primary tag and an unknown one too, and keeps its links' marks`() {
        upgrade(13) { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted, added) VALUES " +
                    "('Nadia', 'en-US', 5, 1000, 2, 3, 0), ('Kelpa', 'en', 2, 1200, 1, 2, 1), " +
                    "('masă', 'ro', 3, 1300, 0, 0, 0), ('acasă', 'und', 1, 3000, 0, 1, 0)",
            )
            links(db, marks = true)
        }.use { db ->
            assertEquals(
                listOf(
                    Word("Kelpa", UNPLACED, 2, 1200, 1, 1, 1),
                    Word("Nadia", UNPLACED, 5, 1000, 2, 0, 0),
                    Word("acasă", UNPLACED, 1, 3000, 0, 0, 0),
                    Word("masă", UNPLACED, 3, 1300, 0, 0, 0),
                ),
                words(db),
            )
            assertLinksKept(db, marks = true)
            assertReleasedRowsKept(db)
            assertNewTablesEmpty(db)
        }
    }

    /**
     * Creates the database at [version] from its exported schema, writes [rows] and [releasedRows]
     * into it, and migrates it to the current version through every migration, validated against
     * its schema.
     */
    private fun upgrade(version: Int, rows: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        helper.createDatabase(NAME, version).use { db ->
            rows(db)
            releasedRows(db, version)
        }
        return helper.runMigrationsAndValidate(NAME, CURRENT, true, *MIGRATIONS)
    }

    /** A clip, a blocked word and a key's taps, as every version from 9 on writes them, the clip's screenshot mark from 10 on. */
    private fun releasedRows(db: SupportSQLiteDatabase, version: Int) {
        val (markColumn, markValue) = if (version >= SCREENSHOT_MARK) ", fromScreenshot" to ", 0" else "" to ""
        db.execSQL(
            "INSERT INTO clip_entries (content, createdAt, pinnedAt, contentHash, uri, mimeType, mediaFile, " +
                "sizeBytes, thumbnail, isPrivate, sourcePackage$markColumn) " +
                "VALUES ('hello', 1000, NULL, 42, NULL, NULL, NULL, 0, NULL, 0, NULL$markValue)",
        )
        db.execSQL("INSERT INTO blocked_words (word) VALUES ('spam')")
        db.execSQL(
            "INSERT INTO key_touches (bucket, code, taps, sumX, sumY, sumXX, sumYY, sumXY, keyWidthPx, keyHeightPx, " +
                "density, lastUsedAt) VALUES ('portrait', 97, 3.5, 1.0, 2.0, 3.0, 4.0, 5.0, 100.0, 120.0, 2.0, 1000)",
        )
    }

    private fun assertReleasedRowsKept(db: SupportSQLiteDatabase) {
        assertEquals(listOf("hello"), rows(db, "SELECT content FROM clip_entries"))
        assertEquals(listOf("spam"), rows(db, "SELECT word FROM blocked_words"))
        assertEquals(listOf("portrait 97 3.5"), rows(db, "SELECT bucket, code, taps FROM key_touches"))
    }

    /** A pair and a triple in Romanian with their contexts and clocks, as version 11 on keeps them, [marks] from 13 on. */
    private fun links(db: SupportSQLiteDatabase, marks: Boolean) {
        val markColumns = if (marks) ", asserted, added" else ""
        val markValues = if (marks) ", 1, 1" else ""
        db.execSQL(
            "INSERT INTO user_bigrams (language, previousWord, word, count, lastUsedAt, contextSeen, languageSeen$markColumns) " +
                "VALUES ('ro', 'o', 'cana', 4, 1500, 9, 11$markValues)",
        )
        db.execSQL(
            "INSERT INTO user_trigrams (language, previousWord2, previousWord1, word, count, lastUsedAt, contextSeen, " +
                "languageSeen$markColumns) VALUES ('ro', 'vreau', 'o', 'cana', 2, 1500, 5, 3$markValues)",
        )
        db.execSQL("INSERT INTO user_pair_contexts (language, previousWord, total, lastUsedAt, seen) VALUES ('ro', 'o', 6, 1500, 9)")
        db.execSQL(
            "INSERT INTO user_triple_contexts (language, previousWord2, previousWord1, total, lastUsedAt, seen) " +
                "VALUES ('ro', 'vreau', 'o', 2, 1500, 5)",
        )
        db.execSQL("INSERT INTO user_phrase_clocks (language, pairs, triples) VALUES ('ro', 11, 3)")
    }

    private fun assertLinksKept(db: SupportSQLiteDatabase, marks: Boolean) {
        val mark = if (marks) 1 else 0
        assertEquals(
            listOf("ro o cana 4 1500 9 11 $mark $mark"),
            rows(db, "SELECT language, previousWord, word, count, lastUsedAt, contextSeen, languageSeen, asserted, added FROM user_bigrams"),
        )
        assertEquals(
            listOf("ro vreau o cana 2 1500 5 3 $mark $mark"),
            rows(
                db,
                "SELECT language, previousWord2, previousWord1, word, count, lastUsedAt, contextSeen, languageSeen, asserted, " +
                    "added FROM user_trigrams",
            ),
        )
        assertEquals(listOf("ro o 6 1500 9"), rows(db, "SELECT language, previousWord, total, lastUsedAt, seen FROM user_pair_contexts"))
        assertEquals(
            listOf("ro vreau o 2 1500 5"),
            rows(db, "SELECT language, previousWord2, previousWord1, total, lastUsedAt, seen FROM user_triple_contexts"),
        )
        assertEquals(listOf("ro 11 3"), rows(db, "SELECT language, pairs, triples FROM user_phrase_clocks"))
    }

    /** The learned corrections and the clean-up's stamp, both new in version 14, hold nothing yet. */
    private fun assertNewTablesEmpty(db: SupportSQLiteDatabase) {
        assertEquals(emptyList<String>(), rows(db, "SELECT * FROM user_corrections"))
        assertEquals(emptyList<String>(), rows(db, "SELECT * FROM clean_up_stamps"))
    }

    /** Every learned word, by its spelling. */
    private fun words(db: SupportSQLiteDatabase): List<Word> = buildList {
        db.query(
            "SELECT word, language, count, lastUsedAt, deliberateCapitals, asserted, added FROM user_words ORDER BY word",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    Word(
                        cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getLong(3), cursor.getInt(4),
                        cursor.getInt(5), cursor.getInt(6),
                    ),
                )
            }
        }
    }

    /** Each row [query] reads, its columns joined by spaces. */
    private fun rows(db: SupportSQLiteDatabase, query: String): List<String> = buildList {
        db.query(query).use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).joinToString(" ") { cursor.getString(it) })
            }
        }
    }

    private companion object {
        const val NAME = "upgrade-test"
        const val CURRENT = 14

        /** The first version whose clips carry the screenshot mark. */
        const val SCREENSHOT_MARK = 10
        const val UNPLACED = UserWord.UNPLACED

        /** The language a migrated link takes when no word's locale names one. */
        const val FALLBACK = "de"

        /** Every migration from the released version 9 on. */
        val MIGRATIONS: Array<Migration> = arrayOf(
            BorderKeysDatabase.MIGRATION_9_10,
            BorderKeysDatabase.migration10To11 { FALLBACK },
            BorderKeysDatabase.MIGRATION_11_12,
            BorderKeysDatabase.MIGRATION_12_13,
            BorderKeysDatabase.MIGRATION_13_14,
        )
    }
}
