// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which swipe model a letter layout decodes with: an own-script layout its own, when the build
 * ships it and it has not failed to load three times; a layout whose letters are Latin the Latin
 * model; any other layout none, so the geometric decoder reads it. The engine keeps a model by
 * its [Model.script] id.
 */
object SwipeModels {

    class Model(val script: Int, val asset: String) {
        override fun equals(other: Any?): Boolean = other is Model && other.script == script && other.asset == asset

        override fun hashCode(): Int = script * 31 + asset.hashCode()
    }

    /** The model in every `plus` build. */
    val LATIN = Model(0, "model.bkw")

    /** The directory a script's model is shipped in, as `<layout>.bkw`. */
    const val DIRECTORY = "swipe"

    /** How many failed loads write a model off. */
    const val MAX_FAILURES = 3

    /** The own-script layouts that may have a model, by asset id, and the id the engine keeps it by. */
    private val SCRIPTS = mapOf(
        "russian" to 1, "ukrainian" to 2, "bulgarian" to 3, "serbian" to 4, "macedonian" to 5,
        "greek" to 6, "armenian" to 7, "georgian" to 8, "hebrew" to 9, "arabic" to 10,
    )

    /** The suffixes a layout's id gains for its variants: rows added, keys removed. */
    private val VARIANT_SUFFIX = Regex("""[+].*$|-no(emoji|globe)$""")

    /**
     * The model for the layout [layoutId] with the letter keys [letters], given the models the
     * build [shipped] (file names in [DIRECTORY]) and the ones written off ([dead], by asset
     * path); null when the geometric decoder reads it.
     */
    fun modelFor(layoutId: String, letters: Collection<String>, shipped: Set<String>, dead: Set<String>): Model? {
        val base = baseId(layoutId)
        val script = SCRIPTS[base]
        if (script != null) {
            val asset = "$DIRECTORY/$base.bkw"
            return if ("$base.bkw" in shipped && asset !in dead) Model(script, asset) else null
        }
        return if (isLatin(letters)) LATIN else null
    }

    /** [layoutId] without its variant suffixes. */
    fun baseId(layoutId: String): String {
        var id = layoutId
        while (true) {
            val stripped = id.replace(VARIANT_SUFFIX, "")
            if (stripped == id) return id
            id = stripped
        }
    }

    /** Whether every letter among [letters] is in the Latin script, and there is one. */
    fun isLatin(letters: Collection<String>): Boolean {
        val codePoints = letters.flatMap { label -> label.codePoints().toArray().asList() }.filter(Character::isLetter)
        return codePoints.isNotEmpty() &&
            codePoints.all { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
    }
}
