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

/** The words table's migrations, run on a database created at the older version from its exported schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class WordsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BorderKeysDatabase::class.java)

    @Test
    fun `version 12 adds the added-by-hand count, every existing word at zero`() {
        helper.createDatabase(NAME, FROM).use { db ->
            db.execSQL(
                "INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted) " +
                    "VALUES ('nadia', 'en-US', 3, 5, 1, 2)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(NAME, TO, true, BorderKeysDatabase.MIGRATION_11_12)
        migrated.query("SELECT word, count, asserted, added FROM user_words").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                listOf("nadia", 3, 2, 0),
                listOf(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3)),
            )
        }
    }

    private companion object {
        const val NAME = "migration-test"
        const val FROM = 11
        const val TO = 12
    }
}
