package com.poketrek.emu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MovementGate]. Use a hand-rolled fake budget so the test
 * runs on the plain JVM (no Android Context, no DataStore, no Robolectric).
 *
 * The decrement contract under test:
 *   decrement IFF (X or Y changed since last frame)
 *               AND (a direction was held last frame)
 *               AND (mapBank/mapId unchanged)
 *
 * The masking contract under test:
 *   mask DIR bits IFF (gate enabled) AND (budget == 0) AND (direction held this frame)
 */
class MovementGateTest {

    private class FakeBudget(initialTiles: Int = 0, initialEnabled: Boolean = false) : MovementGateBudget {
        private val _budget = MutableStateFlow(initialTiles)
        private val _enabled = MutableStateFlow(initialEnabled)
        override val budget: StateFlow<Int> = _budget.asStateFlow()
        override val gateEnabled: StateFlow<Boolean> = _enabled.asStateFlow()
        var consumeCalls = 0
            private set
        override fun setGateEnabled(value: Boolean) { _enabled.value = value }
        override fun consumeOneTile(): Boolean {
            consumeCalls++
            val v = _budget.value
            if (v <= 0) return false
            _budget.value = v - 1
            return true
        }
        fun add(tiles: Int) { _budget.value += tiles }
    }

    private fun snap(x: Int = 5, y: Int = 5, mapId: Int = 1, mapBank: Int = 0) =
        LeafGreenRam.Snapshot(
            playerX = x, playerY = y, mapId = mapId, mapBank = mapBank,
            movingStatus = 0, saveBlockPtr = 0x02000000,
        )

    // ---- decrement rule -----------------------------------------------------

    @Test fun `tile change with direction held decrements budget`() {
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        // Frame 1: hold UP, no movement yet — gate sees nothing previous, no decrement.
        gate.process(GbaKey.UP, snap(x = 5, y = 5))
        assertEquals(0, b.consumeCalls)
        // Frame 2: still holding UP, Y changed by 1 — should consume.
        gate.process(GbaKey.UP, snap(x = 5, y = 4))
        assertEquals(1, b.consumeCalls)
        assertEquals(9, b.budget.value)
    }

    @Test fun `tile change with no direction held does not decrement`() {
        // Cutscene / scripted movement: player slides without any key held.
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        gate.process(0, snap(x = 5, y = 5))
        gate.process(0, snap(x = 5, y = 4)) // moved but no direction
        assertEquals(0, b.consumeCalls)
        assertEquals(10, b.budget.value)
    }

    @Test fun `same position with direction held does not decrement`() {
        // Bumping a wall: direction held but X/Y don't change.
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        gate.process(GbaKey.RIGHT, snap(x = 5, y = 5))
        gate.process(GbaKey.RIGHT, snap(x = 5, y = 5)) // unchanged
        assertEquals(0, b.consumeCalls)
        assertEquals(10, b.budget.value)
    }

    @Test fun `map change does not decrement (warp through door)`() {
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        gate.process(GbaKey.DOWN, snap(x = 5, y = 5, mapId = 1))
        // New map: X reset to 7, but mapId changed → don't charge for the warp tile.
        gate.process(GbaKey.DOWN, snap(x = 7, y = 1, mapId = 2))
        assertEquals(0, b.consumeCalls)
        assertEquals(10, b.budget.value)
    }

    @Test fun `map bank change also does not decrement`() {
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        gate.process(GbaKey.DOWN, snap(x = 5, y = 5, mapBank = 0))
        gate.process(GbaKey.DOWN, snap(x = 5, y = 6, mapBank = 1)) // bank changed
        assertEquals(0, b.consumeCalls)
    }

    @Test fun `multiple sequential tile changes each decrement once`() {
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b)
        gate.process(GbaKey.RIGHT, snap(x = 5, y = 5))
        gate.process(GbaKey.RIGHT, snap(x = 6, y = 5))
        gate.process(GbaKey.RIGHT, snap(x = 7, y = 5))
        gate.process(GbaKey.RIGHT, snap(x = 8, y = 5))
        // First call has no prev to diff against; remaining 3 decrement.
        assertEquals(3, b.consumeCalls)
        assertEquals(7, b.budget.value)
    }

    // ---- masking rule -------------------------------------------------------

    @Test fun `disabled gate never masks even when budget is zero`() {
        val b = FakeBudget(initialTiles = 0, initialEnabled = false)
        val gate = MovementGate(b)
        val out = gate.process(GbaKey.UP or GbaKey.A, snap())
        assertEquals(GbaKey.UP or GbaKey.A, out)
    }

    @Test fun `enabled gate with zero budget masks direction bits but preserves A B`() {
        val b = FakeBudget(initialTiles = 0, initialEnabled = true)
        val gate = MovementGate(b)
        val raw = GbaKey.UP or GbaKey.A or GbaKey.B
        val out = gate.process(raw, snap())
        assertEquals(0, out and GbaKey.UP)
        assertEquals(GbaKey.A, out and GbaKey.A)
        assertEquals(GbaKey.B, out and GbaKey.B)
    }

    @Test fun `enabled gate with positive budget does not mask`() {
        val b = FakeBudget(initialTiles = 5, initialEnabled = true)
        val gate = MovementGate(b)
        val out = gate.process(GbaKey.DOWN, snap())
        assertEquals(GbaKey.DOWN, out)
    }

    @Test fun `enabled gate masks all four directions when budget exhausted`() {
        val b = FakeBudget(initialTiles = 0, initialEnabled = true)
        val gate = MovementGate(b)
        val raw = GbaKey.UP or GbaKey.DOWN or GbaKey.LEFT or GbaKey.RIGHT or GbaKey.START
        val out = gate.process(raw, snap())
        assertEquals(0, out and GbaKey.UP)
        assertEquals(0, out and GbaKey.DOWN)
        assertEquals(0, out and GbaKey.LEFT)
        assertEquals(0, out and GbaKey.RIGHT)
        assertEquals(GbaKey.START, out and GbaKey.START)
    }

    // ---- prevKeys uses MASKED bits, not raw --------------------------------

    @Test fun `decrement diff uses masked keys so blocked direction does not credit later movement`() {
        // Subtle: if we exhaust the budget while UP is held, mask UP, then
        // refill the budget — the *next* frame must not retroactively count
        // a tile transition just because raw-UP was held last frame.
        // Concretely: prevKeys must reflect the masked bits, so an exhausted
        // hold doesn't satisfy the "direction held last frame" condition.
        val b = FakeBudget(initialTiles = 0, initialEnabled = true)
        val gate = MovementGate(b)
        // Frame 1: hold UP, budget=0 → masked to 0. No prev, no decrement.
        gate.process(GbaKey.UP, snap(x = 5, y = 5))
        // Frame 2: refill budget to 10. Game engine independently moves Y by 1
        // (e.g. scripted) while UP is still held raw but masked. Should NOT
        // decrement, because last frame's direction (after masking) was 0.
        b.add(10)
        gate.process(GbaKey.UP, snap(x = 5, y = 4))
        assertEquals(0, b.consumeCalls)
    }

    // ---- setEnabled -------------------------------------------------------

    @Test fun `setEnabled flips the budget enabled flag`() {
        val b = FakeBudget(initialEnabled = false)
        val gate = MovementGate(b)
        assertFalse(gate.enabled.value)
        gate.setEnabled(true)
        assertTrue(gate.enabled.value)
        assertTrue(b.gateEnabled.value)
        // Sanity: not the same instance? — they should literally be the same flow.
        assertEquals(b.gateEnabled, gate.enabled)
        // Toggling via budget directly is also visible to the gate.
        b.setGateEnabled(false)
        assertFalse(gate.enabled.value)
    }

    @Test fun `mask is independent of decrement on the same frame`() {
        // Frame where (a) we'd decrement (dir+movement+same map) AND
        // (b) gate is enabled and budget will hit zero. The current
        // implementation reads budget.value for masking AFTER consume
        // already ran — but only if enabled+zero+dir. Verify the order
        // doesn't accidentally double-charge or drop a tile.
        val b = FakeBudget(initialTiles = 1, initialEnabled = true)
        val gate = MovementGate(b)
        gate.process(GbaKey.UP, snap(x = 5, y = 5))            // priming
        val out = gate.process(GbaKey.UP, snap(x = 5, y = 4))  // moved → consume → 0
        assertEquals(1, b.consumeCalls)
        assertEquals(0, b.budget.value)
        // Budget hit zero this same frame → mask kicks in for THIS frame's keys.
        assertEquals(0, out and GbaKey.UP)
        // Make sure other bits would have passed through.
        assertNotEquals(GbaKey.A, out and GbaKey.A) // A wasn't held; nothing to assert vs mask
    }

    // ---- area gate ----------------------------------------------------------

    /**
     * Hand-rolled fake area gate that returns a fixed decision when the
     * snapshot matches a configured tile + pressed-dir, otherwise NONE.
     * Mirrors the FakeBudget pattern — no Context, no real lookup.
     */
    private class FakeAreaGate(
        private val blockOnTile: Triple<Int, Int, Int>? = null, // (mapBank, x, y)
        private val blockedDirMask: Int = 0,
        private val destArea: String = "route_1",
    ) : MoneoAreaGate {
        private val _last = MutableStateFlow(AreaGateDecision.NONE)
        override val lastDecision: StateFlow<AreaGateDecision> = _last.asStateFlow()
        var evalCalls = 0
            private set
        override fun evaluate(rawKeys: Int, snapshot: LeafGreenRam.Snapshot): AreaGateDecision {
            evalCalls++
            val tile = blockOnTile
            if (tile == null) return AreaGateDecision.NONE
            val (b, x, y) = tile
            if (snapshot.mapBank != b || snapshot.playerX != x || snapshot.playerY != y) {
                return AreaGateDecision.NONE
            }
            // Only block if the press includes one of our blocked dir bits.
            if ((rawKeys and blockedDirMask) == 0) return AreaGateDecision.NONE
            val dec = AreaGateDecision(
                shouldBlock = true,
                blockedDirMask = blockedDirMask,
                destArea = destArea,
                maturityFraction = 0.5f,
                thresholdFraction = 0.8f,
            )
            _last.value = dec
            return dec
        }
    }

    @Test fun `area gate off (NONE decision) does not mask anything`() {
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b, initialAreaGate = FakeAreaGate(blockOnTile = null))
        val out = gate.process(GbaKey.UP, snap(x = 5, y = 0))
        assertEquals(GbaKey.UP, out and GbaKey.UP)
    }

    @Test fun `area gate on, threshold met (NONE returned), no mask`() {
        // FakeAreaGate with blockOnTile = null simulates "threshold met"
        // (the impl returns NONE either way; the gate only masks based on
        // the decision).
        val b = FakeBudget(initialTiles = 10)
        val gate = MovementGate(b, initialAreaGate = FakeAreaGate(blockOnTile = null))
        val out = gate.process(GbaKey.UP, snap(x = 5, y = 0))
        assertEquals(GbaKey.UP, out and GbaKey.UP)
    }

    @Test fun `area gate masks press toward unmatured edge boundary`() {
        // Player at (5, 0) on map (0, 1). Pressing UP would cross into a
        // not-yet-mature area → mask just UP, leave A/B/LEFT untouched.
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val pressed = GbaKey.UP or GbaKey.A
        val out = gate.process(pressed, snap(x = 5, y = 0))
        assertEquals(0, out and GbaKey.UP)
        assertEquals(GbaKey.A, out and GbaKey.A)
    }

    @Test fun `area gate does not mask press AWAY from edge boundary`() {
        // Same setup as above but the player presses LEFT instead — fake
        // gate's blocked-dir mask is UP only, so LEFT passes through.
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val out = gate.process(GbaKey.LEFT, snap(x = 5, y = 0))
        assertEquals(GbaKey.LEFT, out and GbaKey.LEFT)
    }

    @Test fun `area gate masks all four dirs when standing on warp tile`() {
        // Warps trigger on entry, so any press blocks. The fake encodes that
        // by setting blockedDirMask = all four DIR bits.
        val b = FakeBudget(initialTiles = 10)
        val allDirs = GbaKey.UP or GbaKey.DOWN or GbaKey.LEFT or GbaKey.RIGHT
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 12, 8),
            blockedDirMask = allDirs,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val out = gate.process(GbaKey.RIGHT or GbaKey.B, snap(x = 12, y = 8))
        assertEquals(0, out and GbaKey.RIGHT)
        assertEquals(GbaKey.B, out and GbaKey.B)
    }

    @Test fun `step gate and area gate compose - both contribute`() {
        // budget=0 + gateEnabled → step gate clears all dirs.
        // Area gate would also clear UP. Combined result: no DIR bits.
        val b = FakeBudget(initialTiles = 0, initialEnabled = true)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val out = gate.process(GbaKey.UP or GbaKey.A, snap(x = 5, y = 0))
        assertEquals(0, out and GbaKey.UP)
        assertEquals(GbaKey.A, out and GbaKey.A)
    }

    // ---- bounce-back --------------------------------------------------------

    @Test fun `single-dir block injects opposite dir for BOUNCE_FRAMES then resumes`() {
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val pos = snap(x = 5, y = 0)

        // Trigger frame: UP cleared AND DOWN injected (the override applies
        // the same frame the bounce latches).
        val triggerOut = gate.process(GbaKey.UP, pos)
        assertEquals(0, triggerOut and GbaKey.UP)
        assertEquals(GbaKey.DOWN, triggerOut and MovementGate.DIR_MASK)

        // Trigger consumed one of the BOUNCE_FRAMES; remaining frames must
        // also output DOWN regardless of what the user presses (and other
        // DPAD bits must be cleared so movement is unambiguous).
        for (frame in 1 until MovementGate.BOUNCE_FRAMES) {
            val out = gate.process(GbaKey.LEFT or GbaKey.A, pos)
            assertEquals(
                "frame $frame: bounce should override DPAD with DOWN",
                GbaKey.DOWN, out and MovementGate.DIR_MASK,
            )
            assertEquals(
                "frame $frame: A should pass through during bounce",
                GbaKey.A, out and GbaKey.A,
            )
        }

        // After the bounce window, raw input passes through. Move snapshot
        // a tile so the gate doesn't re-trigger from the same boundary pos.
        val outAfter = gate.process(GbaKey.LEFT, snap(x = 5, y = 1))
        assertEquals(GbaKey.LEFT, outAfter and GbaKey.LEFT)
        assertEquals(0, outAfter and GbaKey.DOWN)
    }

    @Test fun `bounce step is not charged but walking back into the edge is`() {
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val edge = snap(x = 5, y = 0)
        val bounced = snap(x = 5, y = 1)

        // Block fires; the injected DOWN walks the player one tile back,
        // with the position committing partway through the bounce window.
        gate.process(GbaKey.UP, edge)
        for (frame in 1 until 10) gate.process(GbaKey.UP, edge)
        for (frame in 10 until MovementGate.BOUNCE_FRAMES) gate.process(GbaKey.UP, bounced)
        assertEquals("forced bounce step must be free", 0, b.consumeCalls)

        // The player then chooses to walk back up onto the boundary tile.
        gate.process(GbaKey.UP, bounced)
        gate.process(GbaKey.UP, edge)
        assertEquals("voluntary step back is charged", 1, b.consumeCalls)
    }

    @Test fun `bounce into a wall does not make the next real step free`() {
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val edge = snap(x = 5, y = 0)

        // Bounce is blocked (e.g. wall behind the player): position never
        // changes during or shortly after the bounce window.
        gate.process(GbaKey.UP, edge)
        repeat(MovementGate.BOUNCE_FRAMES * 3) { gate.process(0, edge) }

        // A later, unrelated step to the tile the bounce aimed at is paid.
        gate.process(GbaKey.DOWN, edge)
        gate.process(GbaKey.DOWN, snap(x = 5, y = 1))
        assertEquals(1, b.consumeCalls)
    }

    @Test fun `multi-dir block (warp) does not trigger bounce`() {
        // Warp tiles block all four directions — there is no unambiguous
        // "back" direction, so the gate should mask without latching a bounce.
        val b = FakeBudget(initialTiles = 10)
        val allDirs = GbaKey.UP or GbaKey.DOWN or GbaKey.LEFT or GbaKey.RIGHT
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 12, 8),
            blockedDirMask = allDirs,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val pos = snap(x = 12, y = 8)

        gate.process(GbaKey.RIGHT, pos)
        // Next frame: if a bounce had latched, we'd see one of the dirs
        // injected. Since this was a warp-style multi-dir block, no bounce.
        val out = gate.process(GbaKey.A, pos)
        assertEquals(0, out and MovementGate.DIR_MASK)
        assertEquals(GbaKey.A, out and GbaKey.A)
    }

    @Test fun `bounce does not retrigger while still in flight`() {
        // While the bounce countdown is active, the gate keeps evaluating
        // and may keep returning shouldBlock — but we shouldn't reset the
        // counter or change direction; one bounce per boundary contact.
        val b = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(
            blockOnTile = Triple(0, 5, 0),
            blockedDirMask = GbaKey.UP,
        )
        val gate = MovementGate(b, initialAreaGate = areaGate)
        val pos = snap(x = 5, y = 0)

        gate.process(GbaKey.UP, pos)
        // Half-way through the bounce, simulate the user keeping UP held —
        // the gate should still output DOWN, not flip direction or restart.
        val midFrame = MovementGate.BOUNCE_FRAMES / 2
        repeat(midFrame) { gate.process(GbaKey.UP, pos) }
        // Remaining bounce frames after we already burned (1 + midFrame).
        val remaining = MovementGate.BOUNCE_FRAMES - 1 - midFrame
        repeat(remaining) {
            val out = gate.process(GbaKey.UP, pos)
            assertEquals(GbaKey.DOWN, out and MovementGate.DIR_MASK)
        }
        // After window ends, with snapshot moved off the boundary tile:
        val outAfter = gate.process(GbaKey.UP, snap(x = 5, y = 1))
        assertEquals(GbaKey.UP, outAfter and GbaKey.UP)
    }

    // ---- persistent lock-chip ----------------------------------------------

    @Test fun `persistent chip latches on block and stays through bounce window`() {
        val budget = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(blockOnTile = Triple(0, 5, 0), blockedDirMask = GbaKey.UP)
        val gate = MovementGate(budget, areaGate)

        assertEquals(AreaGateDecision.NONE, gate.persistentAreaGateDecision.value)

        gate.process(GbaKey.UP, snap(x = 5, y = 0))
        assertTrue(gate.persistentAreaGateDecision.value.shouldBlock)

        // Snapshot doesn't change → anchor doesn't move → chip stays
        // visible across every bounce frame.
        for (i in 1 until MovementGate.BOUNCE_FRAMES) {
            gate.process(GbaKey.UP, snap(x = 5, y = 0))
            assertTrue(
                "chip should persist through bounce frame $i",
                gate.persistentAreaGateDecision.value.shouldBlock,
            )
        }
    }

    @Test fun `persistent chip clears once player walks off post-bounce tile`() {
        val budget = FakeBudget(initialTiles = 10)
        val areaGate = FakeAreaGate(blockOnTile = Triple(0, 5, 0), blockedDirMask = GbaKey.UP)
        val gate = MovementGate(budget, areaGate)

        gate.process(GbaKey.UP, snap(x = 5, y = 0))
        // Let the bounce decrement to completion without re-pressing UP
        // (keeping UP held would re-trigger the gate every frame).
        repeat(MovementGate.BOUNCE_FRAMES) {
            gate.process(0, snap(x = 5, y = 0))
        }
        assertTrue(gate.persistentAreaGateDecision.value.shouldBlock)

        // Player steps off the anchor → chip clears.
        gate.process(0, snap(x = 5, y = 1))
        assertFalse(gate.persistentAreaGateDecision.value.shouldBlock)
    }

    @Test fun `persistent chip clears for multi-dir warp block when player steps away`() {
        val budget = FakeBudget(initialTiles = 10)
        val allDirs = GbaKey.UP or GbaKey.DOWN or GbaKey.LEFT or GbaKey.RIGHT
        val areaGate = FakeAreaGate(blockOnTile = Triple(0, 12, 8), blockedDirMask = allDirs)
        val gate = MovementGate(budget, areaGate)

        // Multi-dir block: no bounce, but chip still latches.
        gate.process(GbaKey.RIGHT, snap(x = 12, y = 8))
        assertTrue(gate.persistentAreaGateDecision.value.shouldBlock)

        // Staying in place keeps the chip.
        gate.process(0, snap(x = 12, y = 8))
        assertTrue(gate.persistentAreaGateDecision.value.shouldBlock)

        // Walking away clears it.
        gate.process(GbaKey.LEFT, snap(x = 11, y = 8))
        assertFalse(gate.persistentAreaGateDecision.value.shouldBlock)
    }
}
