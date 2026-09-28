package com.poketrek.moneo

import com.poketrek.moneo.data.Area
import com.poketrek.moneo.data.AreaLemmaCounts
import com.poketrek.moneo.data.CardRecord
import com.poketrek.moneo.data.GateThreshold
import com.poketrek.moneo.data.MoneoCardStore
import com.poketrek.moneo.data.MoneoRepository
import com.poketrek.moneo.data.VocabEntry
import com.poketrek.moneo.srs.CardSnapshot
import com.poketrek.moneo.srs.CardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Text-coverage area gate: readiness weights each word by how often the
 * area's dialog uses it, the study queue teaches frequent words first, and
 * the threshold ramps along the story.
 */
class AreaCoverageTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun v(id: String, korean: String, area: String = "route_1", refs: List<String> = emptyList(), tag: String = "t") =
        VocabEntry(
            id = id, korean = korean, gloss = korean, partOfSpeech = "noun",
            areaId = area, sourceTag = tag, areasReferenced = refs,
        )

    private fun repo(
        vocab: List<VocabEntry>,
        records: List<CardRecord> = emptyList(),
        counts: Map<String, Map<String, Int>>? = null,
    ): MoneoRepository {
        val store = MoneoCardStore(tempFolder.newFolder())
        records.forEach { store.put(it) }
        return MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = listOf(Area("pallet_town", "P", "태초", 1), Area("route_1", "R1", "1번", 2)),
            now = { now },
        ).also { r -> counts?.let { r.setAreaLemmaCounts(AreaLemmaCounts(it.keys.toList(), it)) } }
    }

    private fun rec(id: String, state: CardState, suspended: Boolean = false) =
        CardRecord(id, CardSnapshot(state = state), createdAt = now, suspended = suspended)

    @Test fun readinessWeightsByTokenCount() {
        val r = repo(
            vocab = listOf(v("a", "포켓몬"), v("b", "가다"), v("c", "잡다")),
            records = listOf(rec("a", CardState.REVIEW), rec("b", CardState.LEARNING)),
            counts = mapOf("route_1" to mapOf("포켓몬" to 6, "가다" to 3, "잡다" to 1)),
        )
        assertEquals(0.6f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun suspendedCountsAsKnown() {
        val r = repo(
            vocab = listOf(v("a", "포켓몬"), v("b", "가다")),
            records = listOf(rec("b", CardState.NEW, suspended = true)),
            counts = mapOf("route_1" to mapOf("포켓몬" to 1, "가다" to 3)),
        )
        assertEquals(0.75f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun anyMatureCardWithTheLemmaMakesItKnown() {
        // The same word can be a card in two decks (TOPIK and mined).
        val r = repo(
            vocab = listOf(v("topik:집", "집"), v("mined:집", "집"), v("x", "길")),
            records = listOf(rec("mined:집", CardState.REVIEW)),
            counts = mapOf("route_1" to mapOf("집" to 1, "길" to 1)),
        )
        assertEquals(0.5f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun hiddenDeckWordsDontCount() {
        val r = repo(
            vocab = listOf(v("a", "포켓몬"), v("s", "피카츄", tag = "rom-species-2024")),
            records = listOf(rec("a", CardState.REVIEW)),
            counts = mapOf("route_1" to mapOf("포켓몬" to 1, "피카츄" to 9)),
        )
        assertEquals(0.1f, r.readiness("route_1"), 1e-6f)
        r.setExcludedSourceTags(setOf("rom-species-2024"))
        assertEquals(1f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun wordsFirstSeenElsewhereStillCount() {
        // Coverage is about the destination's text, not where a card is homed.
        val r = repo(
            vocab = listOf(v("a", "포켓몬", area = "pallet_town", refs = listOf("route_1"))),
            counts = mapOf("route_1" to mapOf("포켓몬" to 5)),
        )
        assertEquals(0f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun noCountsFallsBackToFirstSeenMaturity() {
        val r = repo(
            vocab = listOf(v("a", "포켓몬"), v("b", "가다")),
            records = listOf(rec("a", CardState.REVIEW)),
        )
        assertEquals(r.maturityPct("route_1"), r.readiness("route_1"), 0f)
        assertEquals(0.5f, r.readiness("route_1"), 1e-6f)
    }

    @Test fun newCardsComeInFrequencyOrderAcrossTiers() {
        val r = repo(
            vocab = listOf(
                v("home-rare", "덤불"),
                v("ref-common", "가다", area = "pallet_town", refs = listOf("route_1")),
                v("home-mid", "풀숲"),
            ),
            counts = mapOf("route_1" to mapOf("가다" to 30, "풀숲" to 4, "덤불" to 1)),
        )
        assertEquals("ref-common", r.nextDueCard("route_1")?.first?.vocabId)
        r.grade("ref-common", com.poketrek.moneo.srs.Rating.EASY, now)
        assertEquals("home-mid", r.nextDueCard("route_1")?.first?.vocabId)
    }

    @Test fun duplicateCardOfAKnownWordGoesLast() {
        val r = repo(
            vocab = listOf(v("mined:가다", "가다"), v("topik:가다", "가다"), v("x", "풀숲")),
            counts = mapOf("route_1" to mapOf("가다" to 30, "풀숲" to 4)),
        )
        val first = r.nextDueCard("route_1")!!.first.vocabId
        r.grade(first, com.poketrek.moneo.srs.Rating.EASY, now)
        assertEquals("x", r.nextDueCard("route_1")?.first?.vocabId)
    }

    @Test fun dueLearningCardStillBeatsNewCards() {
        val r = repo(
            vocab = listOf(v("new-common", "가다"), v("learning", "덤불")),
            records = listOf(CardRecord("learning", CardSnapshot(state = CardState.LEARNING, dueAt = now - 1), createdAt = now)),
            counts = mapOf("route_1" to mapOf("가다" to 30, "덤불" to 1)),
        )
        assertEquals("learning", r.nextDueCard("route_1")?.first?.vocabId)
    }

    @Test fun thresholdRampsAlongTheStory() {
        assertEquals(60, GateThreshold.pctFor(0, 90))
        assertEquals(70, GateThreshold.pctFor(3, 90))
        assertEquals(90, GateThreshold.pctFor(9, 90))
        assertEquals(90, GateThreshold.pctFor(30, 90))
        assertEquals(90, GateThreshold.pctFor(-1, 90))
        // A final threshold below the ramp start is flat.
        assertEquals(40, GateThreshold.pctFor(0, 40))
        assertEquals(40, GateThreshold.pctFor(5, 40))
    }

    @Test fun parsesAsset() {
        val c = AreaLemmaCounts.parse(
            """{"version":1,"storyOrder":["pallet_town","route_1"],
               "areas":{"route_1":{"가다":3,"집":1}}}"""
        )
        assertEquals(1, c.storyIndex("route_1"))
        assertEquals(-1, c.storyIndex("cerulean_cave"))
        assertEquals(mapOf("가다" to 3, "집" to 1), c.countsFor("route_1"))
        assertEquals(null, c.countsFor("pallet_town"))
    }
}
