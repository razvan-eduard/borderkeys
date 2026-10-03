// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * Which catalogue to load, given what the phone asks for and what BorderKeys ships. Free of
 * Android types; [LanguageManager] does the I/O.
 */
object LanguageResolution {

    /**
     * Picks a language, in this order:
     *
     *  1. [override], when the user has chosen one by hand and it is still shipped.
     *  2. Each of the phone's preferred languages in turn.
     *  3. For each of those, the region-less form: `pt-BR` settles for `pt`.
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
            candidates(tag).firstOrNull { it in available }?.let { return it }
        }
        return Strings.Languages.DEFAULT
    }

    /** Whether [language] is written right to left. */
    fun isRightToLeft(language: String): Boolean =
        candidates(language).lastOrNull() in RIGHT_TO_LEFT

    /**
     * The codes a tag may be served by, best first: the language with its region, then the bare
     * language. Simplified Chinese, by script or region, is `zh-cn`; Traditional has no fallback.
     */
    private fun candidates(tag: String): List<String> {
        val parts = tag.replace('_', '-').lowercase().split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val language = ALIASES[parts[0]] ?: parts[0]
        val script = parts.drop(1).firstOrNull { it.length == 4 }
        val region = parts.drop(1).lastOrNull { it.length == 2 || it.length == 3 && it.all(Char::isDigit) }
        if (language == CHINESE) {
            val traditional = script == "hant" || script == null && region in TRADITIONAL_REGIONS
            return if (traditional) listOfNotNull(region?.let { "$CHINESE-$it" }) else listOf(SIMPLIFIED_CHINESE)
        }
        return listOfNotNull(region?.let { "$language-$it" }, language)
    }

    private const val CHINESE = "zh"
    private const val SIMPLIFIED_CHINESE = "zh-cn"
    private val TRADITIONAL_REGIONS = setOf("tw", "hk", "mo")

    /** Legacy codes Android still hands out, by the code the catalogues use. */
    private val ALIASES = mapOf("in" to "id", "iw" to "he", "ji" to "yi", "tl" to "fil")

    private val RIGHT_TO_LEFT = setOf("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv", "ckb")
}
