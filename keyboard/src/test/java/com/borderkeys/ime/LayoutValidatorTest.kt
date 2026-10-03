// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.CustomLayout
import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `a modmap of shift and ctrl, one character to one, passes, and anything else is named`() {
        fun withModmap(modmap: String) = qwerty().replaceFirst("{", "{\"modmap\": $modmap, ")
        assertEquals(emptyList<String>(), LayoutValidator.validate(withModmap("""{"shift": {"i": "İ"}, "ctrl": {"с": "c"}}""")).map { it.messageKey })
        for (bad in listOf("""{"fn": {"a": "b"}}""", """{"shift": {"i": "İİ"}}""", """{"shift": {"ab": "c"}}""", """{"shift": "i"}""", "\"x\"", """{"shift": {"i": 5}}""")) {
            assertEquals(bad, listOf(Keys.LAYOUT_ERROR_MODMAP), LayoutValidator.validate(withModmap(bad)).map { it.messageKey })
        }
    }

    @Test
    fun `the modmap is parsed, kept by every transform, and the Turkish layouts carry shift on i`() {
        val turkish = LayoutLoader.parse(File(assets, "turkish_q.json").readText())
        assertEquals('İ'.code, turkish.modmap.shifted('i'.code))
        assertEquals('I'.code, turkish.modmap.shifted('ı'.code))
        assertEquals('A'.code, turkish.modmap.shifted('a'.code))
        val composed = turkish.withNumberRow().withAccents(mapOf('a' to "â"), "tr-TR")
            .withModifierRow(listOf(KeyCodes.ESCAPE), atBottom = false).withoutEmojiKey()
        assertEquals('İ'.code, composed.modmap.shifted('i'.code))
        val custom = LayoutChoice.resolve(
            "qwerty", mapOf("qwerty" to "custom-1"),
            listOf(CustomLayout("custom-1", "Mine", "und", qwerty().replaceFirst("{", "{\"modmap\": {\"ctrl\": {\"q\": \"w\"}}, "))),
        ) { LayoutLoader.parse(File(assets, "$it.json").readText()) }
        assertEquals('w'.code, custom.modmap.forControl('q'.code))
        assertEquals('e'.code, custom.modmap.forControl('e'.code))
        assertSame(Modmap.NONE, LayoutLoader.parse(qwerty()).modmap)
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
    fun `in landscape a subtype draws its landscape choice, else its upright one`() {
        val upright = mapOf("qwerty" to "custom-1", "azerty" to "custom-2")
        val landscape = mapOf("qwerty" to "qwerty")
        assertEquals(upright, LayoutChoice.forOrientation(upright, landscape, landscape = false))
        assertEquals(
            mapOf("qwerty" to "qwerty", "azerty" to "custom-2"),
            LayoutChoice.forOrientation(upright, landscape, landscape = true),
        )
        val loads = mutableListOf<String>()
        LayoutChoice.resolve("qwerty", LayoutChoice.forOrientation(upright, landscape, landscape = true), emptyList()) {
            loads += it
            KeyboardLayout.fallbackQwerty()
        }
        assertEquals(listOf("qwerty"), loads)
    }

    @Test
    fun `the layout switch walks subtypes in the list's order, the ones it does not name last`() {
        assertEquals(listOf(2, 0, 1, 3), LayoutChoice.cycleOrder(listOf("qwerty", "custom-1", "russian", "greek"), listOf("russian", "qwerty", "custom-1")))
        assertEquals(listOf(0, 1), LayoutChoice.cycleOrder(listOf("qwerty", "azerty"), emptyList()))
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
