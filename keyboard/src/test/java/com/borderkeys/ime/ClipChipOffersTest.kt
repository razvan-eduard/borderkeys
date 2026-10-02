// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipChipOffersTest {

    @Test
    fun `a clip copied while the keyboard is open is offered in the next app`() {
        val offers = ClipChipOffers()
        offers.copied("north gate", BROWSER)
        offers.shown("north gate", BROWSER)
        offers.keyboardClosed(once = true, BROWSER)
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `sessions in the app a clip came from never spend it`() {
        val offers = ClipChipOffers()
        offers.copied("north gate", BROWSER)
        repeat(3) {
            offers.shown("north gate", BROWSER)
            offers.keyboardClosed(once = true, BROWSER)
        }
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `a clip copied while the keyboard was hidden in an app is still offered in the next one`() {
        val offers = ClipChipOffers()
        offers.shown(null, CHAT)
        offers.keyboardClosed(once = true, CHAT)
        offers.shown("north gate", CHAT)
        offers.keyboardClosed(once = true, CHAT)
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `a clip offered in another app is withheld once that session closes`() {
        val offers = ClipChipOffers()
        offers.copied("north gate", BROWSER)
        offers.shown("north gate", BROWSER)
        offers.keyboardClosed(once = true, BROWSER)
        offers.shown("north gate", NOTES)
        offers.keyboardClosed(once = true, NOTES)
        assertFalse(offers.mayShow("north gate"))
    }

    @Test
    fun `a clip copied with no keyboard open is withheld after the session that offered it`() {
        val offers = ClipChipOffers()
        offers.keyboardClosed(once = true, CHAT)
        offers.shown("second clip", NOTES)
        offers.keyboardClosed(once = true, NOTES)
        assertFalse(offers.mayShow("second clip"))
    }

    @Test
    fun `a used clip is withheld at once`() {
        val offers = ClipChipOffers()
        offers.copied("north gate", BROWSER)
        offers.shown("north gate", BROWSER)
        offers.used("north gate", once = true)
        assertFalse(offers.mayShow("north gate"))
    }

    @Test
    fun `a new copy lifts what was withheld`() {
        val offers = ClipChipOffers()
        offers.shown("north gate", NOTES)
        offers.keyboardClosed(once = true, NOTES)
        offers.copied("south gate", NOTES)
        assertTrue(offers.mayShow("north gate"))
        assertTrue(offers.mayShow("south gate"))
    }

    @Test
    fun `with the setting off nothing is withheld`() {
        val offers = ClipChipOffers()
        offers.shown("north gate", NOTES)
        offers.used("north gate", once = false)
        offers.keyboardClosed(once = false, NOTES)
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `no clip is never shown`() {
        assertFalse(ClipChipOffers().mayShow(null))
    }

    private companion object {
        const val BROWSER = "org.example.browser"
        const val CHAT = "org.example.chat"
        const val NOTES = "org.example.notes"
    }
}
