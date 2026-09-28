package com.poketrek.moneo

import android.content.Context
import com.poketrek.moneo.corpus.RamCapture
import com.poketrek.moneo.data.Area
import com.poketrek.moneo.data.AreaCatalog
import com.poketrek.moneo.data.MoneoCardStore
import com.poketrek.moneo.data.MoneoPrefs
import com.poketrek.moneo.data.MoneoRepository
import com.poketrek.moneo.data.SeedLoader
import com.poketrek.moneo.data.SourceTypeMode
import java.io.File
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Process-wide singleton owning Moneo's collaborators. Mirrors the
 * `MovementBudget.get(context)` pattern so we stay dependency-injection-free.
 *
 * Intentional rule: nothing in `com.poketrek.moneo.*` may import anything
 * from `com.poketrek.step.*`. The two features share only the `EmulatorActivity`
 * + `EmulatorScreen` mount points.
 */
class MoneoModule private constructor(context: Context) {
    val prefs: MoneoPrefs = MoneoPrefs.get(context)
    val repository: MoneoRepository
    /** Korean TTS engine for the example-sentence speaker button. Lazily
     *  initialized so process startup doesn't pay for TextToSpeech bind
     *  unless the user actually opens the review screen. */
    val tts: com.poketrek.moneo.audio.TtsPlayer by lazy {
        com.poketrek.moneo.audio.TtsPlayer(context.applicationContext)
    }
    private val captureDir: File = File(context.filesDir, "moneo")

    /** Hanja breakdowns for Sino-Korean words, shown on the card back. */
    val hanja: com.poketrek.moneo.data.HanjaDict =
        runCatching { com.poketrek.moneo.data.HanjaDict.loadFromAssets(context) }
            .getOrElse { com.poketrek.moneo.data.HanjaDict.EMPTY }

    /** In-game reading helper for the 2024 KR ROM; null until [bindDialogReader]. */
    @Volatile var dialogReader: com.poketrek.moneo.reading.DialogReader? = null
        private set

    private val appContext = context.applicationContext

    /** Messages the reading helper couldn't match, for reviewing misses from real play. */
    val unmatchedDialog = com.poketrek.moneo.reading.UnmatchedDialogLog(
        File(File(context.filesDir, "moneo"), "unmatched_dialog.json")
    )

    /**
     * Start watching the message box; [isSupported] says whether the loaded
     * ROM is the 2024 KR patch, [location] names the current map for the
     * unmatched-dialogue log.
     */
    fun bindDialogReader(
        reader: RamCapture.BusReader,
        isSupported: () -> Boolean,
        location: () -> String? = { null },
    ) {
        if (dialogReader != null) return
        val r = runCatching {
            com.poketrek.moneo.reading.DialogReader(
                reader,
                com.poketrek.moneo.reading.KoText2024.loadFromAssets(appContext),
                com.poketrek.moneo.reading.DialogIndex.loadFromAssets(appContext),
                isSupported,
                onUnmatched = { unmatchedDialog.record(it, location()) },
                names = com.poketrek.moneo.reading.NameFinder(
                    repository.vocab.value.values
                        .filter { it.primarySourceType in com.poketrek.moneo.reading.NameFinder.NAME_TYPES }
                        .map { it.korean }
                ),
            )
        }.getOrNull() ?: return
        dialogReader = r
        r.start()
    }

    /** Per-area lemma frequencies driving the area gate; empty if the asset is missing. */
    val lemmaCounts: com.poketrek.moneo.data.AreaLemmaCounts =
        runCatching { com.poketrek.moneo.data.AreaLemmaCounts.loadFromAssets(context) }
            .getOrElse { com.poketrek.moneo.data.AreaLemmaCounts.EMPTY }

    /**
     * Lazily-attached runtime EWRAM capture. Built on demand once the
     * runner is constructed; call [bindCapture] from the activity.
     * Null until bound; callers should null-check.
     */
    @Volatile var ramCapture: RamCapture? = null
        private set

    init {
        val store = MoneoCardStore.forContext(context)
        val areas = runCatching { AreaCatalog.loadFromAssets(context) }.getOrElse { emptyList() }
        // seed-vocab-ko.json retired 2026-05-12 — its 45 entries were
        // hand-curated MVP placeholders, now migrated into seed-vocab-
        // ko-mined.json with rom-mine-v2 ids. SeedLoader is still used
        // for the larger mined/topik/species/etymology decks below.
        val vocabSeed = emptyList<com.poketrek.moneo.data.VocabEntry>()
        // Auto-mined vocab from corpus.ko.json. Optional — if the file is
        // absent or fails to parse, fall back to seed-only.
        val vocabMined = runCatching {
            SeedLoader.loadFromAssets(context, "moneo/seed-vocab-ko-mined.json")
        }.getOrElse { emptyList() }
        // TOPIK 1+2 vocab that also occurs in the ROM corpus (~700 cards,
        // hand-glossed). Optional like the mined deck.
        val vocabTopik = runCatching {
            SeedLoader.loadFromAssets(context, "moneo/seed-vocab-ko-topik.json")
        }.getOrElse { emptyList() }
        // Species names (proper nouns) extracted from gSpeciesNames in the
        // 2024 ROM. Always loaded; runtime filtering driven by
        // prefs.includeSpecies via repository.setExcludedSourceTags below.
        val vocabSpecies = runCatching {
            SeedLoader.loadFromAssets(context, "moneo/seed-vocab-ko-species.json")
        }.getOrElse { emptyList() }
        // Korean root morphemes harvested from species-name pun etymologies.
        // Off by default; opt-in via prefs.includeEtymology.
        val vocabEtymology = runCatching {
            SeedLoader.loadFromAssets(context, "moneo/seed-vocab-ko-etymology.json")
        }.getOrElse { emptyList() }
        val vocab = vocabSeed + vocabMined + vocabTopik + vocabSpecies + vocabEtymology
        val sentencesRom = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-rom.json")
        }.getOrElse { emptyList() }
        val sentencesStudy = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-study.json")
        }.getOrElse { emptyList() }
        // Mined sentences are ROM-sourced — only included in the verbatim
        // (spoiler-on) corpus. Mined vocab cards therefore have no example
        // sentence when the user has spoilers off.
        val sentencesMined = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-mined.json")
        }.getOrElse { emptyList() }
        val sentencesTopik = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-topik.json")
        }.getOrElse { emptyList() }
        // Species sentences match the species vocab deck. Same shape as the
        // other corpora; auto-generated example sentences.
        val sentencesSpecies = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-species.json")
        }.getOrElse { emptyList() }
        val sentencesEtymology = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-etymology.json")
        }.getOrElse { emptyList() }
        // Hand-curated themed sentences for vocab whose only sentence sources
        // are ROM-derived. Joins the spoiler-free corpus so mined/topik/
        // species/etymology cards still get an example when verbatim is off.
        val sentencesThemed = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-themed.json")
        }.getOrElse { emptyList() }
        // LLM-generated themed sentences for the species deck (241 of 246;
        // the other 5 starters live in sentences-ko-themed.json as the
        // hand-reviewed voice reference). Replaces the templated "야생의 X
        // 가 나타났다!" lines whenever verbatim is off.
        val sentencesThemedSpecies = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-themed-species.json")
        }.getOrElse { emptyList() }
        // LLM-generated themed sentences for the mined deck: 586 original
        // + a 232-lemma straggler batch (818 total) that closes the
        // spoiler-free gap (incl. 따르다). Replaces the rotated-ROM
        // placeholders in the no-spoiler corpus.
        val sentencesThemedMined = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-themed-mined.json")
        }.getOrElse { emptyList() }
        // LLM-generated themed sentences for the TOPIK 1+2 deck (708 of
        // 713; 5 lemmas already in sentences-ko-themed.json). Voice is
        // looser than the mined deck — TOPIK includes general everyday
        // vocab (food/weather/family) so not every sentence is Pokémon-
        // flavored.
        val sentencesThemedTopik = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-themed-topik.json")
        }.getOrElse { emptyList() }
        // LLM-generated themed sentences for the etymology root deck. Each
        // line features the species whose Korean name carries the root, so
        // the root surfaces as a substring without leaking ROM dialog. Without
        // this the entire etymology deck has no spoiler-free example.
        val sentencesThemedEtymology = runCatching {
            com.poketrek.moneo.data.SentenceLoader.loadFromAssets(context, "moneo/sentences-ko-themed-etymology.json")
        }.getOrElse { emptyList() }
        val allRomSentences = sentencesRom + sentencesMined + sentencesTopik + sentencesSpecies + sentencesEtymology
        // Spoiler-free corpus: hand-curated study sentences plus the themed
        // batch. The mined/topik/species/etymology corpora themselves are all
        // ROM-sourced (or surface in-game species names) so they leak content
        // when the user explicitly opts out of spoilers via the verbatim toggle.
        val allStudySentences = sentencesStudy + sentencesThemed + sentencesThemedSpecies + sentencesThemedMined + sentencesThemedTopik + sentencesThemedEtymology
        repository = MoneoRepository(
            store = store,
            initialVocab = vocab,
            initialAreas = areas,
            initialSentencesRom = allRomSentences,
            initialSentencesStudy = allStudySentences,
        )
        repository.setAreaLemmaCounts(lemmaCounts)
        repository.setStudyNext(prefs.studyNext)
        repository.onStudyNextChanged = { prefs.saveStudyNext(it) }
        // Drive optional-deck visibility from user prefs. Combined so toggles
        // take effect immediately without an app restart.
        GlobalScope.launch {
            kotlinx.coroutines.flow.combine(prefs.includeSpecies, prefs.includeEtymology) { species, etym ->
                val tags = mutableSetOf<String>()
                if (!species) tags.add("rom-species-2024")
                if (!etym) tags.add("etymology-roots")
                tags.toSet()
            }.collect { repository.setExcludedSourceTags(it) }
        }
        // Per-source-type opt-out + separation (moves, abilities). Recomputes
        // the area list whenever either pref changes so pseudo-areas appear
        // or disappear without an app restart.
        GlobalScope.launch {
            kotlinx.coroutines.flow.combine(prefs.movesMode, prefs.abilitiesMode) { moves, abil ->
                moves to abil
            }.collect { (moves, abil) ->
                val excluded = mutableSetOf<String>()
                val separated = mutableSetOf<String>()
                if (moves == SourceTypeMode.OFF) excluded.add(SOURCE_TYPE_MOVE)
                if (moves == SourceTypeMode.SEPARATE) separated.add(SOURCE_TYPE_MOVE)
                if (abil == SourceTypeMode.OFF) excluded.add(SOURCE_TYPE_ABILITY)
                if (abil == SourceTypeMode.SEPARATE) separated.add(SOURCE_TYPE_ABILITY)
                repository.setExcludedSourceTypes(excluded)
                repository.setSeparatedSourceTypes(separated)
                repository.setAreas(buildAreaList(areas, separated))
            }
        }
    }

    /**
     * Produce the area list including any pseudo-areas synthesized for
     * source types in [separated]. Each base area that actually contains
     * at least one matching card gets a sibling entry rendered just after
     * it in the picker.
     */
    private fun buildAreaList(base: List<Area>, separated: Set<String>): List<Area> {
        if (separated.isEmpty()) return base
        val out = ArrayList<Area>(base.size + base.size * separated.size)
        // Insert each pseudo-area right after its parent so the picker
        // groups them visually without us needing a custom row renderer.
        // The ordinal mirrors the parent so existing sort-by-ordinal stays
        // correct; ties break alphabetically by id.
        for (area in base) {
            out += area
            for (type in separated.sorted()) {
                if (!repositoryHasAreaTypeCards(area.id, type)) continue
                out += Area(
                    id = "${area.id}#$type",
                    englishName = "${area.englishName} · ${labelFor(type, english = true)}",
                    koreanLabel = "${area.koreanLabel} · ${labelFor(type, english = false)}",
                    ordinal = area.ordinal,
                )
            }
        }
        return out
    }

    /** Whether [areaId] has at least one card whose primarySourceType is [type]. */
    private fun repositoryHasAreaTypeCards(areaId: String, type: String): Boolean =
        repository.vocab.value.values.any { v ->
            v.primarySourceType == type &&
                (v.areaId == areaId || areaId in v.areasReferenced)
        }

    private fun labelFor(type: String, english: Boolean): String = when (type) {
        SOURCE_TYPE_MOVE -> if (english) "Moves" else "기술"
        SOURCE_TYPE_ABILITY -> if (english) "Abilities" else "특성"
        else -> type
    }

    /** Wire up the optional runtime EWRAM capture once the runner exists. */
    fun bindCapture(reader: RamCapture.BusReader) {
        if (ramCapture == null) {
            ramCapture = RamCapture(reader, captureDir)
        }
    }

    companion object {
        @Volatile private var instance: MoneoModule? = null
        fun get(context: Context): MoneoModule = instance ?: synchronized(this) {
            instance ?: MoneoModule(context.applicationContext).also { instance = it }
        }

        const val SOURCE_TYPE_MOVE = "pokemon_move"
        const val SOURCE_TYPE_ABILITY = "pokemon_ability"
    }
}
