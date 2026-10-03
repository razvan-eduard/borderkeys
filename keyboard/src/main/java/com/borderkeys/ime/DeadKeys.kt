// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

/**
 * What a dead key does to the next character: the letter with its mark, composed to one
 * precomposed code point under NFC, or nothing when no such character exists. Pure, and free of
 * Android classes, so the typing flow can use it.
 */
object DeadKeys {

    /** One accent: its combining mark, and the character a dead key followed by space writes. */
    class Accent(val mark: Char, val spacing: String, val name: String)

    private val ACCENTS: Map<Int, Accent> = mapOf(
        KeyCodes.DEAD_ACUTE to Accent('́', "´", "dead_acute"),
        KeyCodes.DEAD_GRAVE to Accent('̀', "`", "dead_grave"),
        KeyCodes.DEAD_CIRCUMFLEX to Accent('̂', "^", "dead_circumflex"),
        KeyCodes.DEAD_DIAERESIS to Accent('̈', "¨", "dead_diaeresis"),
        KeyCodes.DEAD_TILDE to Accent('̃', "~", "dead_tilde"),
        KeyCodes.DEAD_CARON to Accent('̌', "ˇ", "dead_caron"),
        KeyCodes.DEAD_BREVE to Accent('̆', "˘", "dead_breve"),
        KeyCodes.DEAD_CEDILLA to Accent('̧', "¸", "dead_cedilla"),
        KeyCodes.DEAD_OGONEK to Accent('̨', "˛", "dead_ogonek"),
        KeyCodes.DEAD_RING to Accent('̊', "˚", "dead_ring"),
        KeyCodes.DEAD_MACRON to Accent('̄', "¯", "dead_macron"),
        KeyCodes.DEAD_COMMA_BELOW to Accent('̦', " ̦", "dead_comma_below"),
    )

    /**
     * Pairs NFC does not give the letter its users expect: Latvian writes ķ ļ ņ ŗ ģ, whose
     * canonical mark is the cedilla, with a comma below.
     */
    private val OVERRIDES: Map<Long, Int> = buildMap {
        for ((base, result) in listOf('k' to 'ķ', 'l' to 'ļ', 'n' to 'ņ', 'r' to 'ŗ', 'g' to 'ģ')) {
            put(pair(KeyCodes.DEAD_COMMA_BELOW, base.code), result.code)
            put(pair(KeyCodes.DEAD_COMMA_BELOW, base.uppercaseChar().code), result.uppercaseChar().code)
        }
    }

    /** Every dead key's code, in the order the modifier row editor offers them. */
    val CODES: List<Int> = ACCENTS.keys.toList()

    fun isDead(code: Int): Boolean = code in ACCENTS

    fun accent(code: Int): Accent? = ACCENTS[code]

    /** The cap a dead key shows: its mark on a dotted circle. */
    fun cap(code: Int): String? = ACCENTS[code]?.let { "◌${it.mark}" }

    /** [base] with [deadCode]'s mark as one code point, or null when there is none. */
    fun combine(base: Int, deadCode: Int): Int? {
        OVERRIDES[pair(deadCode, base)]?.let { return it }
        val accent = ACCENTS[deadCode] ?: return null
        if (!Character.isLetter(base)) {
            return null
        }
        val composed = Normalizer.normalize(String(Character.toChars(base)) + accent.mark, Normalizer.Form.NFC)
        return if (composed.codePointCount(0, composed.length) == 1) composed.codePointAt(0) else null
    }

    private fun pair(deadCode: Int, base: Int): Long = (deadCode.toLong() shl 32) or (base.toLong() and 0xFFFFFFFFL)
}
