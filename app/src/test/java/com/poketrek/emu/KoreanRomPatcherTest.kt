package com.poketrek.emu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KoreanRomPatcherTest {

    /**
     * Reproduces the zip-reader mojibake: the bundle's entry names are
     * UTF-8 bytes with no language-encoding flag, so a Cp437 reader hands
     * back the UTF-8 bytes reinterpreted as Cp437. Cp437 is a complete
     * bijective single-byte charset, so this round-trips.
     */
    private fun asCp437Mojibake(realName: String): String =
        String(realName.toByteArray(Charsets.UTF_8), charset("Cp437"))

    @Test fun selectsLeafgreenAmongCleanUtf8Names() {
        val names = listOf(
            "가이드.txt",
            "포켓몬스터 파이어레드 패치.xdelta",
            "포켓몬스터 리프그린 패치.xdelta",
            "포켓몬스터 에메랄드 패치.xdelta",
        )
        assertEquals("포켓몬스터 리프그린 패치.xdelta", KoreanRomPatcher.selectLeafgreenEntryName(names))
    }

    @Test fun selectsLeafgreenThroughCp437Mojibake() {
        // What Java's ZipInputStream(Cp437) actually yields for a no-EFS-flag
        // archive — the case Python's zipfile hits too.
        val names = listOf(
            asCp437Mojibake("리프그린 J-K.xdelta"),
            asCp437Mojibake("파이어레드 J-K.xdelta"),
            asCp437Mojibake("에메랄드 J-K.xdelta"),
            asCp437Mojibake("README_가이드.txt"),
        )
        val picked = KoreanRomPatcher.selectLeafgreenEntryName(names)
        assertEquals(asCp437Mojibake("리프그린 J-K.xdelta"), picked)
        // And it's recoverable back to the real Korean name.
        assertEquals(
            "리프그린 J-K.xdelta",
            String(picked!!.toByteArray(charset("Cp437")), Charsets.UTF_8),
        )
    }

    @Test fun returnsNullWhenNoLeafgreenXdelta() {
        val names = listOf("파이어레드.xdelta", "에메랄드.xdelta", "리프그린.txt")
        assertNull(KoreanRomPatcher.selectLeafgreenEntryName(names))
    }

    @Test fun extractsLeafgreenBytesFromBundleZip() {
        val lgBytes = "LEAFGREEN-XDELTA-PAYLOAD".toByteArray()
        val zip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                fun put(name: String, data: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
                put("포켓몬스터 파이어레드.xdelta", "FIRERED".toByteArray())
                put("포켓몬스터 리프그린.xdelta", lgBytes)
                put("포켓몬스터 에메랄드.xdelta", "EMERALD".toByteArray())
                put("README_가이드.txt", "guide".toByteArray())
            }
        }.toByteArray()

        assertArrayEquals(lgBytes, KoreanRomPatcher.extractLeafgreenXdelta(zip))
    }

    @Test fun isExpectedKoreanRomRejectsWrongSize() {
        assertFalse(KoreanRomPatcher.isExpectedKoreanRom(ByteArray(1024)))
        assertFalse(
            KoreanRomPatcher.isExpectedKoreanRom(
                ByteArray(KoreanRomPatcher.EXPECTED_SIZE_BYTES - 1),
            ),
        )
    }

    @Test fun isExpectedKoreanRomRejectsRightSizeWrongCrc() {
        // All-zero 16 MiB: correct size, definitely not the KR_2024 CRC.
        assertFalse(
            KoreanRomPatcher.isExpectedKoreanRom(
                ByteArray(KoreanRomPatcher.EXPECTED_SIZE_BYTES),
            ),
        )
    }

    @Test fun expectedCrcMapsToKr2024Variant() {
        // The patcher's success gate must agree with RomIdentity, or a
        // correctly produced ROM would be cached then rejected on reload.
        assertEquals(
            RomVariant.LEAFGREEN_KR_2024,
            RomIdentity.variantFor(KoreanRomPatcher.EXPECTED_CRC32),
        )
        assertEquals(0x1000000, KoreanRomPatcher.EXPECTED_SIZE_BYTES)
    }

    // ---- produce(): download, cache, retry, verify ----
    //
    // A fake "xdelta" is the bytes the fake applier appends to the base;
    // the injected verifier accepts exactly GOOD_ROM.

    private val base = "JP-BASE".toByteArray()
    private val goodPatch = "+GOOD".toByteArray()
    private val stalePatch = "+STALE".toByteArray()
    private val goodRom = "JP-BASE+GOOD".toByteArray()

    private fun bundle(xdelta: ByteArray): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("포켓몬스터 리프그린.xdelta"))
            zos.write(xdelta)
            zos.closeEntry()
        }
    }.toByteArray()

    private val applier: (ByteArray, ByteArray) -> ByteArray? = { b, p -> b + p }
    private val verifier: (ByteArray) -> Boolean = { it.contentEquals(goodRom) }

    private class FakeHttp(private val responses: List<() -> ByteArray>) : (String) -> ByteArray {
        var calls = 0
        override fun invoke(url: String): ByteArray = responses[calls++].invoke()
    }

    private fun tempCache(): java.io.File = kotlin.io.path.createTempDirectory("patcher").toFile()

    private fun cachedPatch(dir: java.io.File) = java.io.File(dir, "leafgreen_J-K.xdelta")

    @Test fun freshDownloadPatchesCachesAndReportsPhases() {
        val dir = tempCache()
        val http = FakeHttp(listOf({ bundle(goodPatch) }))
        val phases = ArrayList<KoreanRomPatcher.Phase>()
        val result = KoreanRomPatcher.produce(base, dir, applier, phases::add, http, verifier)

        assertArrayEquals(goodRom, result.getOrThrow())
        assertEquals(1, http.calls)
        assertArrayEquals(goodPatch, cachedPatch(dir).readBytes())
        assertEquals(
            listOf(
                KoreanRomPatcher.Phase.DOWNLOADING_PATCH,
                KoreanRomPatcher.Phase.EXTRACTING_PATCH,
                KoreanRomPatcher.Phase.PATCHING,
                KoreanRomPatcher.Phase.VERIFYING,
            ),
            phases,
        )
    }

    @Test fun goodCachedPatchSkipsTheDownload() {
        val dir = tempCache()
        cachedPatch(dir).writeBytes(goodPatch)
        val http = FakeHttp(emptyList())
        val result = KoreanRomPatcher.produce(base, dir, applier, {}, http, verifier)

        assertArrayEquals(goodRom, result.getOrThrow())
        assertEquals(0, http.calls)
    }

    @Test fun staleCachedPatchIsReplacedByOneFreshDownload() {
        val dir = tempCache()
        cachedPatch(dir).writeBytes(stalePatch)
        val http = FakeHttp(listOf({ bundle(goodPatch) }))
        val result = KoreanRomPatcher.produce(base, dir, applier, {}, http, verifier)

        assertArrayEquals(goodRom, result.getOrThrow())
        assertEquals(1, http.calls)
        assertArrayEquals(goodPatch, cachedPatch(dir).readBytes())
    }

    @Test fun truncatedDownloadFailsClearlyAndCachesNothing() {
        val dir = tempCache()
        // Incompressible, so the cut lands inside the entry's data (a cut in
        // the central directory alone still streams the whole entry fine).
        val full = bundle(ByteArray(64 * 1024).also { java.util.Random(1).nextBytes(it) })
        val http = FakeHttp(listOf({ full.copyOf(full.size / 2) }))
        val result = KoreanRomPatcher.produce(base, dir, applier, {}, http, verifier)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("got $result", message.contains("incomplete or damaged"))
        assertFalse(cachedPatch(dir).exists())
    }

    @Test fun networkFailureSurfacesAndCachesNothing() {
        val dir = tempCache()
        val http = FakeHttp(listOf({ throw java.io.IOException("HTTP 503 fetching patch bundle") }))
        val result = KoreanRomPatcher.produce(base, dir, applier, {}, http, verifier)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.startsWith("Couldn't download the patch (HTTP 503"))
        assertFalse(cachedPatch(dir).exists())
    }

    @Test fun wrongBaseFailsAfterOneDownloadWithAHint() {
        val dir = tempCache()
        val http = FakeHttp(listOf({ bundle(goodPatch) }, { bundle(goodPatch) }))
        val result = KoreanRomPatcher.produce("US-BASE".toByteArray(), dir, applier, {}, http, verifier)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("Japanese LeafGreen 1.0"))
        // A just-downloaded patch isn't stale; don't fetch it again.
        assertEquals(1, http.calls)
    }

    @Test fun undecodablePatchReportsDecodeFailure() {
        val dir = tempCache()
        cachedPatch(dir).writeBytes(goodPatch)
        val http = FakeHttp(listOf({ bundle(goodPatch) }))
        val result = KoreanRomPatcher.produce(base, dir, { _, _ -> null }, {}, http, verifier)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.startsWith("xdelta decode failed"))
        assertEquals(1, http.calls)
    }

    @Test fun knownNonJapaneseBasesAreRejectedBeforeDownloading() {
        assertNull(KoreanRomPatcher.baseProblem(RomVariant.LEAFGREEN_JP_10))
        assertNull(KoreanRomPatcher.baseProblem(RomVariant.UNKNOWN))
        assertNull(KoreanRomPatcher.baseProblem(RomVariant.LEAFGREEN_KR_2024))
        val us = KoreanRomPatcher.baseProblem(RomVariant.LEAFGREEN_US_REV1).orEmpty()
        assertTrue(us, us.contains("USA") && us.contains("Japanese LeafGreen 1.0"))
    }
}
