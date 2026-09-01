// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * Which catalogue to load, given what the phone asks for and what BorderKeys actually ships.
 *
 * Separate from [LanguageManager] and free of Android types because this is the part with rules
 * in it, and rules deserve tests that run in milliseconds. The manager around it only does I/O.
 */
object LanguageResolution {

    /**
     * Picks a language, in this order:
     *
     *  1. [override], when the user has chosen one by hand and it is still shipped.
     *  2. Each of the phone's preferred languages in turn -- a phone set to Catalan then Spanish
     *     should get Spanish, not English, and that only works by walking the whole list rather
     *     than looking at the first entry.
     *  3. For each of those, the region-less form: `pt-BR` settles for `pt` when that is what
     *     exists, because a Brazilian reading European Portuguese is served far better than a
     *     Brazilian reading English.
     *  4. [Strings.Languages.DEFAULT].
     *
     * @param preferred the phone's languages, best first, as BCP 47 tags (`ro-RO`, `pt-BR`).
     * @param available the language codes with a catalogue on disk.
     * @param override a hand-picked language, or [Strings.Languages.FOLLOW_SYSTEM].
     */
    fun resolve(
        preferred: List<String>,
        available: Collection<String>,
        override: String = Strings.Languages.FOLLOW_SYSTEM,
    ): String {
        if (override != Strings.Languages.FOLLOW_SYSTEM && override in available) {
            return override
        }
        for (tag in preferred) {
            val normalized = normalize(tag)
            if (normalized in available) {
                return normalized
            }
            val base = normalized.substringBefore('-')
            if (base in available) {
                return base
            }
        }
        return Strings.Languages.DEFAULT
    }

    /**
     * Lowercases the language and drops everything after the region, so the tags a phone hands
     * out (`ro-RO`, `ro_RO`, `en-Latn-US`) compare against file names the way a reader expects.
     */
    private fun normalize(tag: String): String {
        val cleaned = tag.replace('_', '-').lowercase()
        val parts = cleaned.split('-')
        return if (parts.size <= 1) parts[0] else parts[0] + "-" + parts[parts.size - 1]
    }
}
