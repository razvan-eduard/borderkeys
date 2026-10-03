// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Which icons turn round in a right-to-left layout: the ones that follow the text's direction. */
class IconMirroringTest {

    private val drawables = File("src/main/res/drawable")

    private fun mirrored(): Set<String> =
        drawables.listFiles { file -> file.extension == "xml" }.orEmpty()
            .filter { it.readText().contains("android:autoMirrored=\"true\"") }
            .map { it.nameWithoutExtension }
            .toSet()

    @Test
    fun `the icons that follow the text's direction mirror, and the ones naming a side of the screen do not`() {
        assertEquals(
            setOf(
                "bk_action_undo", "bk_action_redo", "bk_action_newline", "bk_action_tab",
                "bk_action_cursor_start", "bk_action_cursor_end",
                "bk_action_select_to_line_start", "bk_action_select_to_line_end",
                "bk_action_delete_word", "bk_action_delete_word_forward",
                "bk_composer_back", "bk_composer_forward", "bk_composer_bullets", "bk_composer_shorten",
                "bk_composer_insert", "bk_icon_chat",
            ),
            mirrored(),
        )
    }
}
