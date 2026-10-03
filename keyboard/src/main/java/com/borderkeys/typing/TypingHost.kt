// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.predict.Candidate

/** What the typing flow asks of the keyboard's views and of the input method around it. */
interface TypingHost {
    /** Whether the keyboard's views exist. */
    val viewAttached: Boolean

    /** The word slots the suggestion strip has, or null when there is no strip. */
    fun stripWordSlots(): Int?

    /** Shows [row] on the strip, [typedIndex] and [appliedIndex] marked, -1 for none. */
    fun showSuggestions(row: List<Candidate>, typedIndex: Int, appliedIndex: Int)

    fun clearStrip()

    /** The strip slot holding the typed word, or null when there is no strip. */
    fun stripTypedIndex(): Int?

    /** Takes the strip out of a held word's actions, when it shows them. */
    fun clearStripActions()

    /** Whether the strip treats the field as empty. */
    fun setEditorEmpty(empty: Boolean)

    /** Shows shift as [state], a [com.borderkeys.ime.ShiftState] value. */
    fun showShiftState(state: Int)

    /** The word was reset: the strip lets go of a held word. */
    fun onWordReset()

    /**
     * A swipe of [count] samples was lifted and is being decoded: its path goes to the stats, and
     * the strip says so when the answer is late.
     */
    fun onSwipeLifted(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int)

    /** The swipe's decode answered. */
    fun onSwipeAnswered()

    /** A decode of [candidates] words was applied, for the stats. */
    fun onSwipeDecoded(candidates: Int)

    /** A decode of [candidates] words was applied; a debuggable build logs its timings. */
    fun traceSwipeDecode(candidates: Int)

    /** Re-reads a private field's text into the strip while it is being shown. */
    fun refreshPrivateReveal()

    fun playEffect(event: EffectEvent, word: String)

    /** Shows the catalogue text [key] on the strip for a moment. */
    fun showNotice(key: String)

    /** Runs the quick action with [id], as a tap on its button would. */
    fun runQuickAction(id: Int)

    /**
     * Shows what is pending: the dead key [deadCode] drawn pressed, 0 for none, [locked] when it
     * stays; the compose sequence typed so far, null for none.
     */
    fun showAccent(deadCode: Int, locked: Boolean, composing: String?)

    /** Offers [replacements] on the language-revert panel. */
    fun offerLanguageReplacements(replacements: List<LanguageSwitchCorrector.Replacement>)

    /**
     * Forgets a personal word, with the pairs and triples it is part of; a word the personal
     * dictionary does not hold is blocked instead when [blockWhenNotPersonal].
     */
    fun forgetWord(word: String, blockWhenNotPersonal: Boolean)

    /** The language tag of the input-method subtype in use. */
    fun subtypeTag(): String

    /** Whether control or alt is armed from the modifier row. */
    val modifiersArmed: Boolean

    fun releaseModifiers()

    /**
     * Sends [keyCode] as a pressed and released hardware key with [metaState] and the armed
     * modifiers, and releases them.
     */
    fun sendPhysicalKey(keyCode: Int, metaState: Int)

    fun postDelayed(action: Runnable, delayMillis: Long)

    fun removeCallbacks(action: Runnable)
}
