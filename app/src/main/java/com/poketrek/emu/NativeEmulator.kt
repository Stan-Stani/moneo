package com.poketrek.emu

/**
 * Kotlin handle to the embedded mGBA core. All methods JNI into libpoketrek.so.
 *
 * Phase 0 surface only — input, audio, save state, and RAM reads will be added
 * in later phases.
 */
class NativeEmulator {
    init {
        System.loadLibrary("poketrek")
    }

    /**
     * Loads a ROM from a byte array. Replaces any previously loaded ROM.
     * [savePath] is the battery-save file (created if missing), or null to
     * keep the save in memory only.
     */
    external fun loadRom(romBytes: ByteArray, savePath: String?): Boolean

    /** Runs a single GBA frame. Caller is responsible for ~60 Hz pacing. */
    external fun runFrame()

    /** Returns the most recent framebuffer as RGBA8888, 240x160 = 153,600 bytes. */
    external fun getFramebuffer(): ByteArray

    /** FNV-1a 64-bit hash of the framebuffer — used by Phase 0 determinism test. */
    external fun getFramebufferHash(): Long

    /** Releases the native emulator instance. */
    external fun destroy()

    /**
     * Sets the GBA key bitmask (active-high). Bits per the GBA KEYINPUT layout:
     * 0=A, 1=B, 2=Select, 3=Start, 4=Right, 5=Left, 6=Up, 7=Down, 8=R, 9=L.
     */
    external fun setKeys(keys: Int)

    /**
     * Copies the current framebuffer into a direct [java.nio.ByteBuffer] of at
     * least 240*160*4 bytes. Returns true on success. Avoids per-frame allocs.
     */
    external fun writeFramebuffer(buffer: java.nio.ByteBuffer): Boolean

    /** Reads one byte from emulated GBA address [addr]. Returns 0..255. */
    external fun busRead8(addr: Int): Int

    /** Reads a little-endian 16-bit halfword. Returns 0..65535. */
    external fun busRead16(addr: Int): Int

    /** Reads a little-endian 32-bit word. Sign-extended into Java Int. */
    external fun busRead32(addr: Int): Int

    /** Writes the low 8 bits of [value] to emulated GBA address [addr]. */
    external fun busWrite8(addr: Int, value: Int)

    /** Writes the low 16 bits of [value] little-endian to [addr]. */
    external fun busWrite16(addr: Int, value: Int)

    /** Writes [value] as a 32-bit little-endian word to [addr]. */
    external fun busWrite32(addr: Int, value: Int)

    /**
     * Bulk-reads [length] bytes starting at [addr]. One JNI/mutex round-trip
     * regardless of length; intended for calibration scans of EWRAM/IWRAM
     * where per-byte reads would be too slow. Returns null if no ROM is
     * loaded.
     */
    external fun busReadBytes(addr: Int, length: Int): ByteArray?

    /** Serializes the emulator state into a byte array. Returns null on failure. */
    external fun saveState(): ByteArray?

    /** Restores emulator state from a previously-saved byte array. */
    /** [withSavedata]: also restore the state's copy of the battery save. */
    external fun loadState(data: ByteArray, withSavedata: Boolean): Boolean

    /**
     * Applies an xdelta (VCDIFF) [patch] to [base] entirely in memory and
     * returns the patched ROM, or null if the decode fails. Standalone —
     * unrelated to any loaded ROM; safe to call before [loadRom].
     *
     * Used for on-device Korean ROM patching: the user supplies their own
     * Japanese LeafGreen base, the app applies the bundled/fetched patch.
     * Tries a strict decode, then retries ignoring the source checksum so
     * non-canonical base dumps still apply (mirrors apply_patch.py).
     */
    external fun applyXdelta(base: ByteArray, patch: ByteArray): ByteArray?

    /** Configures mGBA's blip channels for the desired output sample rate. */
    external fun initAudio(sampleRate: Int)

    /**
     * Pulls available audio samples into a direct [java.nio.ByteBuffer]
     * (interpreted as 16-bit signed PCM, interleaved stereo). Returns the
     * number of stereo frames written.
     */
    external fun pollAudio(buffer: java.nio.ByteBuffer): Int
}

/** GBA key bits matching mGBA's setKeys mask. */
object GbaKey {
    const val A      = 1 shl 0
    const val B      = 1 shl 1
    const val SELECT = 1 shl 2
    const val START  = 1 shl 3
    const val RIGHT  = 1 shl 4
    const val LEFT   = 1 shl 5
    const val UP     = 1 shl 6
    const val DOWN   = 1 shl 7
    const val R      = 1 shl 8
    const val L      = 1 shl 9
}
