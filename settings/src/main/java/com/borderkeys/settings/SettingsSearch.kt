// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import java.text.Normalizer
import java.util.Locale

/**
 * The search box on the Home screen: which screens, cards and rows carry every word of a
 * query in their title, and where each one is.
 *
 * Runs over [SettingsIndex] and the screen titles, through the loaded catalogue, so it finds
 * what the person sees in the language the app is shown in. No text is stored twice.
 */
object SettingsSearch {

    /**
     * One result: the title as shown on its screen, where it is (the screen, then the card
     * under which it sits, or null for a screen itself) and the screen a tap opens.
     */
    class Match(val title: String, val place: String?, val screen: Screen)

    /**
     * The screens and indexed rows whose title holds each word of [query], those whose title
     * begins with the whole query first, then the rest in index order, at most [limit] of them.
     * [text] resolves a catalogue key to the words on screen; [hidden] screens are left out.
     */
    fun find(
        query: String,
        text: (String) -> String,
        hidden: Set<Screen> = emptySet(),
        limit: Int = MAX_MATCHES,
    ): List<Match> {
        val needle = fold(query.trim())
        val words = needle.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return emptyList()
        }
        val leading = ArrayList<Match>()
        val other = ArrayList<Match>()
        fun consider(match: Match) {
            val title = fold(match.title)
            when {
                title.startsWith(needle) -> leading += match
                words.all { it in title } -> other += match
            }
        }
        for (screen in Screen.entries) {
            if (screen == Screen.Home || screen in hidden) continue
            consider(Match(plain(text(screen.titleKey)), null, screen))
        }
        for (entry in SettingsIndex.entries) {
            if (entry.screen in hidden) continue
            val screenTitle = plain(text(entry.screen.titleKey))
            val card = entry.cardKey?.let { plain(text(it)) }
            val place = if (card == null) screenTitle else screenTitle + PLACE_SEPARATOR + card
            consider(Match(plain(text(entry.key)), place, entry.screen))
        }
        return (leading + other).take(limit)
    }

    /** A title with the count or name it is formatted with at runtime left out. */
    fun plain(title: String): String =
        title.replace(BRACKETED_PLACEHOLDER, "")
            .replace(PLACEHOLDER, "")
            .replace(REPEATED_SPACE, " ")
            .trim()

    /**
     * [text] as compared: compatibility forms and case folded, accents and other combining marks
     * dropped, ß as ss, dotless ı as i, katakana as hiragana.
     */
    fun fold(text: String): String {
        val lowered = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == 'ß' -> out.append("ss")
                c == 'ı' -> out.append('i')
                c in KATAKANA -> out.append(c - KATAKANA_TO_HIRAGANA)
                else -> out.append(c)
            }
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC)
    }

    private const val MAX_MATCHES = 40
    private val KATAKANA = '\u30A1'..'\u30F6'
    private const val KATAKANA_TO_HIRAGANA = 0x60
    private const val PLACE_SEPARATOR = " › "
    private const val PLACEHOLDER = "%s"
    private val BRACKETED_PLACEHOLDER = Regex("""\s*\(%s\)""")
    private val REPEATED_SPACE = Regex("""\s{2,}""")
    private val WHITESPACE = Regex("""\s+""")
}
