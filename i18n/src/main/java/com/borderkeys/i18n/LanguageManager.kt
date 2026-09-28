// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one place a word on screen comes from: a flat key to text map read once, eagerly and
 * synchronously, from `assets/translations/{lang}.json`. A key with no entry comes back as itself.
 */
class LanguageManager(private val context: Context) {

    private var translations: Map<String, String> = emptyMap()

    /** The catalogue currently loaded, which is not necessarily the one that was asked for. */
    var language: String = Strings.Languages.DEFAULT
        private set

    /**
     * Loads [langCode], falling back to [Strings.Languages.DEFAULT] if it is missing or broken.
     * If the default fails as well, the catalogue stays empty.
     */
    fun loadLanguage(langCode: String) {
        try {
            val fileName = Strings.Translations.DIR + langCode + Strings.Translations.JSON_EXTENSION
            val text = context.assets.open(fileName).bufferedReader().use { it.readText() }
            loadFromJson(text, langCode)
        } catch (error: Exception) {
            Log.w(TAG, "language '$langCode' did not load: ${error.message}")
            if (langCode != Strings.Languages.DEFAULT) {
                loadLanguage(Strings.Languages.DEFAULT)
            }
        }
    }

    /** Resolves against what is shipped and what the phone asks for, then loads the winner. */
    fun loadResolved(override: String = Strings.Languages.FOLLOW_SYSTEM) {
        loadLanguage(LanguageResolution.resolve(systemLanguages(), availableLanguages(), override))
    }

    /** The parse step of [loadLanguage], separated so tests can feed text without an asset dir. */
    internal fun loadFromJson(text: String, langCode: String) {
        translations = parse(text)
        language = langCode
    }

    /** The text for [key], or [key] itself when the catalogue has no entry for it. */
    fun getString(key: String): String = translations[key] ?: key

    /** [getString] as `strings[Keys.SOMETHING]`. */
    operator fun get(key: String): String = getString(key)

    /**
     * A line that carries a count, in the form that count takes: `key_one` at one, `key_many`
     * where the language has a separate form for larger numbers, [key] otherwise.
     */
    fun counted(key: String, count: Int): String {
        val suffixed = countedKey(key, count, usesLargeNumberForm)
        val pattern = translations[suffixed] ?: translations[key] ?: key
        return format(pattern, count.toString())
    }

    /** [getString] with `%s` replaced by [arguments], left to right. */
    fun getString(key: String, vararg arguments: Any?): String =
        format(getString(key), *Array(arguments.size) { arguments[it].toString() })

    /** Languages whose larger numbers take a form of their own -- Romanian's "20 de cuvinte". */
    private val usesLargeNumberForm: Boolean
        get() = language.take(2) == LARGE_NUMBER_LANGUAGE

    /** The language codes with a catalogue in assets. */
    fun availableLanguages(): List<String> = try {
        context.assets.list(Strings.Translations.DIR_LIST)
            ?.filter { it.endsWith(Strings.Translations.JSON_EXTENSION) }
            ?.map { it.removeSuffix(Strings.Translations.JSON_EXTENSION) }
            ?.sorted()
            ?: listOf(Strings.Languages.DEFAULT)
    } catch (error: Exception) {
        Log.w(TAG, "asset listing failed: ${error.message}")
        listOf(Strings.Languages.DEFAULT)
    }

    /** The phone's languages, best first, as BCP 47 tags. */
    fun systemLanguages(): List<String> {
        val locales = configuration().locales
        return List(locales.size()) { index -> locales.get(index).toLanguageTag() }
    }

    private fun configuration(): Configuration = context.resources.configuration

    companion object {
        private const val TAG = "LanguageManager"

        /** Languages with a distinct form for larger numbers. */
        internal const val LARGE_NUMBER_LANGUAGE = "ro"

        /** Lenient: a trailing comma is accepted, and a well-formed file reads the same. */
        private val JSON = Json { isLenient = true; ignoreUnknownKeys = true }

        /** Reads a flat object of key to text; a value that is not a string is dropped. */
        fun parse(text: String): Map<String, String> {
            val root = JSON.parseToJsonElement(text)
            if (root !is JsonObject) {
                return emptyMap()
            }
            val out = LinkedHashMap<String, String>(root.size)
            for ((key, value) in root) {
                val primitive = value as? JsonPrimitive ?: continue
                if (primitive.isString) {
                    out[key] = primitive.content
                }
            }
            return out
        }

        /** [counted]'s form selection, pure so the plural rules are testable on their own. */
        internal fun countedKey(key: String, count: Int, usesLargeNumberForm: Boolean): String =
            when {
                count == 1 -> key + "_one"
                usesLargeNumberForm && (count == 0 || count >= LARGE_NUMBER_THRESHOLD) ->
                    key + "_many"
                else -> key
            }

        /** Romanian switches to "20 de cuvinte" here. */
        private const val LARGE_NUMBER_THRESHOLD = 20

        /** Substitutes `%s` placeholders left to right; any other `%` is left as it is. */
        internal fun format(pattern: String, vararg arguments: String): String {
            if (arguments.isEmpty() || !pattern.contains(PLACEHOLDER)) {
                return pattern
            }
            val out = StringBuilder(pattern.length + arguments.sumOf { it.length })
            var index = 0
            var next = 0
            while (index < pattern.length) {
                if (next < arguments.size && pattern.startsWith(PLACEHOLDER, index)) {
                    out.append(arguments[next++])
                    index += PLACEHOLDER.length
                } else {
                    out.append(pattern[index++])
                }
            }
            return out.toString()
        }

        private const val PLACEHOLDER = "%s"
    }
}
