// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.borderkeys.data.ClipMedia
import com.borderkeys.data.DataGraph
import kotlinx.coroutines.runBlocking
import java.io.FileOutputStream

/**
 * Serves a stored clipboard image to the field it is pasted into, decrypted into a pipe, under
 * `content://<package>.clipmedia/<partition>/<sha256>.<extension>`. Not exported: a field reads
 * it only through the grant commitContent hands it. Nothing is written through it.
 */
class ClipMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = nameOf(uri)?.let(ClipMedia::mimeTypeFor)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val name = nameOf(uri) ?: return null
        val context = context ?: return null
        DataGraph.install(context)
        val entry = runBlocking { DataGraph.clipboard.entryForMedia(name) } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> name.substringAfterLast('/')
                    OpenableColumns.SIZE -> entry.sizeBytes
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") {
            return null
        }
        val name = nameOf(uri) ?: return null
        val context = context ?: return null
        DataGraph.install(context)
        val bytes = DataGraph.clipboard.imageBytes(name) ?: return null
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread {
            runCatching {
                FileOutputStream(pipe[1].fileDescriptor).use { it.write(bytes) }
            }
            runCatching { pipe[1].close() }
        }.start()
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** The stored name a URI of ours carries, or null for any other path. */
    private fun nameOf(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2) {
            return null
        }
        val name = "${segments[0]}/${segments[1]}"
        return name.takeIf(ClipMedia::isConfined)
    }

    companion object {
        fun authority(context: Context): String = "${context.packageName}.clipmedia"

        /** The URI the field is handed for the stored image [mediaFile]. */
        fun uriFor(context: Context, mediaFile: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .path(mediaFile)
                .build()
    }
}
