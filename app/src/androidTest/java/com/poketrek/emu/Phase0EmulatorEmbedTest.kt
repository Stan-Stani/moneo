package com.poketrek.emu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0 exit criterion: load LeafGreen, run 600 frames, verify the framebuffer
 * hash is deterministic across runs and the emulator doesn't crash.
 *
 * Setup: drop your LeafGreen ROM at `app/src/androidTest/assets/leafgreen.gba`
 * (gitignored). Then `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Phase0EmulatorEmbedTest {

    private lateinit var emu: NativeEmulator

    @Before
    fun setUp() {
        emu = NativeEmulator()
    }

    @After
    fun tearDown() {
        emu.destroy()
    }

    @Test
    fun loadsRomAndRunsFrames() {
        val rom = readRom()
        assertTrue("loadRom should succeed", emu.loadRom(rom, null))

        repeat(600) { emu.runFrame() }

        val hash = emu.getFramebufferHash()
        assertNotEquals("framebuffer should not be all zeros", 0L, hash)

        val framebuffer = emu.getFramebuffer()
        assertEquals("framebuffer is 240*160*4 bytes", 240 * 160 * 4, framebuffer.size)
    }

    @Test
    fun framebufferHashIsDeterministic() {
        val rom = readRom()
        val hashes = (0 until 2).map { _ ->
            val e = NativeEmulator()
            try {
                assertTrue(e.loadRom(rom, null))
                repeat(600) { e.runFrame() }
                e.getFramebufferHash()
            } finally {
                e.destroy()
            }
        }
        assertEquals("two runs of 600 frames must produce the same framebuffer", hashes[0], hashes[1])
    }

    private fun readRom(): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open("leafgreen.gba").use { it.readBytes() }
    }
}
