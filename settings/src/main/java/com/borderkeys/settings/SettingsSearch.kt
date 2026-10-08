// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import java.text.Normalizer
import java.util.Locale

/**
 * The search box on the Home screen: which screens, cards and rows match a query, and where
 * each one is.
 *
 * Runs over [SettingsIndex] and the screen titles, through the loaded catalogue, so it finds
 * what the person sees in the language the app is shown in. No text is stored twice.
 */
object SettingsSearch {

    /**
     * One result: the title as shown on its screen, where it is (the screen, then the card
     * under which it sits, or null for a screen itself), the screen a tap opens, the title as
     * the catalogue holds it, placeholders included, and the summary of the Advanced fold the
     * row sits under, as the fold shows it, or null.
     */
    class Match(
        val title: String,
        val place: String?,
        val screen: Screen,
        val titleTemplate: String = title,
        val advancedSummary: String? = null,
    ) {
        private val titlePattern: Regex by lazy {
            Regex(titleTemplate.split(PLACEHOLDER).joinToString(".*") { Regex.escape(it) })
        }

        /** Whether [displayed], a title as its screen draws it, is this match's title with its placeholders filled. */
        fun matchesTitle(displayed: String): Boolean = titlePattern.matches(displayed)
    }

    /**
     * The screens and indexed rows that match [query], at most [limit] of them, in four tiers,
     * each in alphabetical order: titles that begin with the whole query; titles that hold every word
     * of it; titles where each word is held or is within [allowedEdits] of the start of one of
     * the title's words; then rows whose title and note together hold every word. [text]
     * resolves a catalogue key to the words on screen; [hidden] screens are left out.
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
        val tiers = List(TIERS) { ArrayList<Match>() }
        fun consider(match: Match, noteKey: String?) {
            val title = fold(match.title)
            val tier = when {
                title.startsWith(needle) -> 0
                words.all { it in title } -> 1
                words.all { it in title || nearWord(it, title) } -> 2
                noteKey != null && fold(plain(text(noteKey))).let { note ->
                    words.all { it in title || it in note }
                } -> 3
                else -> return
            }
            tiers[tier] += match
        }
        for (screen in Screen.entries) {
            if (screen == Screen.Home || screen in hidden) continue
            consider(Match(plain(text(screen.titleKey)), null, screen), null)
        }
        for (entry in SettingsIndex.entries) {
            if (entry.screen in hidden) continue
            val screenTitle = plain(text(entry.screen.titleKey))
            val card = entry.cardKey?.let { plain(text(it)) }
            val place = if (card == null) screenTitle else screenTitle + PLACE_SEPARATOR + card
            val title = text(entry.key)
            consider(
                Match(plain(title), place, entry.screen, title, entry.advancedKey?.let(text)),
                entry.noteKey,
            )
        }
        return tiers.flatMap { tier -> tier.sortedBy { fold(it.title) } }.take(limit)
    }

    /** Whether [word] is within [allowedEdits] of the start of one of [title]'s words. */
    private fun nearWord(word: String, title: String): Boolean {
        val allowed = allowedEdits(word.length)
        if (allowed == 0) {
            return false
        }
        return title.split(NOT_A_WORD).any { it.isNotEmpty() && prefixDistance(word, it) <= allowed }
    }

    /** How many typos a query word of [length] characters may carry: none under four, two from eight. */
    fun allowedEdits(length: Int): Int = when {
        length < FUZZY_FROM -> 0
        length < TWO_EDITS_FROM -> 1
        else -> 2
    }

    /**
     * The fewest insertions, deletions, substitutions and swaps of two neighbouring characters
     * that turn [query] into some prefix of [target].
     */
    fun prefixDistance(query: String, target: String): Int {
        var beforeLast = IntArray(target.length + 1)
        var last = IntArray(target.length + 1) { it }
        var row = IntArray(target.length + 1)
        for (i in 1..query.length) {
            row[0] = i
            for (j in 1..target.length) {
                val cost = if (query[i - 1] == target[j - 1]) 0 else 1
                var best = minOf(last[j] + 1, row[j - 1] + 1, last[j - 1] + cost)
                if (i > 1 && j > 1 && query[i - 1] == target[j - 2] && query[i - 2] == target[j - 1]) {
                    best = minOf(best, beforeLast[j - 2] + 1)
                }
                row[j] = best
            }
            val spare = beforeLast
            beforeLast = last
            last = row
            row = spare
        }
        return last.min()
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
    private const val TIERS = 4
    private const val FUZZY_FROM = 4
    private const val TWO_EDITS_FROM = 8
    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val KATAKANA = '\u30A1'..'\u30F6'
    private const val KATAKANA_TO_HIRAGANA = 0x60
    private const val PLACE_SEPARATOR = " › "
    private const val PLACEHOLDER = "%s"
    private val BRACKETED_PLACEHOLDER = Regex("""\s*\(%s\)""")
    private val REPEATED_SPACE = Regex("""\s{2,}""")
    private val WHITESPACE = Regex("""\s+""")
}
