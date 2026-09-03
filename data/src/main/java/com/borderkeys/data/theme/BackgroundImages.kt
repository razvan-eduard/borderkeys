// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * The picture behind the keys: where it is kept, and how it is read back.
 *
 * Copied in rather than referred to. A `content:` URI belongs to whichever application handed it
 * over and to the moment it was granted; the keyboard needs the picture every time it opens, in
 * a different process, possibly years later. A copy in this application's own directory is the
 * only version of that which keeps working.
 *
 * It is also bounded on the way in. A modern photograph is several thousand pixels across and
 * forty megabytes decoded, and the keyboard would be drawing it into a strip a few hundred
 * pixels tall on a phone that is also running everything else.
 */
object BackgroundImages {

    private const val DIRECTORY = "backgrounds"

    /**
     * The widest the stored copy is allowed to be.
     *
     * A keyboard is at most the width of the screen, so anything past this is detail nobody can
     * see paying for itself in memory every time the keyboard opens.
     */
    const val MAX_WIDTH = 1440
    const val MAX_HEIGHT = 1440

    /** Where a name resolves to. Private to the application; nothing else can read it. */
    fun fileFor(context: Context, name: String): File =
        File(File(context.filesDir, DIRECTORY), name)

    /**
     * Copies a chosen picture in, downsampled, and returns the name to store.
     *
     * Returns null when the picture could not be read or decoded -- a chooser can hand back a
     * URI for a file that has since gone, and a corrupt image is a corrupt image.
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
        // A new name every time rather than one that is overwritten. The keyboard runs in
        // another process and may be holding the old file open; writing over it underneath
        // would show whichever half had been written when it next drew.
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

    /**
     * The power of two that brings a picture under the bound.
     *
     * `inSampleSize` only understands powers of two, which is why this doubles rather than
     * dividing: it is the decoder's own arithmetic, and asking for anything else makes it
     * silently round anyway.
     */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_WIDTH || height / sample > MAX_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    private const val QUALITY = 85
}
