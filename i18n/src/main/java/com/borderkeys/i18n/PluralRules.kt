// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * The CLDR plural category a number takes in a language, for the catalogue languages and the ones
 * planned beside them. A counted key `name` is the "other" form; `name_one`, `name_few` and the
 * rest are the others, looked up by [LanguageManager.counted].
 */
object PluralRules {

    const val ZERO = "zero"
    const val ONE = "one"
    const val TWO = "two"
    const val FEW = "few"
    const val MANY = "many"
    const val OTHER = "other"

    /** The categories a language uses, [OTHER] last. */
    fun categories(language: String): List<String> = when (base(language)) {
        "ja", "ko", "zh", "vi", "id", "in" -> listOf(OTHER)
        "ro" -> listOf(ONE, FEW, OTHER)
        "cs", "sk", "pl", "ru", "uk" -> listOf(ONE, FEW, MANY, OTHER)
        "lv" -> listOf(ZERO, ONE, OTHER)
        else -> listOf(ONE, OTHER)
    }

    /** The category of the whole number [count] in [language]. */
    fun category(language: String, count: Long): String =
        category(language, if (count < 0) -count else count, 0, 0)

    /**
     * The category of the number shown as [integer], a point and [fractionDigits] digits whose
     * value is [fraction], in [language]: CLDR's operands i, v and f.
     */
    fun category(language: String, integer: Long, fractionDigits: Int, fraction: Long): String {
        val i = integer
        val v = fractionDigits
        val f = fraction
        val whole = f == 0L
        val isOne = i == 1L && whole
        return when (base(language)) {
            "ja", "ko", "zh", "vi", "id", "in" -> OTHER
            "fr" -> if (i == 0L || i == 1L) ONE else OTHER
            "pt" -> if (i == 0L || i == 1L) ONE else OTHER
            "fa", "hi" -> if (i == 0L || isOne) ONE else OTHER
            "es", "hu", "tr", "el", "bg" -> if (isOne) ONE else OTHER
            "fil", "tl" -> {
                val one = if (v == 0) i in 1L..3L || i % 10 !in setOf(4L, 6L, 9L) else f % 10 !in setOf(4L, 6L, 9L)
                if (one) ONE else OTHER
            }
            "ro" -> when {
                i == 1L && v == 0 -> ONE
                v != 0 || i == 0L || (i != 1L && i % 100 in 1L..19L) -> FEW
                else -> OTHER
            }
            "cs", "sk" -> when {
                v != 0 -> MANY
                i == 1L -> ONE
                i in 2L..4L -> FEW
                else -> OTHER
            }
            "pl" -> when {
                v != 0 -> OTHER
                i == 1L -> ONE
                i % 10 in 2L..4L && i % 100 !in 12L..14L -> FEW
                else -> MANY
            }
            "ru", "uk" -> when {
                v != 0 -> OTHER
                i % 10 == 1L && i % 100 != 11L -> ONE
                i % 10 in 2L..4L && i % 100 !in 12L..14L -> FEW
                else -> MANY
            }
            "lv" -> when {
                whole && (i % 10 == 0L || i % 100 in 11L..19L) -> ZERO
                v == 2 && f % 100 in 11L..19L -> ZERO
                whole && i % 10 == 1L && i % 100 != 11L -> ONE
                v == 2 && f % 10 == 1L && f % 100 != 11L -> ONE
                v != 2 && f % 10 == 1L -> ONE
                else -> OTHER
            }
            else -> if (i == 1L && v == 0) ONE else OTHER
        }
    }

    /** The category of [shown], a number as displayed with an optional point or comma, in [language]. */
    fun category(language: String, shown: String): String {
        val digits = shown.trim().removePrefix("-")
        val point = digits.indexOfFirst { it == '.' || it == ',' }
        val integer = (if (point < 0) digits else digits.substring(0, point)).toLongOrNull() ?: 0L
        val fractionText = if (point < 0) "" else digits.substring(point + 1)
        return category(language, integer, fractionText.length, fractionText.toLongOrNull() ?: 0L)
    }

    /** The key [key]'s form for [category]: `key_<category>`, or [key] for "other". */
    fun keyFor(key: String, category: String): String = if (category == OTHER) key else "${key}_$category"

    /** The key [key]'s form for the whole number [count] in [language]. */
    fun keyFor(key: String, language: String, count: Long): String = keyFor(key, category(language, count))

    /** Every suffix a counted key may carry, in any language. */
    val SUFFIXES: List<String> = listOf(ZERO, ONE, TWO, FEW, MANY).map { "_$it" }

    private fun base(language: String): String = language.lowercase().substringBefore('-').substringBefore('_')
}
