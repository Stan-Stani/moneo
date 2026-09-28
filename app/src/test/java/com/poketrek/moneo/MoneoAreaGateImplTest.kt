package com.poketrek.moneo

import com.poketrek.emu.AreaGateDecision
import com.poketrek.emu.GbaKey
import com.poketrek.emu.LeafGreenRam
import com.poketrek.moneo.data.MapBoundaryLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneoAreaGateImplTest {

    private val boundaryJson = """
        {
          "version": 1,
          "boundaries": {
            "1:0": [
              {"x":5,"y":0,"dir":"up","destBank":1,"destMapId":19,"destArea":"route_1","kind":"edge"},
              {"x":12,"y":8,"dir":null,"destBank":2,"destMapId":0,"destArea":"viridian_forest","kind":"warp"}
            ]
          }
        }
    """.trimIndent()

    private fun snap(
        bank: Int,
        mapId: Int,
        x: Int,
        y: Int,
        saveBlockPtr: Int = 0x02000000,
    ) = LeafGreenRam.Snapshot(
        playerX = x,
        playerY = y,
        mapId = mapId,
        mapBank = bank,
        movingStatus = 0,
        saveBlockPtr = saveBlockPtr,
    )

    private class FakeConfig(
        override val enabled: Boolean = true,
        override val thresholdPct: Int = 80,
    ) : AreaGateConfig

    private class FakeOracle(
        private val byArea: Map<String, Float>,
    ) : MaturityOracle {
        override fun maturityFraction(areaId: String): Float = byArea[areaId] ?: 1f
    }

    @Test
    fun `gate disabled never blocks`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = false, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `unsupported ROM never blocks even on a real boundary`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
            isRomSupported = { false },
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `save block not initialised yields NONE`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        val snapshot = LeafGreenRam.Snapshot(
            playerX = 5,
            playerY = 0,
            mapId = 0,
            mapBank = 1,
            movingStatus = 0,
            saveBlockPtr = 0,
        )
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `empty boundaries yields NONE`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(emptyMap()),
        )
        // map not in boundaryJson
        val snapshot = snap(bank = 99, mapId = 99, x = 0, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `edge blocks when pressing matching dir and below threshold`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0.5f)),
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertTrue(decision.shouldBlock)
        assertEquals(GbaKey.UP, decision.blockedDirMask)
        assertEquals("route_1", decision.destArea)
        assertEquals(0.5f, decision.maturityFraction)
        assertEquals(0.8f, decision.thresholdFraction)
    }

    @Test
    fun `edge does not block when above threshold`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0.9f)),
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `edge does not block wrong direction`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        val decision = gate.evaluate(GbaKey.LEFT, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `edge does not block off edge tile`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        // one tile left of the edge
        val snapshot = snap(bank = 1, mapId = 0, x = 4, y = 0)
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, decision)
    }

    @Test
    fun `warp blocks all directions when standing on it`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("viridian_forest" to 0f)),
        )
        val snapshot = snap(bank = 1, mapId = 0, x = 12, y = 8)
        // pressing any direction – warp blocks all four
        val decision = gate.evaluate(GbaKey.UP, snapshot)
        assertTrue(decision.shouldBlock)
        val allDirs = GbaKey.UP or GbaKey.DOWN or GbaKey.LEFT or GbaKey.RIGHT
        assertEquals(allDirs, decision.blockedDirMask)
        assertEquals("viridian_forest", decision.destArea)
    }

    @Test
    fun `adjacent warp blocks only the toward dir`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("viridian_forest" to 0f)),
        )
        // left of the warp at (12,8) – (11,8)
        val snapshot = snap(bank = 1, mapId = 0, x = 11, y = 8)

        // pressing RIGHT toward the warp – should block that direction
        val decisionRight = gate.evaluate(GbaKey.RIGHT, snapshot)
        assertTrue(decisionRight.shouldBlock)
        assertEquals(GbaKey.RIGHT, decisionRight.blockedDirMask)

        // pressing LEFT instead – should not block
        val decisionLeft = gate.evaluate(GbaKey.LEFT, snapshot)
        assertEquals(AreaGateDecision.NONE, decisionLeft)
    }

    @Test
    fun `last decision updates and is exposed`() {
        val lookup = MapBoundaryLookup.parse(boundaryJson)
        val gate = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = true, thresholdPct = 80),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        // first call: should block
        val snapshot = snap(bank = 1, mapId = 0, x = 5, y = 0)
        gate.evaluate(GbaKey.UP, snapshot)
        assertTrue(gate.lastDecision.value.shouldBlock)

        // second call with gate disabled – decision becomes NONE
        val gateDisabled = MoneoAreaGateImpl(
            lookup,
            FakeConfig(enabled = false),
            FakeOracle(mapOf("route_1" to 0f)),
        )
        gateDisabled.evaluate(GbaKey.UP, snapshot)
        assertEquals(AreaGateDecision.NONE, gateDisabled.lastDecision.value)
    }

    private class FakeVisited(initial: Set<String> = emptySet()) : VisitedAreas {
        val areas = initial.toMutableSet()
        override fun isVisited(areaId: String) = areaId in areas
        override fun markVisited(areaId: String) { areas += areaId }
    }

    private val mapAreas = mapOf((1 to 0) to "pallet_town", (1 to 19) to "route_1")

    private fun gateWithVisits(
        visited: FakeVisited,
        enabled: Boolean = true,
        maturity: Map<String, Float> = mapOf("route_1" to 0f, "viridian_forest" to 0f),
    ) = MoneoAreaGateImpl(
        MapBoundaryLookup.parse(boundaryJson),
        FakeConfig(enabled = enabled),
        FakeOracle(maturity),
        currentArea = { bank, id -> mapAreas[bank to id] },
        visited = visited,
    )

    @Test
    fun `standing in an area records it as visited`() {
        val visited = FakeVisited()
        gateWithVisits(visited).evaluate(0, snap(bank = 1, mapId = 0, x = 3, y = 3))
        assertEquals(setOf("pallet_town"), visited.areas)
    }

    @Test
    fun `visits are recorded while the gate is off`() {
        val visited = FakeVisited()
        gateWithVisits(visited, enabled = false).evaluate(0, snap(bank = 1, mapId = 0, x = 3, y = 3))
        assertEquals(setOf("pallet_town"), visited.areas)
    }

    @Test
    fun `no visit recorded before the save block exists`() {
        val visited = FakeVisited()
        gateWithVisits(visited).evaluate(0, snap(bank = 1, mapId = 0, x = 3, y = 3, saveBlockPtr = 0))
        assertTrue(visited.areas.isEmpty())
    }

    @Test
    fun `edge into a visited area is not blocked even when immature`() {
        val gate = gateWithVisits(FakeVisited(setOf("route_1")))
        assertEquals(AreaGateDecision.NONE, gate.evaluate(GbaKey.UP, snap(bank = 1, mapId = 0, x = 5, y = 0)))
    }

    @Test
    fun `standing on a warp to a visited area is not blocked`() {
        val gate = gateWithVisits(FakeVisited(setOf("viridian_forest")))
        assertEquals(AreaGateDecision.NONE, gate.evaluate(GbaKey.UP, snap(bank = 1, mapId = 0, x = 12, y = 8)))
    }

    @Test
    fun `unvisited destination still blocks alongside a visited one`() {
        // Standing next to the warp (12,8) from (11,8): RIGHT goes to the
        // unvisited forest; route_1 is visited but irrelevant here.
        val gate = gateWithVisits(FakeVisited(setOf("route_1")))
        val d = gate.evaluate(GbaKey.RIGHT, snap(bank = 1, mapId = 0, x = 11, y = 8))
        assertTrue(d.shouldBlock)
        assertEquals("viridian_forest", d.destArea)
    }
}
