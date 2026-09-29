// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.borderkeys.data.BorderKeysDatabase
import org.junit.Assert.assertEquals
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

    private companion object {
        const val DATABASE = "migration-test.db"
    }
}
