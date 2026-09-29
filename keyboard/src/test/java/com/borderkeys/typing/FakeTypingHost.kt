// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.predict.Candidate

/** The keyboard's views as the typing flow sees them, recorded for the tests to read. */
internal class FakeTypingHost(private val clock: ManualClock) : TypingHost {

    override var viewAttached = true

    /** The word slots the strip has. */
    var wordSlots = WORD_SLOTS

    /** The strip's row and marks, as last shown. */
    var strip: List<Candidate> = emptyList()
        private set
    var typedIndex = -1
        private set
    var appliedIndex = -1
        private set

    var editorEmpty = true
        private set

    /** Shift as last shown, a [com.borderkeys.ime.ShiftState] value. */
    var shiftState = 0
        private set

    /** How many times a private field's text was re-read into the strip. */
    var privateRevealRefreshes = 0
        private set

    val effects = mutableListOf<Pair<EffectEvent, String>>()

    /** Each forgotten word, with whether it was to be blocked when not personal. */
    val forgotten = mutableListOf<Pair<String, Boolean>>()

    val offeredReplacements = mutableListOf<List<LanguageSwitchCorrector.Replacement>>()

    override var modifiersArmed = false

    /** Each hardware key sent, as its key code and meta state. */
    val physicalKeys = mutableListOf<Pair<Int, Int>>()

    /** What a hardware key does to the field; nothing unless a test sets it. */
    var onPhysicalKey: (keyCode: Int, metaState: Int) -> Unit = { _, _ -> }

    var subtype = "en-US"

    private class Posted(val action: Runnable, val dueAt: Long)

    private val posted = mutableListOf<Posted>()

    /** Runs the earliest posted runnable that is due; false when none is. */
    fun runDue(): Boolean {
        val due = posted.filter { it.dueAt <= clock.now }.minByOrNull { it.dueAt } ?: return false
        posted.remove(due)
        due.action.run()
        return true
    }

    override fun stripWordSlots(): Int? = if (viewAttached) wordSlots else null

    override fun showSuggestions(row: List<Candidate>, typedIndex: Int, appliedIndex: Int) {
        strip = row
        this.typedIndex = typedIndex
        this.appliedIndex = appliedIndex
    }

    override fun clearStrip() {
        strip = emptyList()
        typedIndex = -1
        appliedIndex = -1
    }

    override fun stripTypedIndex(): Int? = if (viewAttached) typedIndex else null

    override fun clearStripActions() = Unit

    override fun setEditorEmpty(empty: Boolean) {
        editorEmpty = empty
    }

    override fun showShiftState(state: Int) {
        shiftState = state
    }

    override fun dismissRing() = Unit

    override fun onWordReset() = Unit

    override fun refreshPrivateReveal() {
        privateRevealRefreshes++
    }

    override fun playEffect(event: EffectEvent, word: String) {
        effects += event to word
    }

    override fun offerLanguageReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        offeredReplacements += replacements
    }

    override fun forgetWord(word: String, blockWhenNotPersonal: Boolean) {
        forgotten += word to blockWhenNotPersonal
    }

    override fun subtypeTag(): String = subtype

    override fun releaseModifiers() {
        modifiersArmed = false
    }

    override fun sendPhysicalKey(keyCode: Int, metaState: Int) {
        physicalKeys += keyCode to metaState
        onPhysicalKey(keyCode, metaState)
        releaseModifiers()
    }

    override fun postDelayed(action: Runnable, delayMillis: Long) {
        posted += Posted(action, clock.now + delayMillis)
    }

    override fun removeCallbacks(action: Runnable) {
        posted.removeAll { it.action === action }
    }

    private companion object {
        /** More than any suggestion count the settings allow. */
        const val WORD_SLOTS = 16
    }
}
