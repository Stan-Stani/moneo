package com.poketrek.moneo.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentLinesTest {

    private var now = 1_000_000L
    private val lines = RecentLines(max = 3, windowMs = 60_000, clock = { now })

    private fun shown(m: String) = DialogReader.OnScreen(m, null)
    private fun messages(l: List<RecentLines.Entry>) = l.map { it.shown.message }

    @Test fun keepsTheLatestDistinctLines() {
        for (m in listOf("a", "a", "b", "", "c", "d")) { lines.add(shown(m)); now += 1_000 }
        assertEquals(listOf("b", "c", "d"), messages(lines.recent()))
    }

    @Test fun forgetsLinesOutsideTheWindow() {
        lines.add(shown("old")); now += 61_000
        lines.add(shown("new"))
        assertEquals(listOf("new"), messages(lines.recent()))
        now += 61_000
        assertTrue(lines.recent().isEmpty())
    }

    @Test fun openBoxIsTheTargetAndNotRepeatedInBefore() {
        lines.add(shown("a")); lines.add(shown("b")); lines.add(shown("c"))
        val t = lines.target(shown("c"))
        assertTrue(t.onScreen)
        assertEquals("c", t.line?.message)
        assertEquals(listOf("a", "b"), messages(t.before))
    }

    @Test fun withNoBoxTheLastLineIsTheTarget() {
        lines.add(shown("a")); now += 5_000; lines.add(shown("b")); val bAt = now; now += 20_000
        val t = lines.target(null)
        assertFalse(t.onScreen)
        assertEquals("b", t.line?.message)
        assertEquals(bAt, t.lineAtMs)
        assertEquals(listOf("a"), messages(t.before))
    }

    @Test fun nothingRecentMeansNoTarget() {
        val t = lines.target(null)
        assertNull(t.line)
        assertTrue(t.before.isEmpty())
    }
}
