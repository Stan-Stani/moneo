package com.poketrek.emu

import java.util.zip.CRC32

/**
 * What ROM is currently loaded. Used to (a) surface a friendly name in
 * the debug overlay, (b) decide whether the hard-coded LeafGreenRam
 * addresses can be trusted for step gating.
 *
 * For now only the matching US Rev 1 build is calibrated. Korean and
 * other regional variants are detected but [LeafGreenRam] reads from
 * addresses that won't be correct on those builds — runtime calibration
 * is the planned path (see project memory).
 */
enum class RomVariant(
    val displayName: String,
    val gatingSupported: Boolean,
    /**
     * Whether the Moneo *area* gate (distinct from the step gate above)
     * runs on this ROM. boundary_tiles.json is keyed in pokefirered's
     * canonical bank/mapId numbering, which both the US Rev 1 ROM and the
     * 2024 KR patch use.
     */
    val areaGateSupported: Boolean = false,
) {
    LEAFGREEN_US_REV1(
        "LeafGreen (USA Rev 1)",
        gatingSupported = true,
        areaGateSupported = true,
    ),
    LEAFGREEN_KOREAN("LeafGreen (Korean 2010 fan translation)", gatingSupported = false),
    LEAFGREEN_KR_2024(
        "LeafGreen (Korean 2024-02-29 patch)",
        gatingSupported = false,
        areaGateSupported = true,
    ),
    UNKNOWN("Unknown ROM", gatingSupported = false),
}

/**
 * Identity of a loaded ROM: its CRC32 hash plus the variant we mapped
 * the hash to.
 */
data class RomIdentity(val crc32: Long, val variant: RomVariant) {
    val crc32Hex: String
        get() = "0x" + crc32.toString(16).uppercase().padStart(8, '0')

    companion object {
        /**
         * Known CRC32 → variant. Computed offline from the canonical ROM
         * files. CRC32 is fine for ROM identification — we don't need
         * cryptographic strength, just a 32-bit fingerprint that doesn't
         * collide across the handful of LeafGreen builds in the wild.
         */
        private val KNOWN: Map<Long, RomVariant> = mapOf(
            0xDAFFECECL to RomVariant.LEAFGREEN_US_REV1,
            0x398C4817L to RomVariant.LEAFGREEN_KOREAN,
            0x4A38A8CBL to RomVariant.LEAFGREEN_KR_2024,
        )

        fun of(bytes: ByteArray): RomIdentity {
            val crc = CRC32().apply { update(bytes) }.value
            return RomIdentity(crc, variantFor(crc))
        }

        /** Maps a known CRC32 to its ROM variant; falls back to [RomVariant.UNKNOWN]. */
        fun variantFor(crc32: Long): RomVariant = KNOWN[crc32] ?: RomVariant.UNKNOWN

        /** Pretty-prints a CRC32 as `0xXXXXXXXX`. */
        fun crc32Hex(crc32: Long): String =
            "0x" + crc32.toString(16).uppercase().padStart(8, '0')
    }
}
