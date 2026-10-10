// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Credential-encrypted storage is read through `DataGraph` only after the user's first unlock.
 * Every use of it in the service and the dictionary loader must sit in a member that runs only
 * once unlocked, or behind a check of `unlocked` in the same member. The members that run only
 * once unlocked are listed here, by file.
 */
class UnlockedPathsTest {

    /** Members of `DataGraph` readable before the unlock. */
    private val lockedSafe = setOf("install", "isUserUnlocked", "lockedAppearance", "LOCKED_APPEARANCE_FILE")

    /** Members reached only after the unlock, by file. */
    private val unlockedOnly = mapOf(
        "BorderKeysService.kt" to setOf(
            // Started by startUnlocked, or from what it starts.
            "loadDictionaries", "startUnlocked", "observeSettings", "observeLanguagePacks",
            "observeDictionaryEdits", "restoreLanguageEvidence",
            // Reached through the learning flow, whose gate follows FieldPolicy.userUnlocked.
            "learningStore", "persistLearning", "maybeDecayPersonalDictionary",
            // Reached from the clipboard panel, opened by offerClipboardHistory, or from the
            // clipboard listener, registered by registerClipboardListener.
            "onClipPinToggled", "onClipDeleted", "refreshClipboardPanel", "onClipboardPicked",
            "onClipboardChanged",
            // Behind the field policy's clipboard gate, which follows FieldPolicy.userUnlocked.
            "offerClipboardHistory", "rememberScreenshot",
        ),
        "DictionaryLoader.kt" to setOf("load", "reloadPersonal"),
    )

    @Test
    fun `credential storage is read only on unlocked paths`() {
        val offences = mutableListOf<String>()
        for ((name, allowed) in unlockedOnly) {
            val file = File("src/main/java/com/borderkeys/ime", name)
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                for (match in Regex("""\bDataGraph\.(\w+)""").findAll(line)) {
                    if (match.groupValues[1] in lockedSafe) {
                        continue
                    }
                    val (memberLine, memberName) = enclosingMember(lines, index)
                    if (memberName in allowed) {
                        continue
                    }
                    val guarded = (memberLine..index).any { lines[it].contains("unlocked") }
                    if (!guarded) {
                        offences += "$name:${index + 1} in $memberName: ${line.trim()}"
                    }
                }
            }
        }
        assertEquals(
            "a DataGraph use outside the unlocked members and without an unlocked check",
            emptyList<String>(),
            offences,
        )
    }

    /** The class-level member [index] is in: its first line and its name. */
    private fun enclosingMember(lines: List<String>, index: Int): Pair<Int, String> {
        val declaration = Regex("""^    (?:\w+ )*(?:fun|val|var) (?:[\w.<>]+\.)?(`[^`]+`|\w+)""")
        for (at in index downTo 0) {
            val match = declaration.find(lines[at]) ?: continue
            return at to match.groupValues[1]
        }
        return 0 to ""
    }
}
