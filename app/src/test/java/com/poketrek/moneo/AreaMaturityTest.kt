package com.poketrek.moneo

import com.poketrek.moneo.data.Area
import com.poketrek.moneo.data.CardRecord
import com.poketrek.moneo.data.MoneoCardStore
import com.poketrek.moneo.data.MoneoRepository
import com.poketrek.moneo.data.VocabEntry
import com.poketrek.moneo.srs.CardSnapshot
import com.poketrek.moneo.srs.CardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AreaMaturityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun vocab(id: String, areaId: String): VocabEntry = VocabEntry(
        id = id,
        korean = id,
        gloss = id,
        partOfSpeech = "noun",
        areaId = areaId,
        sourceTag = "tag",
    )

    @Test fun emptyAreaIsVacuouslyMature() {
        val store = MoneoCardStore(tempFolder.newFolder())
        val repo = MoneoRepository(
            store = store,
            initialVocab = emptyList(),
            initialAreas = listOf(Area("aid", "A", "에이", 0)),
            now = { now },
        )
        assertEquals(1f, repo.maturityPct("aid"), 0f)
    }

    @Test fun allNewIsZero() {
        val store = MoneoCardStore(tempFolder.newFolder())
        val vocab = (1..3).map { vocab("v$it", "aid") }
        val repo = MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = listOf(Area("aid", "A", "에이", 0)),
            now = { now },
        )
        assertEquals(0f, repo.maturityPct("aid"), 0f)
    }

    @Test fun reviewAndSuspendedCountAsMature() {
        val store = MoneoCardStore(tempFolder.newFolder())
        // Pre-seed the store: ensureExists() in repo init only inserts missing rows,
        // so existing put-ed records survive with their custom state.
        store.put(CardRecord("v1", CardSnapshot(state = CardState.REVIEW), createdAt = now))
        store.put(CardRecord("v2", CardSnapshot(state = CardState.NEW), createdAt = now, suspended = true))
        store.put(CardRecord("v3", CardSnapshot(state = CardState.LEARNING), createdAt = now))
        store.put(CardRecord("v4", CardSnapshot(state = CardState.NEW), createdAt = now))

        val vocab = (1..4).map { vocab("v$it", "aid") }
        val repo = MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = listOf(Area("aid", "A", "에이", 0)),
            now = { now },
        )
        // v1 (REVIEW) + v2 (suspended) = mature; v3 (LEARNING) + v4 (NEW) = not.
        assertEquals(0.5f, repo.maturityPct("aid"), 1e-6f)
    }

    @Test fun onlyCardsFirstSeenInTheAreaCount() {
        // The gate asks "has the player learned this area's words?", so a word
        // first met in an earlier area and merely referenced here must not count.
        val store = MoneoCardStore(tempFolder.newFolder())
        store.put(CardRecord("here", CardSnapshot(state = CardState.REVIEW), createdAt = now))
        store.put(CardRecord("earlier", CardSnapshot(state = CardState.NEW), createdAt = now))
        val vocab = listOf(
            vocab("here", "aid"),
            vocab("earlier", "other").copy(areasReferenced = listOf("other", "aid")),
        )
        val repo = MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = listOf(Area("other", "O", "오", 0), Area("aid", "A", "에이", 1)),
            now = { now },
        )
        assertEquals(1f, repo.maturityPct("aid"), 0f)
        assertEquals(0f, repo.maturityPct("other"), 0f)
    }

    @Test fun areaWithOnlyReferencedCardsIsVacuouslyMature() {
        val store = MoneoCardStore(tempFolder.newFolder())
        val vocab = listOf(vocab("earlier", "other").copy(areasReferenced = listOf("aid")))
        val repo = MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = listOf(Area("other", "O", "오", 0), Area("aid", "A", "에이", 1)),
            now = { now },
        )
        assertEquals(1f, repo.maturityPct("aid"), 0f)
    }
}
