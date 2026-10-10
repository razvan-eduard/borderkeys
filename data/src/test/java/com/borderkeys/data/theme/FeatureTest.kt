// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feature table: what each feature sits under, and what the settings alone switch on. */
class FeatureTest {

    /** Every clipboard switch on, and a screenshot folder so Remember screenshots can be on. */
    private val allOn = KeyboardPreferences(
        clipboardEnabled = true,
        clipboardSuggestion = true,
        clipboardImages = true,
        screenshotSuggestion = KeyboardPreferences.SCREENSHOT_SUGGESTION_BESIDE,
        screenshotFolder = "content://folder",
        screenshotCascade = true,
        swipeEnabled = true,
        showSuggestionStrip = true,
    )

    @Test
    fun `with every switch on, every feature is on`() {
        for (feature in Feature.entries) {
            assertTrue(feature.name, feature.on(allOn))
        }
    }

    @Test
    fun `Remember what you copy off switches off everything under it, whatever their own switches say`() {
        val off = allOn.copy(clipboardEnabled = false)
        for (feature in listOf(
            Feature.CLIPBOARD_HISTORY, Feature.CLIPBOARD_OFFER, Feature.PHOTOS,
            Feature.SCREENSHOTS, Feature.SCREENSHOT_OFFER, Feature.SCREENSHOT_CASCADE,
        )) {
            assertFalse(feature.name, feature.on(off))
        }
        assertTrue(Feature.SWIPE.on(off))
        assertTrue(Feature.STRIP.on(off))
    }

    @Test
    fun `the screenshot offer needs Remember screenshots as well as Offer what you copied`() {
        assertFalse(Feature.SCREENSHOT_OFFER.on(allOn.copy(screenshotSuggestion = KeyboardPreferences.SCREENSHOT_SUGGESTION_OFF)))
        assertFalse(Feature.SCREENSHOT_OFFER.on(allOn.copy(clipboardSuggestion = false)))
        assertTrue(Feature.SCREENSHOTS.on(allOn.copy(clipboardSuggestion = false)))
    }

    @Test
    fun `cascade screenshots needs its switch, the screenshot offer and a time limit`() {
        assertTrue(Feature.SCREENSHOT_CASCADE.on(allOn))
        assertFalse(Feature.SCREENSHOT_CASCADE.on(allOn.copy(screenshotCascade = false)))
        assertFalse("not with the offer window off", Feature.SCREENSHOT_CASCADE.on(allOn.copy(imageOfferMinutes = 0)))
        assertFalse(Feature.SCREENSHOT_CASCADE.on(allOn.copy(clipboardSuggestion = false)))
        assertFalse(Feature.SCREENSHOT_CASCADE.on(allOn.copy(screenshotSuggestion = KeyboardPreferences.SCREENSHOT_SUGGESTION_OFF)))
    }

    @Test
    fun `each feature sits where the settings screen shows it`() {
        assertEquals(null, Feature.CLIPBOARD_HISTORY.parent)
        assertEquals(Feature.CLIPBOARD_HISTORY, Feature.CLIPBOARD_OFFER.parent)
        assertEquals(Feature.CLIPBOARD_HISTORY, Feature.PHOTOS.parent)
        assertEquals(Feature.CLIPBOARD_HISTORY, Feature.SCREENSHOTS.parent)
        assertEquals(Feature.SCREENSHOTS, Feature.SCREENSHOT_OFFER.parent)
        assertEquals(Feature.SCREENSHOT_OFFER, Feature.SCREENSHOT_CASCADE.parent)
        assertEquals(null, Feature.SWIPE.parent)
        assertEquals(null, Feature.STRIP.parent)
    }

    @Test
    fun `each feature names what it needs from the field`() {
        assertEquals(FieldRequirement.SWIPE, Feature.SWIPE.needs)
        assertEquals(FieldRequirement.ON_BY_HAND, Feature.STRIP.needs)
        for (feature in listOf(
            Feature.CLIPBOARD_HISTORY, Feature.CLIPBOARD_OFFER, Feature.PHOTOS,
            Feature.SCREENSHOTS, Feature.SCREENSHOT_OFFER, Feature.SCREENSHOT_CASCADE,
        )) {
            assertEquals(feature.name, FieldRequirement.CLIPBOARD, feature.needs)
        }
    }
}
