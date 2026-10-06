// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The newest image in the screenshot folder the user chose, read through the document tree grant
 * they gave for it and nothing else.
 */
class ScreenshotFolder(private val resolver: ContentResolver) {

    /** An image of the folder: its document URI, type and last change, in epoch milliseconds. */
    class Shot(val uri: Uri, val mimeType: String, val modifiedMillis: Long)

    /** The URI the folder's children are listed under, and changes to them reported. */
    fun childrenUri(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    /** The newest image directly in [tree], or null when there is none or the grant is gone. */
    fun newest(tree: Uri): Shot? {
        val children = childrenUri(tree) ?: return null
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                var best: Shot? = null
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val mimeType = cursor.getString(1) ?: continue
                    if (!mimeType.startsWith("image/")) {
                        continue
                    }
                    val modified = cursor.getLong(2)
                    if (best == null || modified > best.modifiedMillis) {
                        best = Shot(
                            DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                            mimeType,
                            modified,
                        )
                    }
                }
                best
            }
        }.getOrNull()
    }
}
