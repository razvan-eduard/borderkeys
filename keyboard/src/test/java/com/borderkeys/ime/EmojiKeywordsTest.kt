// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiKeywordsTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val emojiAssets = File(root, "keyboard/src/main/assets/emoji")

    @Test
    fun `the languages read are the tags' languages, each once, and English last`() {
        assertEquals(listOf("ro", "en"), EmojiKeywords.languagesFor(listOf("ro-RO")))
        assertEquals(listOf("en"), EmojiKeywords.languagesFor(emptyList()))
        assertEquals(
            listOf("en", "de"),
            EmojiKeywords.languagesFor(listOf("en-US", "de-DE", "en-GB")),
        )
        assertEquals("fr", EmojiKeywords.languageOf("fr-FR"))
    }

    /**
     * The shipped keyword files, read from the repository: one per language the application
     * speaks, each covering nearly the whole palette, every line an emoji the palette holds.
     */
    @Test
    fun `every language ships keywords for nearly every emoji in the palette`() {
        val palette = EmojiSearch.parse(File(emojiAssets, "emoji_names.txt").readLines().asSequence())
            .map { it.emoji }
            .toSet()
        assertTrue("the palette is loaded", palette.size > 1000)
        for (language in listOf("en", "de", "es", "fr", "it", "ro")) {
            val file = File(emojiAssets, "keywords/$language.txt")
            assertTrue("$language has keywords", file.isFile)
            val keywords = EmojiSearch.parseKeywords(file.readLines().asSequence())
            assertTrue(
                "$language covers the palette: ${keywords.size} of ${palette.size}",
                keywords.size >= palette.size * 9 / 10,
            )
            for (emoji in keywords.keys) {
                assertTrue("$language keys $emoji, which the palette holds", emoji in palette)
            }
        }
    }

    @Test
    fun `the English keywords find the lantern by the pumpkin`() {
        val index = EmojiSearch.parse(File(emojiAssets, "emoji_names.txt").readLines().asSequence())
        val keywords = EmojiSearch.parseKeywords(
            File(emojiAssets, "keywords/en.txt").readLines().asSequence(),
        )
        // "pump" is a word of the fuel pump's own name, so that comes first; the lantern and
        // the pie follow through their "pumpkin" keyword.
        val pump = EmojiSearch.matches("pump", index, 10, keywords)
        assertEquals("⛽", pump.first())
        assertTrue("the lantern is found: $pump", "🎃" in pump.take(3))
        // Neither is named "pumpkin"; both carry it as a keyword, in palette order.
        assertEquals(listOf("🥧", "🎃"), EmojiSearch.matches("pumpkin", index, 10, keywords))
        // The paper lantern is named so; the jack-o-lantern follows by its keyword.
        assertEquals(listOf("🏮", "🎃"), EmojiSearch.matches("lantern", index, 10, keywords))
        assertTrue("🎃" in EmojiSearch.matches("halloween", index, 30, keywords))
        // The names still come first: every match for "cake" is a cake by name, the
        // birthday cake among them, before any keyword match.
        val cake = EmojiSearch.matches("cake", index, 10, keywords)
        assertEquals(listOf("🍥", "🥮", "🎂", "🍰", "🥞", "🧁"), cake)
    }
}
