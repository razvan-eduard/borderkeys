// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.SliceRows
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import com.borderkeys.typing.Commit
import com.borderkeys.typing.CommitLanguage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The context slice across the bridge on the host library: what is pushed, the engine holds and reads. */
class ContextSliceBridgeTest {

    @Test
    fun `slice arrays cross the bridge`() {
        Pipeline.require()
        val handle = NativePredictor.nativeCreate()
        try {
            val random = Random(SEED)
            val pairs = 1 + random.nextInt(LINKS)
            val triples = random.nextInt(LINKS)
            val hops = minOf(pairs, SliceRows.MAX_HOPS)
            val header = intArrayOf(
                random.nextInt(IDS), random.nextInt(IDS), random.nextInt(IDS), random.nextInt(COUNTS),
                random.nextInt(COUNTS), pairs, triples, hops,
            )
            val links = IntArray((pairs + triples) * SliceRows.LINK_FIELDS) { random.nextInt(COUNTS) }
            val hopInts = IntArray(hops * SliceRows.HOP_FIELDS) { random.nextInt(COUNTS) }
            val seen = LongArray(SliceRows.CONTEXT_SEEN_FIELDS + pairs + triples) { random.nextLong(Long.MAX_VALUE) }
            assertTrue(NativePredictor.nativePushSlice(handle, header, links, hopInts, seen))
            val readHeader = IntArray(SliceRows.HEADER_FIELDS)
            val readLinks = IntArray(links.size)
            val readHops = IntArray(hopInts.size)
            val readSeen = LongArray(seen.size)
            assertTrue(NativePredictor.nativeReadSlice(handle, readHeader, readLinks, readHops, readSeen))
            assertArrayEquals("the header", header, readHeader)
            assertArrayEquals("each link's word, count and choices", links, readLinks)
            assertArrayEquals("each hop", hopInts, readHops)
            assertArrayEquals("the contexts' and each link's observations", seen, readSeen)

            assertFalse(
                "a slice whose arrays disagree with its header is refused",
                NativePredictor.nativePushSlice(handle, header, links.copyOf(links.size - 1), hopInts, seen),
            )
            NativePredictor.nativeReadSlice(handle, readHeader, readLinks, readHops, readSeen)
            assertArrayEquals(
                "and leaves none",
                intArrayOf(SliceRows.NONE, SliceRows.NONE, SliceRows.NONE, 0, 0, 0, 0, 0),
                readHeader,
            )
        } finally {
            NativePredictor.nativeDestroy(handle)
        }
    }

    @Test
    fun `arrays holding more than the header counts are read up to the counts`() {
        Pipeline.require()
        val handle = NativePredictor.nativeCreate()
        try {
            val header = intArrayOf(0, 1, SliceRows.NONE, 2, 0, 1, 0, 1)
            val links = intArrayOf(7, 2, 0)
            val hops = intArrayOf(SliceRows.NONE, 0, 0, 0)
            val seen = longArrayOf(2L, 0L, 2L)
            assertTrue(
                NativePredictor.nativePushSlice(
                    handle, header, links + IntArray(SPARE), hops + IntArray(SPARE), seen + LongArray(SPARE),
                ),
            )
            val readLinks = IntArray(links.size + SPARE)
            val readSeen = LongArray(seen.size + SPARE)
            val readHeader = IntArray(SliceRows.HEADER_FIELDS)
            NativePredictor.nativeReadSlice(handle, readHeader, readLinks, IntArray(hops.size), readSeen)
            assertArrayEquals(header, readHeader)
            assertArrayEquals(links, readLinks.copyOf(links.size))
            assertArrayEquals(seen, readSeen.copyOf(seen.size))
        } finally {
            NativePredictor.nativeDestroy(handle)
        }
    }

    @Test
    fun `the next request in a context is handed the commits made since the last one there`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            // Both words held, so the request names the same context before the commit and after.
            english.loadWords(listOf(CONTEXT, PACK_FOLLOWER), KeyboardPreferences.DEFAULT_LEARN_AFTER_USES)
            assertEquals(emptySet<String>(), english.heldAfter(language, CONTEXT))
            english.workingCopy.commit(Commit(LearnedWord(PACK_FOLLOWER, ENGLISH, 1, AT), CONTEXT, null, language))
            assertEquals(setOf(PACK_FOLLOWER), english.heldAfter(language, CONTEXT))
        } finally {
            english.close()
        }
    }

    @Test
    fun `a commit under the language no tag names learns its pair there, and a request under it reads it`() {
        Pipeline.require()
        // No pack answers, as for a layout no pack is for.
        val packless = Pipeline.open()
        try {
            val language = CommitLanguage.decide(null, LanguageTags.UNKNOWN)
            assertEquals(LanguageTags.UNKNOWN, language)
            // Both words learned first, so their pair is learnable.
            repeat(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) {
                packless.workingCopy.commit(Commit(LearnedWord(CONTEXT, language, 1, AT), null, null, language))
            }
            for (previous in listOf(null, CONTEXT)) {
                repeat(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) {
                    packless.workingCopy.commit(Commit(LearnedWord(FOLLOWER, language, 1, AT), previous, null, language))
                }
            }
            assertEquals(setOf(FOLLOWER), packless.heldAfter(language, CONTEXT))
            assertTrue("offered after its word there", FOLLOWER in packless.strip("", CONTEXT, language))
            assertFalse(
                "and nowhere else",
                FOLLOWER in packless.strip("", CONTEXT, LanguageTags.primary(ENGLISH)),
            )
        } finally {
            packless.close()
        }
    }

    private companion object {
        const val ENGLISH = "en-US"
        const val SEED = 20261030
        const val AT = 1_000L

        /** The most pairs, or triples, the pushed slice holds, the ids its header names, and its counts. */
        const val LINKS = 40

        /** Slots past the end of what a slice counts. */
        const val SPARE = 6
        const val IDS = 1_000
        const val COUNTS = 100

        /** A pack word, a word no pack spells, and one the pack spells. */
        const val CONTEXT = "the"
        const val FOLLOWER = "zqxwriver"
        const val PACK_FOLLOWER = "river"
    }
}
