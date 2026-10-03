// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.borderkeys.data.DataGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The copy of the appearance the keyboard reads before the first unlock, where it is kept. */
@RunWith(AndroidJUnit4::class)
class DirectBootTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLockedAppearanceLivesInDeviceProtectedStorageAndFollowsTheSettings() {
        DataGraph.install(context.applicationContext)
        assertTrue(DataGraph.isUserUnlocked())
        runBlocking {
            DataGraph.themes.updatePreferences { it.copy(numberRow = true, preferredLanguageTag = "ro-RO") }
            val locked = DataGraph.lockedAppearance.data.first()
            assertTrue(locked.preferences.numberRow)
            assertEquals("", locked.preferences.preferredLanguageTag)
            DataGraph.themes.updatePreferences { it.copy(numberRow = false, preferredLanguageTag = "") }
        }
        val file = java.io.File(
            context.createDeviceProtectedStorageContext().filesDir,
            "datastore/" + DataGraph.LOCKED_APPEARANCE_FILE,
        )
        assertTrue(file.absolutePath, file.isFile)
        assertTrue(file.absolutePath, file.absolutePath.contains("/user_de/"))
    }
}
