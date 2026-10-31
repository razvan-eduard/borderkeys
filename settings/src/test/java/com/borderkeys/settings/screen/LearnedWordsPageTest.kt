// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.backup.BackupRepository
import com.borderkeys.data.entity.UserWord
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.language.LanguageTags
import com.borderkeys.settings.LocalStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Learned words page as a person sees it, its English text read from the catalogue, under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SDK], qualifiers = TALL_SCREEN)
class LearnedWordsPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val strings = LanguageManager(ApplicationProvider.getApplicationContext()).apply { loadLanguage(ENGLISH) }

    private fun listed(spelling: String, language: String, count: Int = USES, added: Int = 0) =
        PersonalEntries.ListedWord(spelling.lowercase(), language, spelling, count, added, listOf(spelling))

    /** Shows [groups] as the page lists them; returns the words Delete was pressed for. */
    private fun show(groups: List<WordGroup>): MutableList<String> {
        val deleted = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                LazyColumn { learnedWordGroups(groups, onBlock = {}, onDelete = { deleted += it.spelling }) }
            }
        }
        return deleted
    }

    @Test
    fun `each language's words sit under its name, a language that is off says when they are suggested, and the rest have their own headings`() {
        show(
            listOf(
                WordGroup("en", off = false, words = listOf(listed("Kelpa", "en", count = 1, added = 1))),
                WordGroup("ro", off = true, words = listOf(listed("drum", "ro"))),
                WordGroup(LanguageTags.UNKNOWN, off = true, words = listOf(listed("zqxw", LanguageTags.UNKNOWN))),
                WordGroup(UserWord.UNPLACED, off = false, words = listOf(listed("taxi", UserWord.UNPLACED))),
            ),
        )
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithText("Kelpa").assertIsDisplayed()
        compose.onNodeWithText("added by hand").assertIsDisplayed()
        compose.onNodeWithText("Romanian").assertIsDisplayed()
        compose.onNodeWithText("drum").assertIsDisplayed()
        compose.onNodeWithText("Unknown language").assertIsDisplayed()
        compose.onNodeWithText("Language not recorded").assertIsDisplayed()
        compose.onNodeWithText("taxi").assertIsDisplayed()
        assertEquals("used $USES times", 3, compose.onAllNodesWithText("used $USES times").fetchSemanticsNodes().size)
        assertEquals(
            "under each language that is off, and only there",
            2,
            compose.onAllNodesWithText("Suggested while this language is on.").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `delete on a row deletes that word`() {
        val deleted = show(listOf(WordGroup("en", off = false, words = listOf(listed("cat", "en"), listed("dog", "en")))))
        compose.onAllNodesWithText("Delete")[1].performClick()
        assertEquals(listOf("dog"), deleted)
    }

    @Test
    fun `an import says what it left out, blocked here or unreadable, after what it did`() {
        assertEquals("Read in.", importedNotice("Read in.", BackupRepository.Applied(), strings))
        assertEquals(
            "Read in.\nLeft out, blocked on this device: 2\nLeft out, could not be read: 1",
            importedNotice("Read in.", BackupRepository.Applied(leftOutBlocked = 2, leftOutUnreadable = 1), strings),
        )
    }

    private companion object {
        const val ENGLISH = "en"
        const val USES = 3
    }
}

/** The Android version these Robolectric tests run on: the newest it has offline. */
private const val SDK = 35

/** A screen tall enough to show every row the tests list at once. */
private const val TALL_SCREEN = "w411dp-h1600dp"
