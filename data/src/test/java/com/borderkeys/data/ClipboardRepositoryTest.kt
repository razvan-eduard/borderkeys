// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.ClipboardDao
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.data.theme.KeyboardPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repository over a fake table and a fake media store: images as bytes, and the sweep. */
class ClipboardRepositoryTest {

    /** The table in memory, with the unique hash the real one has. */
    private class FakeDao : ClipboardDao {
        val rows = MutableStateFlow<List<ClipEntry>>(emptyList())
        private var nextId = 1L

        override fun observeLive(expiryCutoff: Long): Flow<List<ClipEntry>> =
            rows.map { list -> list.filter { it.pinnedAt != null || it.isPrivate || it.createdAt >= expiryCutoff } }

        override suspend fun findByHash(contentHash: Long): ClipEntry? =
            rows.value.firstOrNull { it.contentHash == contentHash }

        override suspend fun updateContent(id: Long, content: String, contentHash: Long): Int {
            val row = rows.value.firstOrNull { it.id == id && it.uri == null && it.mediaFile == null }
                ?: return 0
            rows.value = rows.value.map { if (it.id == id) row.copy(content = content, contentHash = contentHash) else it }
            return 1
        }

        override suspend fun upsert(
            content: String,
            createdAt: Long,
            contentHash: Long,
            uri: String?,
            mimeType: String?,
            mediaFile: String?,
            sizeBytes: Long,
            thumbnail: ByteArray?,
            isPrivate: Boolean,
            sourcePackage: String?,
        ) {
            val existing = findByHash(contentHash)
            rows.value = if (existing != null) {
                rows.value.map {
                    if (it.id == existing.id) {
                        it.copy(
                            createdAt = createdAt,
                            isPrivate = it.isPrivate || isPrivate,
                            sourcePackage = it.sourcePackage ?: sourcePackage,
                        )
                    } else {
                        it
                    }
                }
            } else {
                rows.value + ClipEntry(
                    id = nextId++, content = content, createdAt = createdAt, contentHash = contentHash,
                    uri = uri, mimeType = mimeType, mediaFile = mediaFile, sizeBytes = sizeBytes,
                    thumbnail = thumbnail, isPrivate = isPrivate, sourcePackage = sourcePackage,
                )
            }
        }

        override suspend fun mediaFiles(): List<String> = rows.value.mapNotNull { it.mediaFile }

        override suspend fun findByMediaFile(mediaFile: String): ClipEntry? =
            rows.value.firstOrNull { it.mediaFile == mediaFile }

        override suspend fun insertIfAbsent(
            content: String,
            createdAt: Long,
            pinnedAt: Long?,
            contentHash: Long,
            isPrivate: Boolean,
        ) {
            if (findByHash(contentHash) == null) {
                rows.value = rows.value + ClipEntry(nextId++, content, createdAt, pinnedAt, contentHash, isPrivate = isPrivate)
            }
        }

        override suspend fun setPinned(id: Long, pinnedAt: Long?) {
            rows.value = rows.value.map { if (it.id == id) it.copy(pinnedAt = pinnedAt) else it }
        }

        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun deleteExpired(expiryCutoff: Long): Int =
            remove { it.pinnedAt == null && !it.isPrivate && it.createdAt < expiryCutoff }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }

        override suspend fun deleteImages(): Int = remove { it.uri != null || it.mediaFile != null }

        override suspend fun deleteUnpinned(): Int = remove { it.pinnedAt == null && !it.isPrivate }

        override suspend fun count(): Int = rows.value.size

        override suspend fun trimUnpinnedTo(keep: Int): Int {
            val unpinned = rows.value.filter { it.pinnedAt == null && !it.isPrivate }.sortedByDescending { it.createdAt }
            val dropped = unpinned.drop(keep).map { it.id }.toSet()
            return remove { it.id in dropped }
        }

        private fun remove(which: (ClipEntry) -> Boolean): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot(which)
            return before - rows.value.size
        }
    }

    /** Files by name in memory; every sweep is recorded. */
    private class FakeMedia : ClipMediaFiles {
        val files = mutableMapOf<String, ByteArray>()
        val sweeps = mutableListOf<Set<String>>()

        override fun store(bytes: ByteArray, mimeType: String): ClipMediaFiles.Stored? {
            val extension = ClipMedia.extensionFor(mimeType) ?: return null
            val name = ClipMedia.nameFor(ClipMedia.sha256(bytes), extension)
            files[name] = bytes
            return ClipMediaFiles.Stored(name, bytes.size.toLong(), byteArrayOf(1, 2, 3))
        }

        override fun read(name: String): ByteArray? = files[name]

        override fun sweep(referenced: Set<String>) {
            sweeps += referenced
            files.keys.retainAll(referenced)
        }
    }

    private val dao = FakeDao()
    private val media = FakeMedia()
    private var clock = 1_000L
    private val settings = MutableStateFlow(KeyboardPreferences(clipboardImages = true, clipboardImageMaxMb = 1))
    private val repository = ClipboardRepository(dao, settings, media) { clock }

    private val picture = ByteArray(2_048) { (it % 251).toByte() }

    @Test
    fun `a copied image is kept as bytes under its hash, with a thumbnail and its size`() = runTest {
        assertNull(repository.rememberImageBytes(picture, "image/png"))
        val row = dao.rows.value.single()
        assertTrue(row.isImage)
        assertTrue(row.hasMedia)
        assertEquals("", row.content)
        assertEquals(2_048L, row.sizeBytes)
        assertEquals(ClipMedia.nameFor(ClipMedia.sha256(picture), "png"), row.mediaFile)
        assertEquals(3, row.thumbnail?.size)
        assertEquals(1, media.files.size)
        assertTrue(picture.contentEquals(repository.imageBytes(row.mediaFile!!)))
    }

    @Test
    fun `the same picture copied again is one entry, moved to the top`() = runTest {
        repository.rememberImageBytes(picture, "image/png")
        clock = 2_000L
        repository.rememberImageBytes(picture, "image/png")
        assertEquals(1, dao.rows.value.size)
        assertEquals(2_000L, dao.rows.value.single().createdAt)
        assertEquals(1, media.files.size)
    }

    @Test
    fun `an image over the cap, or with images off, is refused and nothing is stored`() = runTest {
        val large = ByteArray(1024 * 1024 + 1)
        assertEquals(ClipboardRepository.ImageRefusal.TOO_LARGE, repository.rememberImageBytes(large, "image/png"))
        settings.value = KeyboardPreferences(clipboardImages = false)
        assertEquals(ClipboardRepository.ImageRefusal.OFF, repository.rememberImageBytes(picture, "image/png"))
        settings.value = KeyboardPreferences(clipboardImages = true)
        assertEquals(ClipboardRepository.ImageRefusal.NOT_AN_IMAGE, repository.rememberImageBytes(picture, "text/plain"))
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(media.files.isEmpty())
    }

    @Test
    fun `every delete sweeps the files nothing refers to`() = runTest {
        repository.rememberImageBytes(picture, "image/png")
        val other = ByteArray(100) { 7 }
        repository.rememberImageBytes(other, "image/jpeg")
        assertEquals(2, media.files.size)

        repository.delete(dao.rows.value.first { it.sizeBytes == 100L }.id)
        assertEquals(1, media.files.size)
        assertTrue(media.sweeps.last().single().endsWith(".png"))

        repository.deleteImages()
        assertTrue(media.files.isEmpty())
        assertEquals(emptySet<String>(), media.sweeps.last())
    }

    @Test
    fun `the oldest unpinned image goes with its file when the history is full`() = runTest {
        settings.value = KeyboardPreferences(clipboardImages = true, clipboardMaxEntries = 1)
        repository.rememberImageBytes(picture, "image/png")
        clock = 2_000L
        repository.rememberImageBytes(ByteArray(100) { 7 }, "image/jpeg")
        assertEquals(1, dao.rows.value.size)
        assertEquals(1, media.files.size)
        assertTrue(media.files.keys.single().endsWith(".jpg"))
    }

    @Test
    fun `a private copy is kept with history off, flagged, with its source`() = runTest {
        settings.value = KeyboardPreferences(clipboardEnabled = false)
        assertTrue(!repository.remember("plain"))
        assertTrue(repository.rememberPrivately("secret", "com.example.notes"))
        val row = dao.rows.value.single()
        assertTrue(row.isPrivate)
        assertEquals("secret", row.content)
        assertEquals("com.example.notes", row.sourcePackage)
        assertTrue(!repository.rememberPrivately("", "com.example.notes"))
    }

    @Test
    fun `the private flag survives the same text copied again, either way round`() = runTest {
        repository.remember("secret")
        repository.rememberPrivately("secret", "com.example.notes")
        assertTrue(dao.rows.value.single().isPrivate)
        assertEquals("com.example.notes", dao.rows.value.single().sourcePackage)
        clock = 5_000L
        repository.remember("secret")
        val row = dao.rows.value.single()
        assertTrue(row.isPrivate)
        assertEquals(5_000L, row.createdAt)
    }

    @Test
    fun `a private entry outlives the timer, the limit and clearing on close`() = runTest {
        settings.value = KeyboardPreferences(clipboardMaxEntries = 1, clipboardRetentionMinutes = 1)
        repository.rememberPrivately("secret", null)
        clock += 10 * 60_000L
        repository.remember("newer")
        repository.remember("newest")
        assertTrue(dao.rows.value.any { it.isPrivate })
        assertEquals(2, dao.rows.value.size)
        repository.purgeExpired()
        assertTrue(dao.rows.value.any { it.isPrivate })
        repository.deleteUnpinned()
        assertEquals(listOf("secret"), dao.rows.value.map { it.content })
        assertTrue(repository.recent(10).single().isPrivate)
        repository.deleteAll()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `text is remembered as before`() = runTest {
        assertTrue(repository.remember("hello"))
        val row = dao.rows.value.single()
        assertEquals("hello", row.content)
        assertTrue(!row.isImage)
        assertEquals(ClipboardRepository.contentHash("hello"), row.contentHash)
    }
}
