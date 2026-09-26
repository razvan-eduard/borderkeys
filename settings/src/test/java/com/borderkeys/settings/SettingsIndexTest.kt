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
 * The generated index against the screen sources it was generated from, and the search over
 * it against the English catalogue.
 *
 * The first test is the same scan tools/gen_settings_index.py runs, so a row added to a screen
 * without the script being run again fails here, with the regeneration command in the message.
 */
class SettingsIndexTest {

    private val screens = File("src/main/java/com/borderkeys/settings/screen")

    private val english: Map<String, String> =
        LanguageManager.parse(File("../i18n/src/main/assets/translations/en.json").readText())

    private fun text(key: String): String = english.getValue(key)

    @Test
    fun `the index matches the screen sources`() {
        val expected = scanSources()
        val actual = SettingsIndex.entries.map { Triple(it.screen, it.cardKey, it.key) }
        assertEquals("run python3 tools/gen_settings_index.py", expected, actual)
    }

    @Test
    fun `every indexed title is in the catalogue`() {
        for (entry in SettingsIndex.entries) {
            assertTrue("${entry.key} is not in en.json", entry.key in english)
            entry.cardKey?.let { assertTrue("$it is not in en.json", it in english) }
        }
    }

    @Test
    fun `a word finds the card that carries it, under its screen`() {
        val match = SettingsSearch.find("phrases", ::text).single { it.screen == Screen.Dictionary }
        assertEquals("Learned phrases", match.title)
        assertEquals(text(Keys.SCREEN_PERSONAL_DICTIONARY), match.place)
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
        assertEquals(2, SettingsSearch.find("phrases", ::text).size)
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
    fun `a title formatted with a count is shown without it`() {
        assertEquals("Learned phrases", SettingsSearch.plain("Learned phrases (%s)"))
        assertEquals("Forget", SettingsSearch.plain("Forget %s"))
    }

    /**
     * What tools/gen_settings_index.py writes, derived the same way from the same files: the
     * constant names it finds, lower-cased, are the catalogue keys the constants hold.
     */
    private fun scanSources(): List<Triple<Screen, String?, String>> {
        val out = ArrayList<Triple<Screen, String?, String>>()
        for (file in screens.listFiles()!!.filter { it.extension == "kt" }.sortedBy { it.name }) {
            val screen = screenOf(file.name) ?: continue
            val text = file.readLines().joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) "" else line
            }
            val seen = HashSet<Pair<Screen, String>>()
            var card: String? = null
            val events = ANY_CARD.findAll(text).map { it.range.first to it } +
                ROW.findAll(text).map { it.range.first to it }
            for ((position, match) in events.sortedBy { it.first }) {
                if (match.value.startsWith(CARD_NAME)) {
                    card = CARD.matchAt(text, position)?.groupValues?.get(1)?.lowercase()
                    if (card != null && seen.add(screen to card)) {
                        out += Triple(screen, null, card)
                    }
                } else {
                    val key = match.groupValues[1].lowercase()
                    if (seen.add(screen to key)) {
                        out += Triple(screen, card, key)
                    }
                }
            }
        }
        return out
    }

    private fun screenOf(name: String): Screen? {
        val screenName = EXTRA_SOURCES[name]
            ?: name.takeIf { it.endsWith(SCREEN_SUFFIX) }?.removeSuffix(SCREEN_SUFFIX)
            ?: return null
        if (screenName in SKIPPED_SCREENS) return null
        return Screen.entries.first { it.name == screenName }
    }

    private companion object {
        const val SCREEN_SUFFIX = "Screen.kt"
        const val CARD_NAME = "SettingsSectionCard"
        val EXTRA_SOURCES = mapOf("EventEffectsSection.kt" to "Effects")
        val SKIPPED_SCREENS = setOf("Home", "Features", "ProcessText", "Transfer")
        const val TITLE = """\(\s*(?:title\s*=\s*)?strings(?:\[|\.getString\()Keys\.([A-Z0-9_]+)"""
        val CARD = Regex(CARD_NAME + TITLE)
        val ANY_CARD = Regex("""SettingsSectionCard\(""")
        val ROW = Regex("(?:SettingRow|SwitchRow)$TITLE")
    }
}
