// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** An icon that points back or forward in reading order is mirrored in a right-to-left layout. */
class MirroredIconsTest {

    @Test
    fun `back, forward and delete icons are auto-mirrored`() {
        val unmirrored = File("src/main/res/drawable").listFiles()!!
            .filter { file -> DIRECTIONAL.any { file.nameWithoutExtension.endsWith(it) } }
            .filterNot { it.readText().contains("""android:autoMirrored="true"""") }
            .map { it.name }
        assertEquals(emptyList<String>(), unmirrored)
    }

    private companion object {
        val DIRECTIONAL = listOf("_back", "_forward", "_next", "_previous", "_delete_word")
    }
}
