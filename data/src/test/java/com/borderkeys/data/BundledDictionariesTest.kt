// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BundledDictionariesTest {

    private fun header(magic: Int = 0x31444B42, crc: Int = 0x5A5A1234, extra: Int = 400): ByteArray {
        val bytes = ByteArray(32 + extra)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, magic)
        buffer.putInt(4, 3)
        buffer.putInt(8, 336)
        buffer.putInt(12, 3)
        buffer.putLong(16, bytes.size.toLong())
        buffer.putInt(24, crc)
        buffer.putInt(28, 0x0BADF00D)
        return bytes
    }

    @Test
    fun readsTheContentCrcFromThePackHeader() {
        assertEquals(0x5A5A1234, BundledDictionaries.contentCrc(ByteArrayInputStream(header())))
        assertEquals(-1, BundledDictionaries.contentCrc(ByteArrayInputStream(header(crc = -1))))
    }

    @Test
    fun twoEditionsOfOneListDifferByCrcAlone() {
        val shipped = BundledDictionaries.contentCrc(ByteArrayInputStream(header(crc = 1)))
        val installed = BundledDictionaries.contentCrc(ByteArrayInputStream(header(crc = 2)))
        assertEquals(false, shipped == installed)
    }

    @Test
    fun refusesWhatIsNotAPack() {
        assertNull(BundledDictionaries.contentCrc(ByteArrayInputStream(header(magic = 0x11223344))))
        assertNull(BundledDictionaries.contentCrc(ByteArrayInputStream(ByteArray(12))))
        assertNull(BundledDictionaries.contentCrc(ByteArrayInputStream(ByteArray(0))))
    }
}
