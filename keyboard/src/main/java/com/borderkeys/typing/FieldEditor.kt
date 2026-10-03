// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The text field the keyboard types into, as the typing flow reads and edits it. */
interface FieldEditor {
    /** Up to [length] characters before the caret, or null when the field does not answer. */
    fun textBeforeCursor(length: Int): CharSequence?

    /** Up to [length] characters after the caret, or null when the field does not answer. */
    fun textAfterCursor(length: Int): CharSequence?

    /**
     * The field's text around the caret, at most [maxChars] of it (0 lets the field choose), with
     * the selection; null when the field does not report it.
     */
    fun extractedText(maxChars: Int): FieldText?

    /** The capital modes of [modes] that apply at the caret. */
    fun cursorCapsMode(modes: Int): Int

    fun beginBatchEdit()

    fun endBatchEdit()

    fun setComposingText(text: CharSequence, newCursorPosition: Int)

    fun commitText(text: CharSequence, newCursorPosition: Int)

    fun finishComposingText()

    fun setComposingRegion(start: Int, end: Int)

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int)

    fun setSelection(start: Int, end: Int)

    fun performEditorAction(actionId: Int)

    /**
     * Hands the field the content at [uri], of [mimeType], described by [label], with read access
     * for the insertion; false when the field did not take it.
     */
    fun commitContent(uri: String, mimeType: String, label: String?): Boolean
}

/** What [FieldEditor.extractedText] reports: a window of the field's text and the selection. */
class FieldText(
    /** The text of the window; null when the field reported none. */
    val text: CharSequence?,
    /** Where the window starts in the field. */
    val startOffset: Int,
    /** The selection, in the window's offsets; negative when the field reported none. */
    val selectionStart: Int,
    val selectionEnd: Int,
)
