package com.poketrek.moneo.data

import com.poketrek.moneo.srs.CardSnapshot
import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class StudyDigestTest {

    private val day = 86_400_000L
    private val now = 1_790_000_000_000L

    private fun vocab(id: String, korean: String, gloss: String) =
        id to VocabEntry(id, korean, gloss, "noun", "pallet_town", "test")

    private val vocab = mapOf(
        vocab("a", "간판", "signboard"),
        vocab("b", "도움", "help"),
        vocab("c", "연구소", "laboratory"),
        vocab("d", "박사", "professor"),
    )

    private fun card(id: String, state: CardState, lastMs: Long?, lapses: Int = 0, reps: Int = 0, suspended: Boolean = false) =
        id to CardRecord(id, CardSnapshot(state = state, lapses = lapses, reps = reps), 0, lastMs, suspended)

    private fun entry(id: String, r: Rating, daysAgo: Int, before: CardState = CardState.REVIEW) =
        ReviewLog.Entry(id, r, now - daysAgo * day, before)

    @Test fun missesAndFirstStudiesFromTheLog() {
        val cards = mapOf(
            card("a", CardState.LEARNING, now - day),
            card("b", CardState.REVIEW, now - day),
            card("c", CardState.LEARNING, now - 2 * day),
        )
        val entries = listOf(
            entry("a", Rating.AGAIN, 3), entry("a", Rating.HARD, 1), entry("a", Rating.AGAIN, 1),
            entry("b", Rating.GOOD, 1),  // an old card graded Good: neither list
            entry("c", Rating.GOOD, 2, before = CardState.NEW),
            entry("d", Rating.AGAIN, 20),  // outside the window
        )
        val d = StudyDigest.collect(entries, logStartMs = now - 30 * day, cards, vocab, { null }, now)
        assertEquals(listOf("간판"), d.struggling.map { it.korean })
        assertEquals(2, d.struggling[0].again)
        assertEquals(1, d.struggling[0].hard)
        assertEquals(listOf("연구소"), d.learned.map { it.korean })
    }

    @Test fun aNewWordMissedLatelyIsListedOnceAsStruggling() {
        val entries = listOf(entry("c", Rating.GOOD, 3, before = CardState.NEW), entry("c", Rating.AGAIN, 1))
        val d = StudyDigest.collect(entries, now - 30 * day, mapOf(card("c", CardState.LEARNING, now - day)), vocab, { null }, now)
        assertEquals(listOf("연구소"), d.struggling.map { it.korean })
        assertTrue(d.learned.isEmpty())
    }

    @Test fun cardStateStandsInBeforeTheLogCoversTheWindow() {
        val cards = mapOf(
            card("a", CardState.REVIEW, now - day, lapses = 2),
            card("c", CardState.REVIEW, now - day, reps = 1),
            card("b", CardState.REVIEW, now - day, reps = 5),       // long known
            card("d", CardState.LEARNING, now - 30 * day),          // not lately
        )
        val d = StudyDigest.collect(emptyList(), logStartMs = null, cards, vocab, { null }, now)
        assertEquals(listOf("간판"), d.struggling.map { it.korean })
        assertTrue(d.struggling[0].estimated)
        assertEquals(listOf("연구소"), d.learned.map { it.korean })
    }

    @Test fun noStandInOnceTheLogCoversTheWindow() {
        val cards = mapOf(card("a", CardState.REVIEW, now - day, lapses = 2))
        val d = StudyDigest.collect(emptyList(), logStartMs = now - 30 * day, cards, vocab, { null }, now)
        assertTrue(d.struggling.isEmpty())
    }

    @Test fun suspendedCardsAreLeftOut() {
        val cards = mapOf(card("a", CardState.REVIEW, now - day, suspended = true))
        val d = StudyDigest.collect(listOf(entry("a", Rating.AGAIN, 1)), now - 30 * day, cards, vocab, { null }, now)
        assertTrue(d.struggling.isEmpty())
    }

    @Test fun markdownLists() {
        val entries = listOf(entry("a", Rating.AGAIN, 1), entry("c", Rating.GOOD, 1, before = CardState.NEW))
        val cards = mapOf(card("a", CardState.LEARNING, now - day), card("c", CardState.LEARNING, now - day))
        val d = StudyDigest.collect(entries, now - 30 * day, cards, vocab, { if (it == "a") "간판은 도움이 되지!" else null }, now)
        val md = StudyDigest.markdown(d, now, ZoneOffset.UTC)
        assertTrue(md, md.contains("- 간판 — signboard. Again ×1. Last Sep 20, still learning.\n  Example: 간판은 도움이 되지!"))
        assertTrue(md, md.contains("- 연구소 — laboratory. Last Sep 20, still learning.\n"))
    }

    @Test fun logLinesRoundTrip() {
        val e = ReviewLog.Entry("mined:간판", Rating.HARD, 123L, CardState.NEW)
        assertEquals(e, ReviewLog.parse(ReviewLog.format(e)))
        assertEquals(null, ReviewLog.parse("not json"))
    }
}
