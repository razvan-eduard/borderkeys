// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.CustomLayout
import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The rules a layout must meet before the keyboard draws it, on the shipped assets and on broken text. */
class LayoutValidatorTest {

    private val assets = File("src/main/assets/layouts")

    private fun qwerty(): String = File(assets, "qwerty.json").readText()

    @Test
    fun `every shipped layout passes`() {
        val files = assets.listFiles { file -> file.extension == "json" }.orEmpty()
        assertTrue(files.size >= 35)
        for (file in files) {
            val problems = LayoutValidator.validate(file.readText())
            assertEquals("${file.name}: $problems", emptyList<LayoutValidator.Problem>(), problems)
            assertNotNull(LayoutValidator.parse(file.readText()))
        }
    }

    @Test
    fun `text that is not JSON, or has no rows, is one problem`() {
        assertEquals(listOf(Keys.LAYOUT_ERROR_JSON), LayoutValidator.validate("{ not json").map { it.messageKey })
        assertEquals(listOf(Keys.LAYOUT_ERROR_NO_ROWS), LayoutValidator.validate("""{"rows": []}""").map { it.messageKey })
        assertEquals(listOf(Keys.LAYOUT_ERROR_NO_ROWS), LayoutValidator.validate("""{"id": "x"}""").map { it.messageKey })
        assertNull(LayoutValidator.parse("{ not json"))
    }

    @Test
    fun `a character twice, a key of two characters and an unknown code are named by row and key`() {
        val text = qwerty()
            .replaceFirst("\"c\": \"w\"", "\"c\": \"q\"")
            .replaceFirst("\"c\": \"e\"", "\"c\": \"ee\"")
            .replaceFirst("\"code\": \"shift\"", "\"code\": \"fn\"")
        val problems = LayoutValidator.validate(text)
        val keys = problems.map { it.messageKey }
        assertTrue(Keys.LAYOUT_ERROR_DUPLICATE_KEY in keys)
        assertTrue(Keys.LAYOUT_ERROR_KEY_NOT_ONE_CHAR in keys)
        assertTrue(Keys.LAYOUT_ERROR_UNKNOWN_CODE in keys)
        val duplicate = problems.first { it.messageKey == Keys.LAYOUT_ERROR_DUPLICATE_KEY }
        assertEquals(0, duplicate.row)
        assertEquals(1, duplicate.key)
    }

    @Test
    fun `a Latin layout short of a letter, or a last row without space and enter, is refused`() {
        val missing = qwerty().replaceFirst("\"c\": \"z\"", "\"c\": \"ß\"")
        assertTrue(Keys.LAYOUT_ERROR_MISSING_LETTERS in LayoutValidator.validate(missing).map { it.messageKey })
        val noSpace = qwerty().replaceFirst("\"code\": \"space\"", "\"code\": \"tab\"")
        assertTrue(Keys.LAYOUT_ERROR_NO_SPACE_ENTER in LayoutValidator.validate(noSpace).map { it.messageKey })
    }

    @Test
    fun `the chosen layout is the user's own under its own id, a built-in by its id, or the subtype's`() {
        val own = CustomLayout("custom-3", "Mine", "ro-RO", qwerty().replaceFirst("\"c\": \"q\"", "\"c\": \"q\", \"alt\": \"ø\""))
        val loads = mutableListOf<String>()
        val load: (String) -> KeyboardLayout = { id -> loads += id; KeyboardLayout.fallbackQwerty() }
        val chosen = LayoutChoice.resolve("qwerty", mapOf("qwerty" to "custom-3"), listOf(own), load)
        assertEquals("custom-3", chosen.id)
        assertEquals("ro-RO", chosen.languageTag)
        assertTrue(chosen.rows.first().keys.any { it.code == 'q'.code && it.alternatives.contains("ø") })
        assertTrue(loads.isEmpty())

        LayoutChoice.resolve("qwerty", mapOf("qwerty" to "azerty"), emptyList(), load)
        assertEquals(listOf("azerty"), loads)

        val broken = own.copy(json = "{ not json")
        LayoutChoice.resolve("qwerty", mapOf("qwerty" to "custom-3"), listOf(broken), load)
        assertEquals(listOf("azerty", "qwerty"), loads)
        LayoutChoice.resolve("qwerty", mapOf("qwerty" to "custom-9"), listOf(own), load)
        assertEquals(listOf("azerty", "qwerty", "qwerty"), loads)
    }

    @Test
    fun `a parsed custom layout compiles with no gaps`() {
        val layout = LayoutValidator.parse(qwerty())!!
        val geometry = KeyboardGeometry().apply { compile(layout, 1080f, 640f, 8f) }
        assertEquals(layout.keyCount, geometry.keyCount)
        for (x in 0 until 1080 step 7) {
            for (y in 0 until 640 step 7) {
                assertTrue(geometry.findKeyAt(x.toFloat(), y.toFloat()) != KeyboardGeometry.NO_KEY)
            }
        }
    }
}
