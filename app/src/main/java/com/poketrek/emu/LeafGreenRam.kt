package com.poketrek.emu

/**
 * RAM addresses for Pokémon LeafGreen Version (USA, Europe) Rev 1.
 *
 * Player position lives behind a DMA-protected pointer at `0x03005008`. The
 * pointer's target value moves as the engine reshuffles, so we must deref each
 * read. Other addresses (e.g. moving status) are at fixed locations.
 *
 * For non-US-Rev1 builds (Korean), [SAVE_BLOCK1_PTR] is replaced at read time
 * with the calibrated value from [RomCalibration]. The other addresses are
 * still hardcoded to the US Rev 1 values until probes for them are added.
 */
object LeafGreenRam {
    const val SAVE_BLOCK1_PTR = 0x03005008
    const val MOVING_STATUS = 0x0203707B  // 1 byte, non-zero while player is mid-step animation
    const val SPEED_BIKING = 0x02037078   // low 3 bits

    /** Snapshot of the bits the gating logic and HUD care about. */
    data class Snapshot(
        val playerX: Int,
        val playerY: Int,
        val mapId: Int,
        val mapBank: Int,
        val movingStatus: Int,
        val saveBlockPtr: Int,
    )

    fun read(emu: NativeEmulator, calibration: RomCalibration = RomCalibration.DEFAULT_US_REV1): Snapshot {
        val ptr = emu.busRead32(calibration.saveBlock1PtrAddr)
        val playerX: Int
        val playerY: Int
        val mapId: Int
        val mapBank: Int
        if (ptr == 0 || ptr ushr 24 != 0x02 && ptr ushr 24 != 0x03) {
            // Pointer hasn't been initialized yet (title screen, intro, etc.)
            playerX = 0
            playerY = 0
            mapId = 0
            mapBank = 0
        } else {
            playerX = emu.busRead16(ptr)
            playerY = emu.busRead16(ptr + 2)
            // SaveBlock1.location is struct WarpData { s8 mapGroup; s8 mapNum; ... }.
            mapBank = emu.busRead8(ptr + 4)
            mapId = emu.busRead8(ptr + 5)
        }
        return Snapshot(
            playerX = playerX,
            playerY = playerY,
            mapId = mapId,
            mapBank = mapBank,
            movingStatus = emu.busRead8(MOVING_STATUS),
            saveBlockPtr = ptr,
        )
    }
}
