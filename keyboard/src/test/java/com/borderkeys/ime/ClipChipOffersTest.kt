// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipChipOffersTest {

    @Test
    fun `a clip copied while the keyboard is open is offered again after it closes`() {
        val offers = ClipChipOffers()
        offers.copied("north gate")
        offers.shown("north gate")
        offers.keyboardClosed(once = true)
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `a clip offered through a whole session is withheld once the keyboard closes`() {
        val offers = ClipChipOffers()
        offers.copied("north gate")
        offers.shown("north gate")
        offers.keyboardClosed(once = true)
        offers.shown("north gate")
        offers.keyboardClosed(once = true)
        assertFalse(offers.mayShow("north gate"))
    }

    @Test
    fun `a clip copied while the keyboard was closed is withheld after the session that offered it`() {
        val offers = ClipChipOffers()
        offers.shown("second clip")
        offers.keyboardClosed(once = true)
        assertFalse(offers.mayShow("second clip"))
    }

    @Test
    fun `a used clip is withheld at once`() {
        val offers = ClipChipOffers()
        offers.copied("north gate")
        offers.shown("north gate")
        offers.used("north gate", once = true)
        assertFalse(offers.mayShow("north gate"))
    }

    @Test
    fun `a new copy lifts what was withheld`() {
        val offers = ClipChipOffers()
        offers.shown("north gate")
        offers.keyboardClosed(once = true)
        offers.copied("south gate")
        assertTrue(offers.mayShow("north gate"))
        assertTrue(offers.mayShow("south gate"))
    }

    @Test
    fun `with the setting off nothing is withheld`() {
        val offers = ClipChipOffers()
        offers.shown("north gate")
        offers.used("north gate", once = false)
        offers.keyboardClosed(once = false)
        assertTrue(offers.mayShow("north gate"))
    }

    @Test
    fun `no clip is never shown`() {
        assertFalse(ClipChipOffers().mayShow(null))
    }
}
