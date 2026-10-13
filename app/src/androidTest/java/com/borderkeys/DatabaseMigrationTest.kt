// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.borderkeys.data.BorderKeysDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each migration against the exported schemas, from a database built at the version before. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BorderKeysDatabase::class.java,
    )

    @Test
    fun version7Gains8sKeyTouchesAndKeepsItsWords() {
        helper.createDatabase(DATABASE, 7).use { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, " +
                    "asserted) VALUES ('keyboard', 'en-US', 3, 1000, 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(DATABASE, 8, true, BorderKeysDatabase.MIGRATION_7_8)
            .use { db ->
                db.query("SELECT count, asserted FROM user_words WHERE word = 'keyboard'").use {
                    it.moveToFirst()
                    assertEquals(3, it.getInt(0))
                    assertEquals(1, it.getInt(1))
                }
                db.query("SELECT COUNT(*) FROM key_touches").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
    }

    @Test
    fun version8Gains9sImageColumnsAndKeepsItsClips() {
        helper.createDatabase(DATABASE, 8).use { db ->
            db.execSQL(
                "INSERT INTO clip_entries (content, createdAt, pinnedAt, contentHash, uri, mimeType) " +
                    "VALUES ('hello', 1000, NULL, 42, NULL, NULL)",
            )
        }
        helper.runMigrationsAndValidate(DATABASE, 9, true, BorderKeysDatabase.MIGRATION_8_9)
            .use { db ->
                db.query("SELECT content, mediaFile, sizeBytes, thumbnail, isPrivate, sourcePackage FROM clip_entries").use {
                    it.moveToFirst()
                    assertEquals("hello", it.getString(0))
                    assertTrue(it.isNull(1))
                    assertEquals(0L, it.getLong(2))
                    assertTrue(it.isNull(3))
                    assertEquals(0, it.getInt(4))
                    assertTrue(it.isNull(5))
                }
            }
    }

    private companion object {
        const val DATABASE = "migration-test.db"

        /** The language a migrated link takes when no word's locale names one. */
        const val FALLBACK_LANGUAGE = "de"
    }

    @Test
    fun version9Gains10sScreenshotMarkAndKeepsItsImages() {
        helper.createDatabase(DATABASE, 9).use { db ->
            db.execSQL(
                "INSERT INTO clip_entries (content, createdAt, pinnedAt, contentHash, uri, mimeType, mediaFile, " +
                    "sizeBytes, thumbnail, isPrivate, sourcePackage) " +
                    "VALUES ('', 1000, NULL, 42, NULL, 'image/png', 'ab/cd.png', 10, NULL, 0, NULL)",
            )
        }
        helper.runMigrationsAndValidate(DATABASE, 10, true, BorderKeysDatabase.MIGRATION_9_10)
            .use { db ->
                db.query("SELECT mediaFile, fromScreenshot FROM clip_entries").use {
                    it.moveToFirst()
                    assertEquals("ab/cd.png", it.getString(0))
                    assertEquals(0, it.getInt(1))
                }
            }
    }

    @Test
    fun version10KeepsItsPhrasesPerLanguageInLowerCaseWithTheirTotals() {
        helper.createDatabase(DATABASE, 10).use { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted) " +
                    "VALUES ('Friday', 'en-US', 5, 1000, 0, 0), ('acasă', 'ro-RO', 2, 1000, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO user_bigrams (previousWord, word, count, lastUsedAt) " +
                    "VALUES ('The', 'Friday', 2, 1000), ('the', 'Friday', 3, 2000), ('vreau', 'acasă', 4, 1500)",
            )
            db.execSQL(
                "INSERT INTO user_trigrams (previousWord2, previousWord1, word, count, lastUsedAt) " +
                    "VALUES ('On', 'the', 'Friday', 1, 1000)",
            )
        }
        helper.runMigrationsAndValidate(DATABASE, 11, true, BorderKeysDatabase.migration10To11 { FALLBACK_LANGUAGE })
            .use { db ->
                db.query(
                    "SELECT language, previousWord, word, count, lastUsedAt, contextSeen, languageSeen " +
                        "FROM user_bigrams ORDER BY language",
                ).use {
                    it.moveToFirst()
                    assertEquals(listOf("en", "the", "friday"), listOf(it.getString(0), it.getString(1), it.getString(2)))
                    assertEquals(5, it.getInt(3))
                    assertEquals(2000L, it.getLong(4))
                    assertEquals(5L, it.getLong(5))
                    assertEquals(5L, it.getLong(6))
                    it.moveToNext()
                    assertEquals(listOf("ro", "vreau", "acasă"), listOf(it.getString(0), it.getString(1), it.getString(2)))
                    assertTrue(it.isLast)
                }
                db.query("SELECT language, previousWord, total, seen FROM user_pair_contexts ORDER BY language").use {
                    it.moveToFirst()
                    assertEquals(listOf("en", "the", "5"), listOf(it.getString(0), it.getString(1), "${it.getLong(2)}"))
                    assertEquals("a context's observations start at its uses", it.getLong(2), it.getLong(3))
                }
                db.query("SELECT language, previousWord2, previousWord1, total, seen FROM user_triple_contexts").use {
                    it.moveToFirst()
                    assertEquals(listOf("en", "on", "the"), (0..2).map(it::getString))
                    assertEquals(it.getLong(3), it.getLong(4))
                    assertTrue(it.isLast)
                }
                db.query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN " +
                        "('index_user_bigrams_previousWord', 'index_user_trigrams_previousWord2')",
                ).use { assertEquals(2, it.count) }
                db.query("SELECT language, previousWord2, previousWord1, word FROM user_trigrams").use {
                    it.moveToFirst()
                    assertEquals(listOf("en", "on", "the", "friday"), (0..3).map(it::getString))
                }
                db.query("SELECT language, pairs, triples FROM user_phrase_clocks ORDER BY language").use {
                    it.moveToFirst()
                    assertEquals(listOf("en", "5", "1"), listOf(it.getString(0), "${it.getLong(1)}", "${it.getLong(2)}"))
                    it.moveToNext()
                    assertEquals(listOf("ro", "4", "0"), listOf(it.getString(0), "${it.getLong(1)}", "${it.getLong(2)}"))
                }
            }
    }
}
