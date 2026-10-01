package com.poketrek.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class RomIdentityTest {

    /** Build a byte stream whose CRC32 matches [target] (filling the rest with `pad`). */
    private fun bytesWithCrc(target: Long, pad: ByteArray): ByteArray {
        // Easier: just set the bytes such that CRC32 happens to come out — but that's
        // arithmetic on the CRC table. Instead: take a fixed pad, compute its crc, and
        // assert. For arbitrary target we'd need a forging primitive. So instead this
        // helper computes the CRC of the supplied bytes and the test asserts on that.
        val crc = CRC32().apply { update(pad) }.value
        assertEquals("test setup: pad bytes don't have expected crc", target, crc)
        return pad
    }

    @Test fun `unknown bytes map to UNKNOWN variant`() {
        val id = RomIdentity.of(byteArrayOf(0, 1, 2, 3, 4, 5))
        assertEquals(RomVariant.UNKNOWN, id.variant)
        assertFalse(id.variant.gatingSupported)
    }

    @Test fun `crc32Hex is upper-case 8 hex digits with 0x prefix`() {
        val id = RomIdentity.of(byteArrayOf(0))
        assertEquals(0xD202EF8DL, id.crc32) // CRC32 of single zero byte
        assertEquals("0xD202EF8D", id.crc32Hex)
        assertEquals(10, id.crc32Hex.length) // "0x" + 8 hex
    }

    @Test fun `crc32Hex pads small CRCs to 8 digits`() {
        // crc32 of empty input is 0
        val id = RomIdentity.of(byteArrayOf())
        assertEquals(0L, id.crc32)
        assertEquals("0x00000000", id.crc32Hex)
    }

    @Test fun `same bytes always produce same identity`() {
        val a = RomIdentity.of(byteArrayOf(1, 2, 3, 4, 5))
        val b = RomIdentity.of(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(a, b)
    }

    @Test fun `different bytes produce different crc32`() {
        val a = RomIdentity.of(byteArrayOf(1, 2, 3))
        val b = RomIdentity.of(byteArrayOf(1, 2, 4))
        assertNotEquals(a.crc32, b.crc32)
    }

    @Test fun `LEAFGREEN_US_REV1 supports gating, others do not`() {
        assertTrue(RomVariant.LEAFGREEN_US_REV1.gatingSupported)
        assertFalse(RomVariant.LEAFGREEN_KOREAN.gatingSupported)
        assertFalse(RomVariant.UNKNOWN.gatingSupported)
    }

    @Test fun `Japanese LeafGreen 1_0 is recognised as the Korean patch base`() {
        // CRC32 of the clean dump whose MD5 is 138a71a5be83f3f3d7af3d31916a5fc7.
        assertEquals(RomVariant.LEAFGREEN_JP_10, RomIdentity.variantFor(0x0A48556BL))
        assertFalse(RomVariant.LEAFGREEN_JP_10.gatingSupported)
        assertFalse(RomVariant.LEAFGREEN_JP_10.areaGateSupported)
    }

    @Test fun `GBA header check accepts a cartridge header and rejects other files`() {
        val header = ByteArray(0xC0).also { it[3] = 0xEA.toByte(); it[0xB2] = 0x96.toByte() }
        assertTrue(RomIdentity.looksLikeGbaRom(header))
        assertFalse(RomIdentity.looksLikeGbaRom("this is not a rom\n".toByteArray()))
        assertFalse(RomIdentity.looksLikeGbaRom(header.copyOf().also { it[0xB2] = 0 }))
        assertFalse(RomIdentity.looksLikeGbaRom(header.copyOf().also { it[3] = 0 }))
    }
}
