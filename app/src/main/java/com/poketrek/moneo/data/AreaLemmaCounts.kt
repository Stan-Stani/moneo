package com.poketrek.moneo.data

import android.content.Context
import org.json.JSONObject

/**
 * How often each deck lemma occurs in each area's reachable dialog, shipped
 * as `assets/moneo/area_lemma_counts.json` (built by
 * `tools/moneo/build_area_lemma_counts.py`). The area gate uses it to
 * measure how much of an area's text the player can read, and the study
 * queue to teach an area's most frequent words first.
 *
 * [storyOrder] is the order a first playthrough enters areas; the gate
 * threshold ramps along it (see [GateThreshold]).
 */
class AreaLemmaCounts(
    val storyOrder: List<String>,
    private val byArea: Map<String, Map<String, Int>>,
) {
    /** Lemma → token count for [areaId], or null if the area has no data. */
    fun countsFor(areaId: String): Map<String, Int>? = byArea[areaId]

    /** Position of [areaId] in [storyOrder], or -1 if it's not on the path. */
    fun storyIndex(areaId: String): Int = storyOrder.indexOf(areaId)

    companion object {
        val EMPTY = AreaLemmaCounts(emptyList(), emptyMap())

        fun parse(json: String): AreaLemmaCounts {
            val root = JSONObject(json)
            val orderArr = root.optJSONArray("storyOrder")
            val order = if (orderArr == null) emptyList()
            else (0 until orderArr.length()).map { orderArr.getString(it) }
            val areasObj = root.getJSONObject("areas")
            val out = HashMap<String, Map<String, Int>>()
            for (areaId in areasObj.keys()) {
                val counts = areasObj.optJSONObject(areaId) ?: continue
                val m = HashMap<String, Int>(counts.length())
                for (lemma in counts.keys()) {
                    val n = counts.optInt(lemma, 0)
                    if (n > 0) m[lemma] = n
                }
                out[areaId] = m
            }
            return AreaLemmaCounts(order, out)
        }

        fun loadFromAssets(
            context: Context,
            path: String = "moneo/area_lemma_counts.json",
        ): AreaLemmaCounts {
            val json = context.assets.open(path).use { it.readBytes() }.toString(Charsets.UTF_8)
            return parse(json)
        }
    }
}

/**
 * Coverage threshold for entering an area. Early areas' text is dominated
 * by everyday words, so a flat threshold front-loads hundreds of cards
 * before Viridian City. Instead the threshold rises linearly from
 * [RAMP_START_PCT] at the first story area to the user's [finalPct] at
 * story index [RAMP_LENGTH] - 1 and stays there (simulated in
 * tools/moneo/gate_coverage_sim.py: 15-90 new words per early gate rather
 * than 224 up front). Areas off the story path use [finalPct].
 */
object GateThreshold {
    const val RAMP_START_PCT = 60
    const val RAMP_LENGTH = 10

    fun pctFor(storyIndex: Int, finalPct: Int): Int {
        if (storyIndex < 0) return finalPct
        val start = minOf(RAMP_START_PCT, finalPct)
        val t = minOf(storyIndex, RAMP_LENGTH - 1).toFloat() / (RAMP_LENGTH - 1)
        return Math.round(start + (finalPct - start) * t)
    }
}
