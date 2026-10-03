// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each store listing, for both applications and every locale, has its three files, with the title
 * and the short description within their limits.
 */
class StoreListingTest {

    private val root = File("../fastlane")

    private fun locales(application: String): List<File> =
        File(root, "$application/metadata/android").listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }

    @Test
    fun `every listing has a title, a short and a full description within the limits`() {
        val problems = mutableListOf<String>()
        for (application in APPLICATIONS) {
            val found = locales(application)
            assertTrue("$application has no listing", found.isNotEmpty())
            for (locale in found) {
                for ((name, limit) in FILES) {
                    val file = File(locale, name)
                    if (!file.isFile) {
                        problems += "$application/${locale.name} has no $name"
                        continue
                    }
                    val text = file.readText().trimEnd('\n')
                    val length = text.codePointCount(0, text.length)
                    if (text.isBlank()) problems += "$application/${locale.name}/$name is blank"
                    if (length > limit) problems += "$application/${locale.name}/$name is $length long, over $limit"
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun `both applications are listed in the same locales`() {
        assertEquals(locales(APPLICATIONS[0]).map { it.name }, locales(APPLICATIONS[1]).map { it.name })
    }

    private companion object {
        val APPLICATIONS = listOf("com.borderkeys", "com.borderkeys.plus")
        val FILES = listOf("title.txt" to 50, "short_description.txt" to 80, "full_description.txt" to Int.MAX_VALUE)
    }
}
