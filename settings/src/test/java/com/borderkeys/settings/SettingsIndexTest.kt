// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index the build generates from the screen sources, and the search over it, against every
 * catalogue.
 */
class SettingsIndexTest {

    private val translations = File("../i18n/src/main/assets/translations")

    private val catalogues: Map<String, Map<String, String>> =
        translations.listFiles()!!.filter { it.extension == "json" }
            .associate { it.name.removeSuffix(".json") to LanguageManager.parse(it.readText()) }

    private val english: Map<String, String> = catalogues.getValue("en")

    private fun text(key: String): String = english.getValue(key)

    @Test
    fun `every indexed title is in every catalogue`() {
        for ((language, catalogue) in catalogues) {
            for (entry in SettingsIndex.entries) {
                assertTrue("${entry.key} is not in $language.json", entry.key in catalogue)
                entry.cardKey?.let { assertTrue("$it is not in $language.json", it in catalogue) }
                entry.noteKey?.let { assertTrue("$it is not in $language.json", it in catalogue) }
            }
        }
    }

    @Test
    fun `every screen is found by its own title in every catalogue`() {
        for ((language, catalogue) in catalogues) {
            for (screen in Screen.entries) {
                if (screen == Screen.Home) continue
                val title = SettingsSearch.plain(catalogue.getValue(screen.titleKey))
                val matches = SettingsSearch.find(title, catalogue::getValue, limit = Int.MAX_VALUE)
                assertTrue(
                    "$language: '$title' does not find ${screen.name}",
                    matches.any { it.screen == screen && it.place == null },
                )
            }
        }
    }

    @Test
    fun `a query typed without accents finds the accented title`() {
        val romanian = catalogues.getValue("ro")
        val title = SettingsSearch.plain(romanian.getValue(Keys.SCREEN_DICTIONARY_AND_HEATMAP))
        val bare = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        assertTrue(title != bare)
        assertTrue(SettingsSearch.find(bare, romanian::getValue).any { it.screen == Screen.Dictionary && it.place == null })
    }

    @Test
    fun `folding drops accents, case and width and joins the kana`() {
        assertEquals("stergere", SettingsSearch.fold("Ștergere"))
        assertEquals("stergere", SettingsSearch.fold("Ştergere"))
        assertEquals("grosse", SettingsSearch.fold("Größe"))
        assertEquals("istanbul", SettingsSearch.fold("İstanbul"))
        assertEquals("istanbul", SettingsSearch.fold("ıstanbul"))
        assertEquals("かたかな", SettingsSearch.fold("カタカナ"))
        assertEquals("かたかな", SettingsSearch.fold("ｶﾀｶﾅ"))
        assertEquals("abc", SettingsSearch.fold("ＡＢＣ"))
    }

    @Test
    fun `a word finds the card that carries it, under its screen`() {
        val match = SettingsSearch.find("learned", ::text).first { it.screen == Screen.Dictionary }
        assertEquals("Learned", match.title)
        assertEquals(text(Keys.SCREEN_DICTIONARY_AND_HEATMAP), match.place)
    }

    @Test
    fun `a list page is found once, by its own title`() {
        val matches = SettingsSearch.find("learned words", ::text)
        assertEquals(1, matches.count { it.screen == Screen.LearnedWords })
        val match = matches.first()
        assertEquals(Screen.LearnedWords, match.screen)
        assertEquals(null, match.place)
    }

    @Test
    fun `a row is placed under its screen and its card`() {
        val match = SettingsSearch.find("suggest whole phrases", ::text).single()
        assertEquals(Screen.Typing, match.screen)
        assertTrue(match.place!!.startsWith(text(Keys.SCREEN_SUGGESTIONS_AND_CORRECTIONS)))
        assertTrue(match.place!!.length > text(Keys.SCREEN_SUGGESTIONS_AND_CORRECTIONS).length)
    }

    @Test
    fun `a screen is found by its own title, ahead of rows that mention it`() {
        val matches = SettingsSearch.find("theme", ::text)
        assertEquals(Screen.Theme, matches.first().screen)
        assertEquals(null, matches.first().place)
        assertTrue(matches.size > 1)
    }

    @Test
    fun `every word of the query has to be in the title`() {
        assertEquals(1, SettingsSearch.find("learned phrases", ::text).size)
        assertEquals(1, SettingsSearch.find("PHRASES learned", ::text).size)
        assertEquals(3, SettingsSearch.find("phrases", ::text).size)
        assertTrue(SettingsSearch.find("learned zebra", ::text).isEmpty())
    }

    @Test
    fun `a blank query finds nothing`() {
        assertTrue(SettingsSearch.find("   ", ::text).isEmpty())
    }

    @Test
    fun `a hidden screen and its rows are left out`() {
        val hidden = setOf(Screen.Assistant)
        assertTrue(SettingsSearch.find("model", ::text).any { it.screen == Screen.Assistant })
        assertTrue(SettingsSearch.find("model", ::text, hidden).none { it.screen == Screen.Assistant })
    }

    @Test
    fun `the start of a word finds a setting drawn under a heading`() {
        val matches = SettingsSearch.find("scr", ::text)
        assertTrue(matches.any { it.screen == Screen.Clipboard && it.title == text(Keys.CLIPBOARD_SCREENSHOT_SUGGESTION) })
    }

    @Test
    fun `a picker titled above its chips is found`() {
        assertTrue(SettingsSearch.find("capitalise for me", ::text).any { it.screen == Screen.Typing })
        assertTrue(SettingsSearch.find("rare words", ::text).any { it.screen == Screen.Typing })
    }

    @Test
    fun `a word with a typo finds the title`() {
        assertTrue(SettingsSearch.find("screnshot", ::text).any { it.title == text(Keys.CLIPBOARD_SCREENSHOT_SUGGESTION) })
        assertTrue(SettingsSearch.find("scrnshot", ::text).any { it.title == text(Keys.CLIPBOARD_SCREENSHOT_SUGGESTION) })
        assertTrue(SettingsSearch.find("vibraton", ::text).any { it.screen == Screen.Layout })
    }

    @Test
    fun `an exact title ranks above one found through a typo or a note`() {
        val matches = SettingsSearch.find("swipe typing", ::text)
        assertEquals(text(Keys.SWIPE_SWIPE_TYPING), matches.first().title)
    }

    @Test
    fun `a word only in a note finds the row, after every title match`() {
        val matches = SettingsSearch.find("uncorrected", ::text)
        assertTrue(matches.any { it.title == text(Keys.CORRECTIONS_RARE_WORDS) })
        assertTrue(matches.none { "uncorrected" in SettingsSearch.fold(it.title) })
    }

    @Test
    fun `a short query is matched exactly`() {
        assertEquals(0, SettingsSearch.allowedEdits(3))
        assertEquals(1, SettingsSearch.allowedEdits(4))
        assertEquals(2, SettingsSearch.allowedEdits(8))
        assertTrue(SettingsSearch.find("qzx", ::text).isEmpty())
    }

    @Test
    fun `the prefix distance counts edits against the closest start of the word`() {
        assertEquals(0, SettingsSearch.prefixDistance("scr", "screenshot"))
        assertEquals(1, SettingsSearch.prefixDistance("screnshot", "screenshot"))
        assertEquals(1, SettingsSearch.prefixDistance("scrn", "screenshot"))
        assertEquals(1, SettingsSearch.prefixDistance("teh", "the"))
        assertEquals(2, SettingsSearch.prefixDistance("ab", "xy"))
    }

    @Test
    fun `a title formatted with a count is shown without it`() {
        assertEquals("Learned phrases", SettingsSearch.plain("Learned phrases (%s)"))
        assertEquals("Forget", SettingsSearch.plain("Forget %s"))
    }
}
