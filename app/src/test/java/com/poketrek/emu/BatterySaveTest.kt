package com.poketrek.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatterySaveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun missingOrEmptyFileIsBlank() {
        assertTrue(isBlankSave(File(tmp.root, "none.sav")))
        assertTrue(isBlankSave(tmp.newFile("empty.sav")))
    }

    @Test fun erasedFlashIsBlank() {
        val f = tmp.newFile("erased.sav").apply { writeBytes(ByteArray(0x20000) { 0xFF.toByte() }) }
        assertTrue(isBlankSave(f))
    }

    @Test fun anyWrittenByteMeansASave() {
        val bytes = ByteArray(0x20000) { 0xFF.toByte() }.also { it[0x1FFF4] = 0x25 }
        assertFalse(isBlankSave(tmp.newFile("saved.sav").apply { writeBytes(bytes) }))
    }

    @Test fun fileNameIsZeroPaddedCrc() {
        assertEquals("4a38a8cb.sav", saveFileName(0x4A38A8CBL))
        assertEquals("0000abcd.sav", saveFileName(0xABCDL))
    }
}
