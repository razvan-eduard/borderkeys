// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.borderkeys.typing.FieldEditor
import com.borderkeys.typing.FieldText

/** [FieldEditor] over the input connection [connection] gives, read afresh at each call. */
internal class ConnectionFieldEditor(private val connection: () -> InputConnection?) : FieldEditor {

    override fun textBeforeCursor(length: Int): CharSequence? =
        connection()?.getTextBeforeCursor(length, 0)

    override fun textAfterCursor(length: Int): CharSequence? =
        connection()?.getTextAfterCursor(length, 0)

    override fun extractedText(maxChars: Int): FieldText? {
        val request = ExtractedTextRequest().apply { hintMaxChars = maxChars }
        val extracted = connection()?.getExtractedText(request, 0) ?: return null
        return FieldText(
            extracted.text, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd,
        )
    }

    override fun cursorCapsMode(modes: Int): Int = connection()?.getCursorCapsMode(modes) ?: 0

    override fun beginBatchEdit() {
        connection()?.beginBatchEdit()
    }

    override fun endBatchEdit() {
        connection()?.endBatchEdit()
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        connection()?.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        connection()?.commitText(text, newCursorPosition)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int) {
        connection()?.setComposingRegion(start, end)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
        connection()?.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int) {
        connection()?.setSelection(start, end)
    }

    override fun performEditorAction(actionId: Int) {
        connection()?.performEditorAction(actionId)
    }

    override fun commitContent(uri: String, mimeType: String, label: String?): Boolean {
        val connection = connection() ?: return false
        val info = InputContentInfo(
            android.net.Uri.parse(uri),
            android.content.ClipDescription(label, arrayOf(mimeType)),
        )
        return connection.commitContent(
            info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }
}
