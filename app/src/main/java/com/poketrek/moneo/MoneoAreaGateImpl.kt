package com.poketrek.moneo

import com.poketrek.emu.AreaGateDecision
import com.poketrek.emu.GbaKey
import com.poketrek.emu.LeafGreenRam
import com.poketrek.emu.MoneoAreaGate
import com.poketrek.moneo.data.MapAreaLookup
import com.poketrek.moneo.data.MapBoundaryLookup
import com.poketrek.moneo.data.MoneoPrefs
import com.poketrek.moneo.data.MoneoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny oracle the area gate consults for area readiness. Letting the gate
 * take this interface (instead of [MoneoRepository]) keeps it testable on
 * the plain JVM. The production path forwards to [MoneoRepository.readiness]
 * (share of the area's text the player can read).
 */
fun interface MaturityOracle {
    /** Readiness (0..1) for entering [areaId]. */
    fun maturityFraction(areaId: String): Float
}

/**
 * Tiny configuration the area gate consults each frame. Same testability
 * motivation as [MaturityOracle].
 */
interface AreaGateConfig {
    val enabled: Boolean
    val thresholdPct: Int

    /** Threshold for entering [areaId]; production ramps it (see GateThreshold). */
    fun thresholdPctFor(areaId: String): Int = thresholdPct
}

/**
 * Areas the player has already been in. The gate records the current area
 * every frame and never blocks a transition into a recorded one: an area's
 * gate is for entering it the first time, not for going back (the story
 * sends the player back to Pallet with Oak's Parcel), and lapsed reviews
 * shouldn't lock the player out of a town they already reached.
 */
interface VisitedAreas {
    fun isVisited(areaId: String): Boolean
    fun markVisited(areaId: String)
}

/**
 * Concrete implementation of [MoneoAreaGate] that uses [MapBoundaryLookup] to detect
 * area boundaries and warps, [MoneoPrefs] to read the user's enable toggle and maturity
 * threshold, and [MoneoRepository] to obtain the actual maturity of the destination area.
 *
 * Each call to [evaluate] checks whether any currently pressed direction would step
 * into a not-yet-mature Moneo Area and returns an [AreaGateDecision] that may block one
 * or more direction bits. The latest decision is exposed through [lastDecision] so the
 * HUD can display a lock chip without re-running the full evaluation.
 */
class MoneoAreaGateImpl(
    private val boundaries: MapBoundaryLookup,
    private val config: AreaGateConfig,
    private val oracle: MaturityOracle,
    /**
     * Per-frame check: is the currently-loaded ROM one for which boundary
     * data is valid? Returns false if no ROM is loaded, the ROM hasn't been
     * identified yet, or the variant isn't on the supported list. The
     * boundary_tiles.json asset is keyed for the 2024 Korean patch's
     * bank/mapId numbering — running it against a different ROM would mask
     * directions on tiles that belong to entirely different maps.
     */
    private val isRomSupported: () -> Boolean = { true },
    /** Area of the player's current map, or null if unknown. */
    private val currentArea: (mapBank: Int, mapId: Int) -> String? = { _, _ -> null },
    private val visited: VisitedAreas? = null,
) : MoneoAreaGate {

    private val _last = MutableStateFlow(AreaGateDecision.NONE)
    override val lastDecision: StateFlow<AreaGateDecision> = _last.asStateFlow()

    override fun evaluate(rawKeys: Int, snapshot: LeafGreenRam.Snapshot): AreaGateDecision {
        // Loaded ROM isn't on the supported list → no blocking
        if (!isRomSupported()) {
            return updateAndReturn(AreaGateDecision.NONE)
        }

        // SaveBlock not yet initialised (title screen / intro). Avoid querying
        // the lookup with garbage coordinates.
        if (snapshot.saveBlockPtr == 0) {
            return updateAndReturn(AreaGateDecision.NONE)
        }

        // Record visits even with the gate off, so turning it on later
        // doesn't wall the player out of places they've already been.
        if (visited != null) {
            currentArea(snapshot.mapBank, snapshot.mapId)?.let { visited.markVisited(it) }
        }

        if (!config.enabled) {
            return updateAndReturn(AreaGateDecision.NONE)
        }

        val list = boundaries.boundariesFor(snapshot.mapBank, snapshot.mapId)
            .filter { visited?.isVisited(it.destArea) != true }
        if (list.isEmpty()) return updateAndReturn(AreaGateDecision.NONE)

        var blockedMask = 0
        var firstHitArea: String? = null
        var firstHitMaturity = 0f
        var firstHitThreshold = 0f
        fun thresholdFrac(areaId: String) = config.thresholdPctFor(areaId).coerceIn(0, 100) / 100f

        // Edge case: standing on a warp tile (any pressed dir would trigger
        // the warp on the next step). Block all four directions.
        val warpHere = list.firstOrNull {
            it.kind == "warp" && it.x == snapshot.playerX && it.y == snapshot.playerY
        }
        if (warpHere != null) {
            val mat = oracle.maturityFraction(warpHere.destArea)
            val threshold = thresholdFrac(warpHere.destArea)
            if (mat < threshold) {
                blockedMask = blockedMask or DIR_MASK
                firstHitArea = warpHere.destArea
                firstHitMaturity = mat
                firstHitThreshold = threshold
            }
        }

        // Edge boundaries: only if pressing the matching direction.
        // Adjacent warp: pressing toward a warp tile.
        val pressedDirs: List<Pair<String, Int>> = listOf(
            "up" to GbaKey.UP,
            "down" to GbaKey.DOWN,
            "left" to GbaKey.LEFT,
            "right" to GbaKey.RIGHT,
        )
        for ((dirName, dirBit) in pressedDirs) {
            if ((rawKeys and dirBit) == 0) continue

            // (a) Edge at current tile?
            val edge = list.firstOrNull {
                it.kind == "edge" && it.x == snapshot.playerX && it.y == snapshot.playerY && it.dir == dirName
            }
            if (edge != null) {
                val mat = oracle.maturityFraction(edge.destArea)
                val threshold = thresholdFrac(edge.destArea)
                if (mat < threshold) {
                    blockedMask = blockedMask or dirBit
                    if (firstHitArea == null) {
                        firstHitArea = edge.destArea
                        firstHitMaturity = mat
                        firstHitThreshold = threshold
                    }
                }
                continue
            }

            // (b) Adjacent warp tile (pressing toward it)?
            val (ax, ay) = adjacent(snapshot.playerX, snapshot.playerY, dirName)
            val warp = list.firstOrNull { it.kind == "warp" && it.x == ax && it.y == ay }
            if (warp != null) {
                val mat = oracle.maturityFraction(warp.destArea)
                val threshold = thresholdFrac(warp.destArea)
                if (mat < threshold) {
                    blockedMask = blockedMask or dirBit
                    if (firstHitArea == null) {
                        firstHitArea = warp.destArea
                        firstHitMaturity = mat
                        firstHitThreshold = threshold
                    }
                }
            }
        }

        if (blockedMask == 0) {
            return updateAndReturn(AreaGateDecision.NONE)
        }
        val dec = AreaGateDecision(
            shouldBlock = true,
            blockedDirMask = blockedMask,
            destArea = firstHitArea,
            maturityFraction = firstHitMaturity,
            thresholdFraction = firstHitThreshold,
        )
        return updateAndReturn(dec)
    }


    private fun updateAndReturn(decision: AreaGateDecision): AreaGateDecision {
        if (_last.value != decision) _last.value = decision
        return decision
    }

    private fun adjacent(x: Int, y: Int, dir: String): Pair<Int, Int> = when (dir) {
        "up" -> x to (y - 1)
        "down" -> x to (y + 1)
        "left" -> (x - 1) to y
        "right" -> (x + 1) to y
        else -> x to y
    }

    companion object {
        private const val DIR_MASK = GbaKey.RIGHT or GbaKey.LEFT or GbaKey.UP or GbaKey.DOWN

        /** Build the production area gate from concrete Moneo singletons. */
        fun create(
            boundaries: MapBoundaryLookup,
            prefs: MoneoPrefs,
            repo: MoneoRepository,
            lemmaCounts: com.poketrek.moneo.data.AreaLemmaCounts =
                com.poketrek.moneo.data.AreaLemmaCounts.EMPTY,
            isRomSupported: () -> Boolean = { true },
            mapAreas: MapAreaLookup? = null,
            /** Key for the loaded ROM (e.g. its CRC), or null before one is loaded. */
            romKey: () -> String? = { null },
        ): MoneoAreaGateImpl {
            val cfg = object : AreaGateConfig {
                override val enabled: Boolean get() = prefs.areaGateEnabled.value
                override val thresholdPct: Int get() = prefs.areaGateThresholdPct.value
                override fun thresholdPctFor(areaId: String): Int =
                    com.poketrek.moneo.data.GateThreshold.pctFor(lemmaCounts.storyIndex(areaId), thresholdPct)
            }
            val oracle = MaturityOracle { areaId -> repo.readiness(areaId) }
            val visited = object : VisitedAreas {
                override fun isVisited(areaId: String): Boolean =
                    romKey()?.let { prefs.isAreaVisited(it, areaId) } ?: false
                override fun markVisited(areaId: String) {
                    romKey()?.let { prefs.markAreaVisited(it, areaId) }
                }
            }
            return MoneoAreaGateImpl(
                boundaries, cfg, oracle, isRomSupported,
                currentArea = { bank, id -> mapAreas?.areaIdFor(bank, id) },
                visited = visited,
            )
        }
    }
}