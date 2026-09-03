// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * A text buffer that answers like an editor.
 *
 * The draft box needs a destination for keystrokes that is not the application's field. The
 * expensive way to build one is to teach the service to write to a second place -- and the
 * service's typing rules are about a hundred and twenty lines of interlocking policy: correct on
 * space, revert on backspace, two spaces for a full stop, a space after punctuation and none
 * before it, auto-shift, learning. Every one of those is written in terms of `InputConnection`.
 *
 * So the buffer is an `InputConnection`. `BaseInputConnection` in `fullEditor` mode keeps an
 * [Editable] and implements the primitives over it, including the one the correction path
 * depends on: committing text replaces the composing region if there is one and the selection
 * otherwise. Nothing about typing has to be written twice, and the suggestion strip goes on
 * predicting the draft's words without knowing anything has changed.
 *
 * `fullEditor = true` is load-bearing. In the other mode the base class turns every commit into
 * synthetic key events aimed at the target view, which is the opposite of what a scratch buffer
 * is for.
 */
class ComposerInputConnection(
    targetView: View,
    /** Called after anything changes the text or the caret, so the view can redraw. */
    private val onChanged: () -> Unit,
) : BaseInputConnection(targetView, true) {

    /**
     * What the field would say about itself, if it were a field.
     *
     * Allocated once and handed to the service in place of `currentInputEditorInfo`, which
     * describes the application's field and would be wrong here in two ways that matter.
     *
     * `IME_ACTION_NONE` is the one that would have hurt. The base class's `performEditorAction`
     * synthesises a real `KEYCODE_ENTER` and dispatches it through the input method manager, so
     * a draft opened over a chat box asking for `IME_ACTION_SEND` would have *sent the message*
     * the first time enter was pressed inside the box. With no action declared, the service's
     * enter handling takes the newline path, which is what a draft wants anyway.
     *
     * `CAP_SENTENCES` is the second: capitalisation is derived from what the field asked for,
     * and a draft is prose.
     */
    val editorInfo: EditorInfo = EditorInfo().apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_NONE
    }

    /**
     * The buffer itself, owned here rather than left to the base class.
     *
     * The base class creates one lazily and hands it back as a nullable, which every caller
     * would then have to answer for. Owning it makes the type honest -- there is always a
     * buffer, because the buffer is the whole object.
     */
    private val buffer: Editable = Editable.Factory.getInstance().newEditable("").also {
        Selection.setSelection(it, 0)
    }

    override fun getEditable(): Editable = buffer

    val text: Editable get() = buffer

    val selectionStart: Int get() = Selection.getSelectionStart(buffer)
    val selectionEnd: Int get() = Selection.getSelectionEnd(buffer)

    /** The whole buffer, as a plain string. */
    fun snapshot(): String = buffer.toString()

    /**
     * Replaces everything with [replacement] and puts the caret at its end.
     *
     * Written through the connection rather than into the [Editable] directly, so that a model's
     * answer arrives by the same path a keystroke does and the view is told about it once.
     */
    fun replaceAll(replacement: String) {
        finishComposingText()
        setSelection(0, buffer.length)
        commitText(replacement, 1)
    }

    /** Empties the buffer and seeds it, for a box that is being opened. */
    fun reset(seed: String) {
        replaceAll(seed)
    }

    // ---- the base class's gaps ----------------------------------------------------------------

    /**
     * The base class returns null here, which would leave the space-bar caret slide dead inside
     * the box: it reads the field's length to clamp the caret, gets nothing, and gives up.
     */
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
        ExtractedText().also {
            it.text = buffer
            it.startOffset = 0
            it.partialStartOffset = -1
            it.partialEndOffset = -1
            it.selectionStart = selectionStart
            it.selectionEnd = selectionEnd
        }

    /**
     * Refused rather than passed on.
     *
     * Nothing in this keyboard calls it, but the base class's own `performEditorAction` does, and
     * a key event escaping a scratch buffer would arrive in the application as a keypress the
     * user never made in that field.
     */
    override fun sendKeyEvent(event: KeyEvent?): Boolean = true

    override fun performEditorAction(actionCode: Int): Boolean = true

    override fun performContextMenuAction(id: Int): Boolean = false

    // ---- change notification ------------------------------------------------------------------
    //
    // Nine overrides that do nothing but tell the view. Not a TextWatcher: a watcher never sees
    // setSelection, which is a span change rather than a text change, and the caret moving is
    // exactly when the box has to be redrawn. Batch edits are the base class's no-ops, which is
    // fine here -- they exist to coalesce inter-process calls, and there is no process boundary
    // between this buffer and the view drawing it.

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        super.commitText(text, newCursorPosition).also { onChanged() }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        super.setComposingText(text, newCursorPosition).also { onChanged() }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        super.setComposingRegion(start, end).also { onChanged() }

    override fun finishComposingText(): Boolean =
        super.finishComposingText().also { onChanged() }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        super.deleteSurroundingText(beforeLength, afterLength).also { onChanged() }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { onChanged() }

    override fun setSelection(start: Int, end: Int): Boolean =
        super.setSelection(start, end).also { onChanged() }

    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean =
        super.commitCompletion(text).also { onChanged() }

    override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean =
        super.commitCorrection(info).also { onChanged() }
}
