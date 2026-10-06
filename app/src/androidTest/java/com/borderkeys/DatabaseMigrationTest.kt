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
}
