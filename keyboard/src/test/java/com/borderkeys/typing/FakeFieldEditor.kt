// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType

/**
 * A text field in memory, edited the way the platform's input connection edits one. After each
 * outermost batch edit that moved the selection or the composing region it queues a selection
 * report, which the test delivers, as the platform posts one to the input method.
 */
internal class FakeFieldEditor : FieldEditor {

    /** What a selection report carries: the selection, and the composing region or -1. */
    data class Report(
        val selectionStart: Int,
        val selectionEnd: Int,
        val composingStart: Int,
        val composingEnd: Int,
    )

    private val content = StringBuilder()

    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set
    var composingStart = -1
        private set
    var composingEnd = -1
        private set

    private var batchDepth = 0
    private var lastReported = current()
    private val reports = ArrayDeque<Report>()

    /** The editor actions performed, in order. */
    val actions = mutableListOf<Int>()

    /** The field's text. */
    val text: String get() = content.toString()

    /** The composing region's text, or null when there is none. */
    val composingText: String?
        get() = if (composingStart < 0) null else content.substring(composingStart, composingEnd)

    /** Replaces the field's text with [initial], the caret after it; nothing is reported. */
    fun reset(initial: String = "") {
        content.setLength(0)
        content.append(initial)
        selectionStart = initial.length
        selectionEnd = initial.length
        composingStart = -1
        composingEnd = -1
        batchDepth = 0
        reports.clear()
        lastReported = current()
    }

    /** The oldest report not yet delivered, taken off the queue, or null. */
    fun takeReport(): Report? = reports.removeFirstOrNull()

    override fun textBeforeCursor(length: Int): CharSequence? {
        val a = minOf(selectionStart, selectionEnd)
        if (a <= 0) {
            return ""
        }
        return content.substring(a - minOf(length, a), a)
    }

    override fun textAfterCursor(length: Int): CharSequence? {
        val b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        return content.substring(b, minOf(content.length, b + length))
    }

    override fun extractedText(maxChars: Int): FieldText =
        FieldText(content.toString(), 0, selectionStart, selectionEnd)

    override fun cursorCapsMode(modes: Int): Int =
        CapsMode.at(content, minOf(selectionStart, selectionEnd), modes)

    override fun beginBatchEdit() {
        batchDepth++
    }

    override fun endBatchEdit() {
        if (batchDepth == 0) {
            return
        }
        batchDepth--
        if (batchDepth == 0) {
            val now = current()
            if (now != lastReported) {
                lastReported = now
                reports.addLast(now)
            }
        }
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) = edit {
        replace(text.toString(), newCursorPosition, composing = true)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) = edit {
        replace(text.toString(), newCursorPosition, composing = false)
    }

    override fun finishComposingText() = edit {
        composingStart = -1
        composingEnd = -1
    }

    override fun setComposingRegion(start: Int, end: Int) = edit {
        val a = minOf(start, end).coerceIn(0, content.length)
        val b = maxOf(start, end).coerceIn(0, content.length)
        if (a == b) {
            composingStart = -1
            composingEnd = -1
        } else {
            composingStart = a
            composingEnd = b
        }
    }

    /** Deletes around the selection and the composing region together, never inside them. */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) = edit {
        var a = minOf(selectionStart, selectionEnd)
        var b = maxOf(selectionStart, selectionEnd)
        if (composingStart >= 0) {
            a = minOf(a, composingStart)
            b = maxOf(b, composingEnd)
        }
        var deleted = 0
        if (beforeLength > 0) {
            val start = (a - beforeLength).coerceAtLeast(0)
            if (a - start > 0) {
                delete(start, a)
                deleted = a - start
            }
        }
        if (afterLength > 0) {
            b -= deleted
            val end = minOf(b + afterLength, content.length)
            if (end - b > 0) {
                delete(b, end)
            }
        }
    }

    /** An offset beyond the text leaves the selection where it was. */
    override fun setSelection(start: Int, end: Int) = edit {
        val length = content.length
        if (start in 0..length && end in 0..length) {
            selectionStart = start
            selectionEnd = end
        }
    }

    override fun performEditorAction(actionId: Int) {
        actions += actionId
    }

    private inline fun edit(change: () -> Unit) {
        beginBatchEdit()
        change()
        endBatchEdit()
    }

    private fun current() = Report(selectionStart, selectionEnd, composingStart, composingEnd)

    /**
     * Writes [text] over the composing region, or over the selection when there is none, and
     * places the caret [newCursorPosition] from its end (above 0) or its start (0 or below).
     */
    private fun replace(text: String, newCursorPosition: Int, composing: Boolean) {
        val a: Int
        val b: Int
        if (composingStart >= 0) {
            a = composingStart
            b = composingEnd
        } else {
            a = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            b = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        }
        content.replace(a, b, text)
        val caret = if (newCursorPosition > 0) {
            a + text.length + newCursorPosition - 1
        } else {
            a + newCursorPosition
        }.coerceIn(0, content.length)
        selectionStart = caret
        selectionEnd = caret
        if (composing && text.isNotEmpty()) {
            composingStart = a
            composingEnd = a + text.length
        } else {
            composingStart = -1
            composingEnd = -1
        }
    }

    /** Deletes [start] to [end], moving the offsets after it back. */
    private fun delete(start: Int, end: Int) {
        content.delete(start, end)
        fun moved(offset: Int) = when {
            offset < 0 -> offset
            offset >= end -> offset - (end - start)
            offset > start -> start
            else -> offset
        }
        selectionStart = moved(selectionStart)
        selectionEnd = moved(selectionEnd)
        composingStart = moved(composingStart)
        composingEnd = moved(composingEnd)
        if (composingStart == composingEnd) {
            composingStart = -1
            composingEnd = -1
        }
    }
}

/** The capital modes at an offset, as [android.text.TextUtils.getCapsMode] reads them. */
internal object CapsMode {
    private const val CHARACTERS = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    private const val WORDS = InputType.TYPE_TEXT_FLAG_CAP_WORDS
    private const val SENTENCES = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    fun at(text: CharSequence, offset: Int, modes: Int): Int {
        if (offset < 0) {
            return 0
        }
        var mode = 0
        if (modes and CHARACTERS != 0) {
            mode = mode or CHARACTERS
        }
        if (modes and (WORDS or SENTENCES) == 0) {
            return mode
        }
        // Back over opening quotes and brackets.
        var i = offset
        while (i > 0) {
            val c = text[i - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) {
                break
            }
            i--
        }
        // The start of a paragraph, spaces aside.
        var j = i
        while (j > 0 && (text[j - 1] == ' ' || text[j - 1] == '\t')) {
            j--
        }
        if (j == 0 || text[j - 1] == '\n') {
            return mode or WORDS or SENTENCES
        }
        if (modes and SENTENCES == 0) {
            if (i != j) {
                mode = mode or WORDS
            }
            return mode
        }
        if (i == j) {
            return mode
        }
        // Back over closing quotes and brackets.
        while (j > 0) {
            val c = text[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) {
                break
            }
            j--
        }
        if (j > 0) {
            val c = text[j - 1]
            if (c == '.' || c == '?' || c == '!') {
                // A full stop after a word that holds another one ends an abbreviation.
                if (c == '.') {
                    var k = j - 2
                    while (k >= 0) {
                        val d = text[k]
                        if (d == '.') {
                            return mode
                        }
                        if (!Character.isLetter(d)) {
                            break
                        }
                        k--
                    }
                }
                return mode or SENTENCES
            }
        }
        return mode
    }
}
