// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/** What the field being typed into has to allow for something to run there. */
enum class FieldRequirement {
    /** Runs in any field. */
    NONE,

    /** Reads or writes the clipboard or its history. */
    CLIPBOARD,

    /** Hands the field's text to the draft box or the assistant. */
    ASSISTANT,

    /** Decodes a swipe across the letters. */
    SWIPE,

    /** Changes what the keyboard does in the field; a field private by its kind has nothing to change. */
    PERSONAL_FIELD,

    /** Not while the user has switched the keyboard's features off by hand. */
    ON_BY_HAND,
}

/**
 * A feature the settings switch on and a field may refuse: what it needs from the field, the
 * setting that switches it on, what else it needs from the settings, and the feature it sits
 * under, which has to be on too. The settings screens read [on]; the keyboard reads the field
 * policy's own `on`, which adds what the field allows.
 */
enum class Feature(val needs: FieldRequirement) {
    /** Clipboard history. */
    CLIPBOARD_HISTORY(FieldRequirement.CLIPBOARD),

    /** Offer what you copied: the chip on the strip. */
    CLIPBOARD_OFFER(FieldRequirement.CLIPBOARD),

    /** Remember photos: a copied photo kept, offered and pasted. */
    PHOTOS(FieldRequirement.CLIPBOARD),

    /** A copied photo offered on the strip. */
    PHOTO_OFFER(FieldRequirement.CLIPBOARD),

    /** Remember screenshots: the folder watched and each new screenshot kept. */
    SCREENSHOTS(FieldRequirement.CLIPBOARD),

    /** The newest screenshot offered on the strip. */
    SCREENSHOT_OFFER(FieldRequirement.CLIPBOARD),

    /** Cascade screenshots: those within the offer window offered in turn, the oldest first. */
    SCREENSHOT_CASCADE(FieldRequirement.CLIPBOARD),

    /** Swipe typing. */
    SWIPE(FieldRequirement.SWIPE),

    /** The suggestion strip shown. */
    STRIP(FieldRequirement.ON_BY_HAND),
    ;

    /** The feature this one sits under, which has to be on for it to be. */
    val parent: Feature?
        get() = when (this) {
            CLIPBOARD_OFFER, PHOTOS, SCREENSHOTS -> CLIPBOARD_HISTORY
            PHOTO_OFFER -> PHOTOS
            SCREENSHOT_OFFER -> SCREENSHOTS
            SCREENSHOT_CASCADE -> SCREENSHOT_OFFER
            CLIPBOARD_HISTORY, SWIPE, STRIP -> null
        }

    /** Whether this feature's own setting is on, its parents aside. */
    fun switchedOn(preferences: KeyboardPreferences): Boolean = when (this) {
        CLIPBOARD_HISTORY -> preferences.clipboardEnabled
        CLIPBOARD_OFFER -> preferences.clipboardSuggestion
        PHOTOS -> preferences.photosRemembered
        PHOTO_OFFER -> preferences.clipboardSuggestion
        SCREENSHOTS -> preferences.screenshotsRemembered
        SCREENSHOT_OFFER -> preferences.clipboardSuggestion
        SCREENSHOT_CASCADE -> preferences.screenshotCascade
        SWIPE -> preferences.swipeEnabled
        STRIP -> preferences.showSuggestionStrip
    }

    /**
     * Whether this feature would be on were its own setting on: what else it needs from the
     * settings (an offer window that is not off, for the cascade) and its parent on.
     */
    fun canSwitch(preferences: KeyboardPreferences): Boolean {
        val needed = when (this) {
            SCREENSHOT_CASCADE -> preferences.imageOfferLimited
            else -> true
        }
        return needed && (parent?.on(preferences) ?: true)
    }

    /** Whether the settings switch this feature on: its own setting, what else it needs, every parent. */
    fun on(preferences: KeyboardPreferences): Boolean = switchedOn(preferences) && canSwitch(preferences)
}
