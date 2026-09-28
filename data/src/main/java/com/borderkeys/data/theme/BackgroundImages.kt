// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * The picture behind the keys: copied, downsampled, into this application's own directory, and
 * read back from there.
 */
object BackgroundImages {

    private const val DIRECTORY = "backgrounds"

    /** The widest the stored copy may be, in pixels. */
    const val MAX_WIDTH = 1440
    const val MAX_HEIGHT = 1440

    /** Where a name resolves to. Private to the application; nothing else can read it. */
    fun fileFor(context: Context, name: String): File =
        File(File(context.filesDir, DIRECTORY), name)

    /**
     * Copies a chosen picture in, downsampled, and returns the name to store, or null when it
     * could not be read or decoded.
     */
    fun import(context: Context, uri: Uri, now: Long = System.currentTimeMillis()): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull() ?: return null

        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        // A new name every time; the keyboard's process may hold the old file open.
        val name = "background-$now.webp"
        val written = runCatching {
            File(directory, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it)
            }
        }.getOrDefault(false)
        bitmap.recycle()
        if (!written) {
            return null
        }
        // Everything else in the directory is a picture nothing points at any more.
        directory.listFiles()?.forEach { if (it.name != name) it.delete() }
        return name
    }

    /** Loads the stored picture, or null when the name points at nothing readable. */
    fun load(context: Context, name: String): Bitmap? {
        if (name.isEmpty()) {
            return null
        }
        val file = fileFor(context, name)
        if (!file.isFile) {
            return null
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun forget(context: Context) {
        File(context.filesDir, DIRECTORY).listFiles()?.forEach { it.delete() }
    }

    /** The power of two, as `inSampleSize` takes it, that brings a picture under the bound. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_WIDTH || height / sample > MAX_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    private const val QUALITY = 85
}
