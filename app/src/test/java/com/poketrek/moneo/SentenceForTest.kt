package com.poketrek.moneo

import com.poketrek.moneo.data.Area
import com.poketrek.moneo.data.MoneoCardStore
import com.poketrek.moneo.data.MoneoRepository
import com.poketrek.moneo.data.SentenceEntry
import com.poketrek.moneo.data.VocabEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Example-sentence selection. About half of the ROM-ripped examples are
 * single-token fragments (undecoded glyphs dropped, e.g. "상대를쪽") or carry
 * only a "(ROM example, recN)" placeholder gloss; those should give way to
 * the study sentence rather than be shown as the card's example.
 */
class SentenceForTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val vocab = listOf(
        VocabEntry(
            id = "rom-mine-v3:있다", korean = "있다", gloss = "to be",
            partOfSpeech = "adjective", areaId = "pallet_town", sourceTag = "rom-mine-v3",
        ),
    )

    private fun mkRepo(rom: List<SentenceEntry>, study: List<SentenceEntry>) = MoneoRepository(
        store = MoneoCardStore(tempFolder.newFolder()),
        initialVocab = vocab,
        initialAreas = listOf(Area("pallet_town", "Pallet", "태초마을", 1)),
        initialSentencesRom = rom,
        initialSentencesStudy = study,
        now = { 1_700_000_000_000L },
    )

    private val id = "rom-mine-v3:있다"
    private val fragment = SentenceEntry(id, "있었는데", "(ROM example, rec282)")
    private val unglossed = SentenceEntry(id, "여기 뭔가 있었는데", "(ROM example, rec283)")
    private val topikPlaceholder = SentenceEntry(id, "제킹부두도론 부J도톤다!", "(TOPIK example from ROM)")
    private val realLine = SentenceEntry(id, "여기 뭔가 있었는데 없어졌다", "Something was here, but it's gone.")
    private val study = SentenceEntry(id, "방에 침대가 있어요.", "There is a bed in the room.")

    @Test fun verbatimPrefersRealRomLine() {
        val repo = mkRepo(rom = listOf(fragment, realLine), study = listOf(study))
        assertEquals(realLine, repo.sentenceFor(id, verbatim = true))
    }

    @Test fun verbatimFallsBackToStudyWhenRomOnlyHasFragments() {
        val repo = mkRepo(rom = listOf(fragment, unglossed, topikPlaceholder), study = listOf(study))
        assertEquals(study, repo.sentenceFor(id, verbatim = true))
    }

    @Test fun verbatimWithNoUsableSentenceAnywhereReturnsNull() {
        val repo = mkRepo(rom = listOf(fragment), study = emptyList())
        assertNull(repo.sentenceFor(id, verbatim = true))
    }

    @Test fun studyModeIsUnchanged() {
        val repo = mkRepo(rom = listOf(realLine), study = listOf(study))
        assertEquals(study, repo.sentenceFor(id, verbatim = false))
    }
}
