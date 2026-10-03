// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import java.io.File
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The shipped catalogues and the test resources that describe them, read from disk. */
object Catalogues {

    private val directory = File("src/main/assets/translations")

    /** Every shipped language code, English included, sorted. */
    val languages: List<String> by lazy {
        directory.listFiles()!!.filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()
    }

    /** Every non-English shipped language code, sorted. */
    val translated: List<String> by lazy { languages - Strings.Languages.DEFAULT }

    private val loaded = HashMap<String, Map<String, String>>()

    /** The catalogue of [language], as a key to text map. */
    fun of(language: String): Map<String, String> = loaded.getOrPut(language) {
        LanguageManager.parse(File(directory, "$language.json").readText())
    }

    private val expected = HashMap<String, Set<String>>()

    /** Whether [language] carries [key]: every English key but the plural forms it does not use. */
    fun carries(language: String, key: String): Boolean = key in expected.getOrPut(language) {
        TranslationParity.expectedKeys(of(Strings.Languages.DEFAULT).keys, language)
    }

    /** The test resource [name], parsed as JSON. */
    fun resource(name: String): JsonObject {
        val text = Catalogues::class.java.getResource("/$name")!!.readText()
        return Json.parseToJsonElement(text) as JsonObject
    }

    /** [element]'s strings, or none when it is absent. */
    fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    /** [text] compared as the rules say: NFC and lower-cased. */
    fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()

    /** A regular expression with Unicode word characters and boundaries. */
    fun regex(pattern: String): Regex = Regex("(?U)$pattern")
}
