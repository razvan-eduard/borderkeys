// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The images in the screenshot folder the user chose, read through the document tree grant they
 * gave for it and nothing else.
 */
class ScreenshotFolder(private val resolver: ContentResolver) {

    /** An image of the folder: its document URI, type and last change, in epoch milliseconds. */
    class Shot(val uri: Uri, val mimeType: String, val modifiedMillis: Long) {
        companion object {
            /** The order screenshots were taken in: by time, then by URI. */
            val ORDER: Comparator<Shot> = compareBy<Shot> { it.modifiedMillis }.thenBy { it.uri.toString() }
        }
    }

    /** The URI the folder's children are listed under, and changes to them reported. */
    fun childrenUri(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    /** The newest image directly in [tree], or null when there is none or the grant is gone. */
    fun newest(tree: Uri): Shot? = images(tree).lastOrNull()

    /**
     * The images directly in [tree], the oldest first, two of the same time in the order of their
     * URIs; empty when there is none or the grant is gone.
     */
    fun images(tree: Uri): List<Shot> {
        val children = childrenUri(tree) ?: return emptyList()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val shots = runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val found = ArrayList<Shot>()
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val mimeType = cursor.getString(1) ?: continue
                    if (!mimeType.startsWith("image/")) {
                        continue
                    }
                    found += Shot(
                        DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                        mimeType,
                        cursor.getLong(2),
                    )
                }
                found
            }
        }.getOrNull() ?: return emptyList()
        return shots.sortedWith(Shot.ORDER)
    }
}
