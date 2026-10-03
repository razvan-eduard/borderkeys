// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.borderkeys.data.ClipMedia
import com.borderkeys.data.ClipMediaStore
import com.borderkeys.data.DataGraph
import com.borderkeys.ime.ClipMediaProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** A copied image's bytes on the device: encrypted on disk, read back whole, served to a field. */
@RunWith(AndroidJUnit4::class)
class ClipMediaStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        for (x in 0 until 640) {
            for (y in 0 until 400) {
                bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun theBytesAreEncryptedOnDiskAndReadBackWhole() {
        val store = ClipMediaStore(context)
        val bytes = picture()
        val stored = store.store(bytes, "image/png")
        assertNotNull(stored)
        stored!!
        assertEquals(bytes.size.toLong(), stored.sizeBytes)
        assertTrue(ClipMedia.isConfined(stored.name))

        val file = File(File(context.filesDir, ClipMedia.DIRECTORY), stored.name)
        assertTrue(file.isFile)
        val onDisk = file.readBytes()
        assertFalse("the PNG signature must not be on disk", onDisk.copyOfRange(0, 4).contentEquals(bytes.copyOfRange(0, 4)))
        assertTrue(bytes.contentEquals(store.read(stored.name)))

        val thumbnail = stored.thumbnail
        assertNotNull(thumbnail)
        assertTrue("${thumbnail!!.size} bytes", thumbnail.size <= ClipMedia.THUMBNAIL_MAX_BYTES)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.size)
        assertEquals(ClipMedia.THUMBNAIL_PX, decoded.width)
        assertEquals(ClipMedia.THUMBNAIL_PX, decoded.height)

        store.sweep(emptySet())
        assertFalse(file.exists())
        assertNull(store.read(stored.name))
    }

    @Test
    fun theProviderServesAStoredImageToTheFieldAndNothingElse() {
        DataGraph.install(context.applicationContext)
        val bytes = picture()
        runBlocking {
            DataGraph.themes.updatePreferences { it.copy(clipboardEnabled = true, clipboardImages = true) }
            assertNull(DataGraph.clipboard.rememberImageBytes(bytes, "image/png"))
        }
        val entry = runBlocking { DataGraph.clipboard.recent(10) }.first { it.hasMedia }
        val uri = ClipMediaProvider.uriFor(context, entry.mediaFile!!)
        assertEquals("image/png", context.contentResolver.getType(uri))
        val served = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(bytes.contentEquals(served))
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)))
        }
        val outside = android.net.Uri.parse(
            "content://${ClipMediaProvider.authority(context)}/${ClipMediaProvider.ROOT}/../x.png",
        )
        assertNull(context.contentResolver.getType(outside))
        assertNull(runCatching { context.contentResolver.openInputStream(outside) }.getOrNull())

        runBlocking {
            DataGraph.clipboard.delete(entry.id)
            DataGraph.themes.updatePreferences { it.copy(clipboardImages = false) }
        }
        assertFalse(File(File(context.filesDir, ClipMedia.DIRECTORY), entry.mediaFile!!).exists())
    }
}
