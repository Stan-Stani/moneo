package com.poketrek.step

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTallyTest {

    @Test fun sameDayAccumulates() {
        val t = DailyTally(100).addSteps(100, 40).addSteps(100, 2).addTilesSpent(100, 1).addTilesSpent(100, 1)
        assertEquals(DailyTally(100, steps = 42, tilesSpent = 2), t)
    }

    @Test fun aNewDayStartsFromZero() {
        val t = DailyTally(100, steps = 5000, tilesSpent = 300)
        assertEquals(DailyTally(101, steps = 7), t.addSteps(101, 7))
        assertEquals(DailyTally(101, tilesSpent = 1), t.addTilesSpent(101, 1))
    }

    @Test fun onReadsAnOldTallyAsEmpty() {
        val t = DailyTally(100, steps = 5000, tilesSpent = 300)
        assertEquals(t, t.on(100))
        assertEquals(DailyTally(101), t.on(101))
    }
}
