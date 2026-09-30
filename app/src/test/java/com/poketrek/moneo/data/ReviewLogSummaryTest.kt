package com.poketrek.moneo.data

import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReviewLogSummaryTest {

    @Test fun countsReviewsAndFirstStudies() {
        val entries = listOf(
            ReviewLog.Entry("a", Rating.GOOD, 1, CardState.NEW),
            ReviewLog.Entry("a", Rating.GOOD, 2, CardState.LEARNING),
            ReviewLog.Entry("b", Rating.AGAIN, 3, CardState.REVIEW),
        )
        assertEquals(ReviewLog.Summary(reviews = 3, newWords = 1), ReviewLog.summarize(entries))
    }

    @Test fun startOfDayIsLocalMidnight() {
        val zone = ZoneId.of("Asia/Seoul")
        val evening = ZonedDateTime.of(2026, 9, 30, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 9, 30, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(midnight, ReviewLog.startOfDayMs(evening, zone))
    }
}
