// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The link tables' migrations, run on a database created at the older version from its exported schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class LinksMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BorderKeysDatabase::class.java)

    @Test
    fun `version 13 adds the chosen and added-by-hand counts to pairs and triples, every existing one at zero`() {
        helper.createDatabase(NAME, FROM).use { db ->
            db.execSQL(
                "INSERT INTO user_bigrams (language, previousWord, word, count, lastUsedAt, contextSeen, languageSeen) " +
                    "VALUES ('en', 'we', 'go', 3, 5, 7, 9)",
            )
            db.execSQL(
                "INSERT INTO user_trigrams (language, previousWord2, previousWord1, word, count, lastUsedAt, " +
                    "contextSeen, languageSeen) VALUES ('en', 'we', 'go', 'home', 2, 5, 7, 9)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(NAME, TO, true, BorderKeysDatabase.MIGRATION_12_13)
        migrated.query("SELECT word, count, asserted, added FROM user_bigrams").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(listOf("go", 3, 0, 0), listOf(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3)))
        }
        migrated.query("SELECT word, count, asserted, added FROM user_trigrams").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(listOf("home", 2, 0, 0), listOf(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3)))
        }
    }

    private companion object {
        const val NAME = "links-migration-test"
        const val FROM = 12
        const val TO = 13
    }
}
