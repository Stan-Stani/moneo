package com.poketrek.emu

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Produces the 2024 Korean LeafGreen ROM on-device by applying the
 * fan-translation xdelta patch to a user-supplied Japanese LeafGreen 1.0
 * base.
 *
 * We never host or redistribute a ROM. The xdelta bundle is fetched from
 * the patch authors' own public distribution (the Google Drive link
 * documented in `tools/moneo/rom_swap/README.md`); the user brings their
 * own Japanese base dump. The actual VCDIFF decode happens in native code
 * ([NativeEmulator.applyXdelta], strict-then-ADLER32_NOVER, mirroring
 * `tools/moneo/rom_swap/apply_patch.py`).
 *
 * Patch authors: 명군 (lead), tony, koi, 돌아온달토끼.
 *
 * The pure pieces ([selectLeafgreenEntryName], [extractLeafgreenXdelta],
 * [isExpectedKoreanRom]) are JVM-unit-tested; [produce] does the network
 * + native I/O and must run off the main thread.
 */
object KoreanRomPatcher {

    /**
     * Direct-download of the 2024-02-29 multi-game patch bundle (a zip with
     * LeafGreen / FireRed / Emerald `.xdelta` files plus a Korean readme),
     * hosted by the patch authors. The file is well under Drive's 25 MB
     * virus-scan-interstitial threshold, so `uc?export=download` returns
     * the bytes directly after a redirect. Keep in sync with the curl URL
     * in `tools/moneo/rom_swap/README.md`.
     */
    const val PATCH_BUNDLE_URL =
        "https://drive.google.com/uc?export=download&id=1PtJ7YplZBdN8Yvb3cw-w9hrt-sT2trPt"

    /** Patched-ROM invariants — a correctly produced KR_2024 LeafGreen. */
    const val EXPECTED_SIZE_BYTES = 0x1000000          // 16 MiB
    const val EXPECTED_CRC32 = 0x4A38A8CBL             // RomVariant.LEAFGREEN_KR_2024

    /** Human-facing label cached alongside the produced ROM. */
    const val ROM_LABEL = "LeafGreen (Korean 2024-02-29 patch)"

    private const val TAG = "KoreanRomPatcher"
    private const val CACHED_PATCH_NAME = "leafgreen_J-K.xdelta"
    private const val KR_LEAFGREEN_MARKER = "리프그린"   // "LeafGreen" in Korean

    /** Coarse progress for the settings UI. */
    enum class Phase { DOWNLOADING_PATCH, EXTRACTING_PATCH, PATCHING, VERIFYING }

    sealed interface State {
        data object Idle : State
        data class Running(val phase: Phase) : State
        data class Success(val crc32: Long) : State
        data class Error(val message: String) : State
    }

    /**
     * The bundle's zip entry names are UTF-8 bytes, but the archive omits
     * the language-encoding flag, so a strict cp437 reader (Java's
     * `ZipInputStream` with Cp437, mirroring Python's `zipfile`) hands back
     * mojibake. Reinterpreting those bytes cp437→UTF-8 recovers the real
     * Korean name. We test both the raw name and the recovered name so a
     * correctly UTF-8-flagged archive also works.
     *
     * @return the matching entry name, or null if no LeafGreen `.xdelta`
     *         entry is present.
     */
    fun selectLeafgreenEntryName(entryNames: List<String>): String? =
        entryNames.firstOrNull { name ->
            val recovered = runCatching {
                String(name.toByteArray(charset("Cp437")), Charsets.UTF_8)
            }.getOrDefault(name)
            val isXdelta = name.endsWith(".xdelta") || recovered.endsWith(".xdelta")
            isXdelta && (name.contains(KR_LEAFGREEN_MARKER) ||
                recovered.contains(KR_LEAFGREEN_MARKER))
        }

    /** Pulls the LeafGreen `.xdelta` bytes out of the patch-bundle zip. */
    fun extractLeafgreenXdelta(zipBytes: ByteArray): ByteArray {
        // First pass: collect names so we can apply the same selection
        // logic the test exercises; second pass: read the chosen bytes.
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes), charset("Cp437")).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) names += e.name
                e = zis.nextEntry
            }
        }
        val target = selectLeafgreenEntryName(names)
            ?: error("LeafGreen .xdelta not found in patch bundle (entries: $names)")
        ZipInputStream(ByteArrayInputStream(zipBytes), charset("Cp437")).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == target) return zis.readBytes()
                e = zis.nextEntry
            }
        }
        error("Patch bundle entry '$target' vanished on second read")
    }

    /**
     * Why [variant] can't be the patch base, or null when it may be: the
     * Japanese 1.0 dump, or an unrecognised file we let the patch judge.
     * Lets a known wrong ROM fail before the patch is downloaded. Pure.
     */
    fun baseProblem(variant: RomVariant): String? = when (variant) {
        RomVariant.LEAFGREEN_JP_10, RomVariant.UNKNOWN -> null
        RomVariant.LEAFGREEN_KR_2024 -> null // already Korean; the caller just loads it
        else -> "That's ${variant.displayName}. The Korean patch needs Japanese LeafGreen 1.0."
    }

    /** Size + CRC32 gate for a candidate patched ROM. Pure. */
    fun isExpectedKoreanRom(bytes: ByteArray): Boolean =
        bytes.size == EXPECTED_SIZE_BYTES &&
            CRC32().apply { update(bytes) }.value == EXPECTED_CRC32

    /**
     * Full pipeline: fetch (or reuse the cached) patch, extract the
     * LeafGreen `.xdelta`, apply it to [baseBytes] via [applyXdelta], and
     * verify the result is the expected KR_2024 ROM.
     *
     * Network and native work — call from an IO context. [applyXdelta] is
     * injected so this is unit-testable and so the native singleton stays
     * the activity's concern. The cached patch lives at
     * `cacheDir/leafgreen_J-K.xdelta`; a cached patch that doesn't produce
     * the expected ROM is deleted and the download retried once. Nothing is
     * cached from a download that doesn't unzip.
     */
    fun produce(
        baseBytes: ByteArray,
        cacheDir: File,
        applyXdelta: (ByteArray, ByteArray) -> ByteArray?,
        onPhase: (Phase) -> Unit = {},
        httpGet: (String) -> ByteArray = ::httpGetFollowingRedirects,
        /** The patched-ROM check; injectable so tests needn't build a real 16 MiB ROM. */
        isExpected: (ByteArray) -> Boolean = ::isExpectedKoreanRom,
    ): Result<ByteArray> = runCatching {
        val cached = File(cacheDir, CACHED_PATCH_NAME)

        val cachedPatch = cached.takeIf { it.exists() && it.length() > 0 }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        var patch = cachedPatch ?: downloadAndCachePatch(cached, onPhase, httpGet)
        onPhase(Phase.PATCHING)
        var patched = applyXdelta(baseBytes, patch)

        // A stale/corrupt cached patch is the likeliest first-failure cause;
        // nuke it and pull a fresh copy once before giving up. A patch we
        // just downloaded is as fresh as it gets, so the base is to blame.
        if ((patched == null || !isExpected(patched)) && cachedPatch != null) {
            Log.w(TAG, "Cached patch failed; refetching patch bundle")
            cached.delete()
            patch = downloadAndCachePatch(cached, onPhase, httpGet)
            onPhase(Phase.PATCHING)
            patched = applyXdelta(baseBytes, patch)
        }

        onPhase(Phase.VERIFYING)
        requireNotNull(patched) {
            "xdelta decode failed — is this a Japanese LeafGreen 1.0 ROM?"
        }
        require(isExpected(patched)) {
            val crc = CRC32().apply { update(patched) }.value
            "Patched ROM mismatch (got ${RomIdentity.crc32Hex(crc)}, " +
                "size ${patched.size}). The base must be Japanese LeafGreen 1.0."
        }
        patched
    }

    private fun downloadAndCachePatch(
        cached: File,
        onPhase: (Phase) -> Unit,
        httpGet: (String) -> ByteArray,
    ): ByteArray {
        onPhase(Phase.DOWNLOADING_PATCH)
        val zip = try {
            httpGet(PATCH_BUNDLE_URL)
        } catch (e: java.io.IOException) {
            throw java.io.IOException(
                "Couldn't download the patch (${e.message}). Check your internet connection and try again.", e,
            )
        }
        onPhase(Phase.EXTRACTING_PATCH)
        val xdelta = try {
            extractLeafgreenXdelta(zip)
        } catch (e: Exception) {
            throw java.io.IOException(
                "The patch download was incomplete or damaged (${zip.size} bytes: ${e.message}). Try again.", e,
            )
        }
        runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(xdelta)
        }.onFailure { Log.w(TAG, "Could not cache patch", it) }
        return xdelta
    }

    /**
     * Minimal GET that follows redirects across hosts (Drive bounces
     * `uc?export=download` to a `googleusercontent.com` URL, and may switch
     * protocols, which `HttpURLConnection` won't auto-follow). Style matches
     * [com.poketrek.moneo.correction.VpsSubmitter].
     */
    private fun httpGetFollowingRedirects(startUrl: String): ByteArray {
        var url = startUrl
        repeat(6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "poketrek")
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.use { it.readBytes() }
                    in 300..399 -> {
                        url = conn.getHeaderField("Location")
                            ?: throw java.io.IOException("Redirect ($code) without Location")
                    }
                    else -> throw java.io.IOException("HTTP $code fetching patch bundle")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw java.io.IOException("Too many redirects fetching patch bundle")
    }
}
