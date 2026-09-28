package com.poketrek.moneo.audio

import com.poketrek.moneo.data.TtsLanguage
import com.poketrek.moneo.srs.Rating
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class WalkReviewSessionTest {

    private class FakeCards(ids: List<String>) : WalkReviewSession.CardSource {
        val due = Collections.synchronizedList(ids.toMutableList())
        val graded = Collections.synchronizedList(mutableListOf<Pair<String, Rating>>())
        override fun next(skip: Set<String>) = due.firstOrNull { it !in skip }?.let {
            WalkReviewSession.Card(it, "ko:$it", TtsLanguage.KOREAN, "en:$it", TtsLanguage.ENGLISH)
        }
        override fun grade(id: String, rating: Rating) { graded += id to rating; due.remove(id) }
    }

    private val spoken = Collections.synchronizedList(mutableListOf<String>())
    private val speaker = WalkReviewSession.Speaker { text, _ -> spoken += text; delay(5); true }
    private val fast = WalkReviewSession.Timing(thinkMs = 40, gradeWindowMs = 80, maxSkipsBeforePause = 2)

    private suspend fun WalkReviewSession.waitFor(phase: WalkReviewSession.Phase, cardId: String? = null) =
        withTimeout(2_000) {
            while (state.value.phase != phase || (cardId != null && state.value.card?.id != cardId)) delay(2)
        }

    @Test fun speaksFrontThenBackAndAppliesTheGrade() = runBlocking {
        val cards = FakeCards(listOf("a", "b"))
        val s = WalkReviewSession(cards, speaker, this, fast)
        s.start()
        s.waitFor(WalkReviewSession.Phase.AWAITING_GRADE, "a")
        s.grade(Rating.GOOD)
        s.waitFor(WalkReviewSession.Phase.AWAITING_GRADE, "b")
        s.grade(Rating.AGAIN)
        s.waitFor(WalkReviewSession.Phase.FINISHED)
        assertEquals(listOf("a" to Rating.GOOD, "b" to Rating.AGAIN), cards.graded.toList())
        assertEquals(listOf("ko:a", "en:a", "ko:b", "en:b"), spoken.toList())
        assertEquals(2, s.state.value.reviewed)
    }

    @Test fun earlyGradeStillSpeaksTheBackThenMovesOn() = runBlocking {
        val cards = FakeCards(listOf("a"))
        val s = WalkReviewSession(cards, speaker, this, fast.copy(thinkMs = 5_000))
        s.start()
        s.waitFor(WalkReviewSession.Phase.THINKING, "a")
        s.grade(Rating.GOOD)  // tapped while thinking: no 5 s wait
        s.waitFor(WalkReviewSession.Phase.FINISHED)
        assertEquals(listOf("ko:a", "en:a"), spoken.toList())
        assertEquals(listOf("a" to Rating.GOOD), cards.graded.toList())
    }

    @Test fun gradeWhileTheFrontIsStillBeingSpokenCounts() = runBlocking {
        val cards = FakeCards(listOf("a"))
        val slow = WalkReviewSession.Speaker { text, _ -> spoken += text; delay(150); true }
        val s = WalkReviewSession(cards, slow, this, fast.copy(thinkMs = 5_000))
        s.start()
        s.waitFor(WalkReviewSession.Phase.FRONT, "a")
        s.grade(Rating.GOOD)  // tapped mid-word: no think pause, back still spoken
        s.waitFor(WalkReviewSession.Phase.FINISHED)
        assertEquals(listOf("ko:a", "en:a"), spoken.toList())
        assertEquals(listOf("a" to Rating.GOOD), cards.graded.toList())
    }

    @Test fun gradesWhileIdleAreIgnored() = runBlocking {
        val cards = FakeCards(listOf("a"))
        val s = WalkReviewSession(cards, speaker, this, fast)
        s.grade(Rating.GOOD)  // idle
        s.start()
        s.waitFor(WalkReviewSession.Phase.AWAITING_GRADE, "a")
        s.grade(Rating.AGAIN)
        s.waitFor(WalkReviewSession.Phase.FINISHED)
        assertEquals(listOf("a" to Rating.AGAIN), cards.graded.toList())
    }

    @Test fun skippedCardsAreNotGradedAndTheSessionPausesAfterTwo() = runBlocking {
        val cards = FakeCards(listOf("a", "b", "c"))
        val s = WalkReviewSession(cards, speaker, this, fast)
        val notices = Collections.synchronizedList(mutableListOf<String>())
        s.announce = { notices += it }
        s.start()
        s.waitFor(WalkReviewSession.Phase.PAUSED)
        assertEquals(emptyList<Pair<String, Rating>>(), cards.graded.toList())
        assertEquals(listOf("ko:a", "en:a", "ko:b", "en:b"), spoken.toList())  // no repeats of a skipped card
        assertEquals(listOf("paused"), notices.toList())
        s.togglePause()  // resume: continues with the next unskipped card
        s.waitFor(WalkReviewSession.Phase.AWAITING_GRADE, "c")
        s.grade(Rating.GOOD)
        s.waitFor(WalkReviewSession.Phase.FINISHED)
        assertEquals(listOf("c" to Rating.GOOD), cards.graded.toList())
    }

    @Test fun pauseRequestTakesEffectAfterTheCurrentCard() = runBlocking {
        val cards = FakeCards(listOf("a", "b"))
        val s = WalkReviewSession(cards, speaker, this, fast)
        s.start()
        s.waitFor(WalkReviewSession.Phase.AWAITING_GRADE, "a")
        s.togglePause()
        s.grade(Rating.GOOD)
        s.waitFor(WalkReviewSession.Phase.PAUSED)
        assertEquals(listOf("a" to Rating.GOOD), cards.graded.toList())
        s.stop()
    }
}
