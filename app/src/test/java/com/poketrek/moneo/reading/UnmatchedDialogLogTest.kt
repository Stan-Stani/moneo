package com.poketrek.moneo.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnmatchedDialogLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun dedupesCountsAndPersists() {
        val f = File(tmp.root, "moneo/unmatched.json")
        val log = UnmatchedDialogLog(f)
        log.record("야생 꼬렛의 몸통박치기!", "1:19")
        log.record("야생 꼬렛의 몸통박치기!", "1:19")
        log.record("이상해씨의 몸통박치기!", "1:19")
        assertEquals(2, log.count.value)
        val reloaded = UnmatchedDialogLog(f)
        assertEquals(2, reloaded.count.value)
        assertTrue(reloaded.report().contains("×2  [1:19]  야생 꼬렛의 몸통박치기!"))
    }

    @Test fun ignoresShortOrNonKoreanText() {
        val log = UnmatchedDialogLog(File(tmp.root, "u.json"))
        log.record("구구♂", null)
        log.record("PP 35/35", null)
        assertEquals(0, log.count.value)
    }

    @Test fun clearRemovesTheFile() {
        val f = File(tmp.root, "u.json")
        val log = UnmatchedDialogLog(f)
        log.record("오박사님께 안부 전해주렴", null)
        log.clear()
        assertEquals(0, log.count.value)
        assertTrue(!f.exists())
    }
}
