// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** One field, from the moment it starts until the next one does. */
data class FieldSession(
    /** Counts the fields started; an answer about an older field is dropped. */
    val generation: Int,
    val policy: FieldPolicy,
    /** The field holds an e-mail address or a URI. */
    val addressField: Boolean,
    /** The field is a terminal: commits show at once and deletions go as key events. */
    val terminalField: Boolean,
    /** The field's input type, in [android.text.InputType]'s bits. */
    val inputType: Int = 0,
    /** The field's action and flags, in [android.view.inputmethod.EditorInfo.imeOptions]' bits. */
    val imeOptions: Int = 0,
    /** The capital modes that applied when the field started, for when it cannot be asked. */
    val initialCapsMode: Int = 0,
    /** The field described itself; without that, shift is never set automatically. */
    val described: Boolean = false,
    /** The content types the field takes through commitContent, as it declared them. */
    val contentMimeTypes: List<String> = emptyList(),
) {
    /** Whether the field takes content of [mimeType]: an exact type, its family, or anything. */
    fun acceptsContent(mimeType: String): Boolean = contentMimeTypes.any { accepted ->
        accepted == "*/*" || accepted.equals(mimeType, ignoreCase = true) ||
            (accepted.endsWith("/*") && mimeType.startsWith(accepted.dropLast(1), ignoreCase = true))
    }

    /**
     * Whether a photo of [mimeType], copied or a screenshot, may be offered and pasted here: the
     * clipboard is allowed here and the field takes that type.
     */
    fun takesPhoto(mimeType: String): Boolean = policy.clipboardAllowed && acceptsContent(mimeType)

    companion object {
        /** Before any field has started. */
        val NONE = FieldSession(
            generation = 0,
            policy = FieldPolicy.NONE,
            addressField = false,
            terminalField = false,
        )
    }
}
