package com.poketrek.moneo.data

import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import com.poketrek.moneo.srs.Sm2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Public API for the Moneo data layer. Holds vocab + card state in memory
 * (sourced from [SeedLoader] + [MoneoCardStore]) and exposes derived flows
 * for the UI.
 *
 * Lifecycle: a single instance is created by [com.poketrek.moneo.MoneoModule]
 * during activity creation and lives for the process lifetime.
 */
class MoneoRepository(
    private val store: MoneoCardStore,
    initialVocab: List<VocabEntry>,
    initialAreas: List<Area>,
    initialSentencesRom: List<SentenceEntry> = emptyList(),
    initialSentencesStudy: List<SentenceEntry> = emptyList(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _vocab = MutableStateFlow(initialVocab.associateBy { it.id })
    val vocab: StateFlow<Map<String, VocabEntry>> = _vocab.asStateFlow()

    private val _areas = MutableStateFlow(initialAreas)
    val areas: StateFlow<List<Area>> = _areas.asStateFlow()

    private val _cards = MutableStateFlow<Map<String, CardRecord>>(emptyMap())
    val cards: StateFlow<Map<String, CardRecord>> = _cards.asStateFlow()

    /**
     * Source tags that should be excluded from review surfaces. Driven by user
     * preferences (e.g. opting out of Pokemon-name flashcards). Vocab entries
     * with a matching [VocabEntry.sourceTag] are filtered from [vocabForArea],
     * [nextDueCard], [totalDueCount], [dueCountForArea], and area progress.
     * Default empty so all decks are visible.
     */
    private val _excludedSourceTags = MutableStateFlow<Set<String>>(emptySet())
    val excludedSourceTags: StateFlow<Set<String>> = _excludedSourceTags.asStateFlow()

    fun setExcludedSourceTags(tags: Set<String>) {
        _excludedSourceTags.value = tags
    }

    /**
     * `primarySourceType` values whose cards should be hidden from every
     * area review queue (and the global due count). Parallel to
     * [_excludedSourceTags] but keyed by [VocabEntry.primarySourceType]
     * — e.g. `{"pokemon_move"}` hides the entire move-name corpus.
     */
    private val _excludedSourceTypes = MutableStateFlow<Set<String>>(emptySet())
    val excludedSourceTypes: StateFlow<Set<String>> = _excludedSourceTypes.asStateFlow()

    fun setExcludedSourceTypes(types: Set<String>) {
        _excludedSourceTypes.value = types
    }

    /**
     * `primarySourceType` values whose cards should NOT appear in their
     * regular base area, but instead in a synthesized sibling pseudo-area
     * whose id is `"<baseAreaId>#<primarySourceType>"`. UI in
     * [com.poketrek.moneo.MoneoModule] synthesizes matching [Area] objects
     * so the picker renders them. Mutually exclusive with
     * [excludedSourceTypes] per type; the filter pass treats "excluded"
     * as taking precedence.
     */
    private val _separatedSourceTypes = MutableStateFlow<Set<String>>(emptySet())
    val separatedSourceTypes: StateFlow<Set<String>> = _separatedSourceTypes.asStateFlow()

    fun setSeparatedSourceTypes(types: Set<String>) {
        _separatedSourceTypes.value = types
    }

    /** Allow [com.poketrek.moneo.MoneoModule] to update the area list when prefs change. */
    fun setAreas(areas: List<Area>) {
        _areas.value = areas
    }

    /**
     * Per-area lemma frequencies. When an area has counts, [readiness]
     * measures text coverage and [nextDueCard] teaches its most frequent
     * words first; without them both fall back to the first-seen card rules.
     */
    @Volatile private var lemmaCounts: AreaLemmaCounts = AreaLemmaCounts.EMPTY

    fun setAreaLemmaCounts(counts: AreaLemmaCounts) {
        lemmaCounts = counts
    }

    /**
     * Parse a pseudo-area id of the form `"<baseAreaId>#<primarySourceType>"`
     * into its components, or return null if [areaId] is a plain area id.
     */
    fun splitPseudoAreaId(areaId: String): Pair<String, String>? {
        val hash = areaId.indexOf('#')
        if (hash < 0) return null
        return areaId.substring(0, hash) to areaId.substring(hash + 1)
    }

    /** Sentences indexed by [SentenceEntry.vocabId]; multiple per vocab allowed. */
    private val sentencesRomByVocab: Map<String, List<SentenceEntry>> = initialSentencesRom.groupBy { it.vocabId }
    private val sentencesStudyByVocab: Map<String, List<SentenceEntry>> = initialSentencesStudy.groupBy { it.vocabId }

    /**
     * Returns up to one example sentence for the given vocab id.
     *
     * [verbatim] selects the source: true = ROM-rip text (may spoil dialog),
     * false = hand-written study sentences. When [preferAreaId] is supplied,
     * picks the first sentence whose areaId matches; falls back to any
     * sentence for the vocab in that source.
     *
     * In verbatim mode, ROM lines that don't read as a sentence (see
     * [isUsableRomExample]) are skipped, falling back to the study sentence
     * when no usable ROM line is left.
     */
    fun sentenceFor(
        vocabId: String,
        preferAreaId: String? = null,
        verbatim: Boolean = true,
    ): SentenceEntry? {
        val candidates = if (verbatim) {
            sentencesRomByVocab[vocabId].orEmpty().filter { it.isUsableRomExample() }
                .ifEmpty { sentencesStudyByVocab[vocabId].orEmpty() }
        } else {
            sentencesStudyByVocab[vocabId].orEmpty()
        }
        if (preferAreaId != null) {
            candidates.firstOrNull { it.areaId == preferAreaId }?.let { return it }
        }
        return candidates.firstOrNull()
    }

    /**
     * About half of the ROM-ripped examples are a single token (undecoded
     * glyphs dropped out of the middle, e.g. "상대를쪽", "많은주마") or only
     * carry a "(ROM example, recN)" / "(TOPIK example from ROM)" placeholder
     * instead of a translation -- the latter are mostly kana text decoded
     * through the hangul table, i.e. not Korean at all.
     */
    private fun SentenceEntry.isUsableRomExample(): Boolean =
        korean.trim().split(WHITESPACE).size >= 2 && !PLACEHOLDER_GLOSS.matches(gloss)

    init {
        // Make sure every seed entry has a card row. Idempotent on repeat launch.
        store.ensureExists(initialVocab.map { it.id }, now())
        _cards.value = store.all().associateBy { it.vocabId }
    }

    /**
     * Vocab visible while the player is in [areaId].
     *
     * [areaId] may be either:
     *  - a regular area id (e.g. `"route_1"`), in which case the result is
     *    cards whose primary [VocabEntry.areaId] matches OR that appear in
     *    [VocabEntry.areasReferenced], minus any cards whose
     *    [VocabEntry.primarySourceType] is in [separatedSourceTypes] (those
     *    live in their pseudo-area sibling instead).
     *  - a pseudo-area id of the form `"<baseAreaId>#<primarySourceType>"`,
     *    in which case the result is cards in baseArea whose
     *    `primarySourceType` matches the suffix.
     *
     * Filters out cards whose [VocabEntry.sourceTag] is in
     * [excludedSourceTags] (deck-level opt-out) or whose
     * [VocabEntry.primarySourceType] is in [excludedSourceTypes] (per-type
     * opt-out).
     */
    fun vocabForArea(areaId: String): List<VocabEntry> {
        val excludedTags = _excludedSourceTags.value
        val excludedTypes = _excludedSourceTypes.value
        val separatedTypes = _separatedSourceTypes.value
        val pseudo = splitPseudoAreaId(areaId)
        val (baseAreaId, requiredType) = pseudo ?: (areaId to null)
        return _vocab.value.values.filter { v ->
            if (v.sourceTag in excludedTags) return@filter false
            val type = v.primarySourceType
            if (type != null && type in excludedTypes) return@filter false
            val matchesArea = v.areaId == baseAreaId || baseAreaId in v.areasReferenced
            if (!matchesArea) return@filter false
            if (requiredType != null) {
                // Pseudo-area: only cards of the requested source type.
                type == requiredType
            } else {
                // Regular area: drop cards that have been separated out.
                type == null || type !in separatedTypes
            }
        }
    }

    /** Vocab IDs visible after applying all opt-out filters. Used by due-count helpers. */
    private fun visibleVocabIds(): Set<String> {
        val excludedTags = _excludedSourceTags.value
        val excludedTypes = _excludedSourceTypes.value
        if (excludedTags.isEmpty() && excludedTypes.isEmpty()) return _vocab.value.keys
        return _vocab.value.values
            .filter { v ->
                v.sourceTag !in excludedTags &&
                    (v.primarySourceType == null || v.primarySourceType !in excludedTypes)
            }
            .map { it.id }
            .toSet()
    }

    fun areaProgress(areaId: String): StateFlow<AreaProgress> {
        // Backed by combining the cards flow with the vocab snapshot. Since
        // vocab only changes when seeds are reloaded (rare), it's fine to
        // snapshot it eagerly.
        val derived = MutableStateFlow(computeAreaProgress(areaId))
        scope.launch {
            _cards.collect { _ -> derived.value = computeAreaProgress(areaId) }
        }
        return derived.asStateFlow()
    }

    private fun computeAreaProgress(areaId: String): AreaProgress {
        val vocab = vocabForArea(areaId)
        val ids = vocab.map { it.id }.toSet()
        val cards = _cards.value.values.filter { it.vocabId in ids && !it.suspended }
        val n = now()
        val newCount = cards.count { it.snapshot.state == CardState.NEW }
        val learning = cards.count { it.snapshot.state == CardState.LEARNING }
        val review = cards.count { it.snapshot.state == CardState.REVIEW }
        // NEW cards count as due (they need first exposure); LEARNING/REVIEW
        // count when their next-due timestamp has elapsed.
        val dueCount = cards.count { rec ->
            rec.snapshot.state == CardState.NEW || rec.snapshot.dueAt <= n
        }
        return AreaProgress(
            areaId = areaId,
            total = vocab.size,
            newCount = newCount,
            learningCount = learning,
            reviewCount = review,
            dueCount = dueCount,
        )
    }

    /**
     * Pick the next due card for [areaId]. Returns null if no cards are due
     * right now.
     *
     * Two-tier ordering. The full SRS priority pass runs on the *home* tier
     * first — cards whose [VocabEntry.areaId] equals [areaId] (or the
     * pseudo-area's base) — and only falls through to the *referenced* tier
     * (cards visible here solely because [areaId] is in their
     * [VocabEntry.areasReferenced]) when no home card is due. This stops
     * broadly-referenced ROM-mined cards (e.g. `집` referenced in 54 areas)
     * from drowning out the handful of cards actually homed at the area.
     *
     * Within each tier, selection priority is:
     *   1. LEARNING cards whose due time has passed (oldest-due first)
     *   2. REVIEW cards due today
     *   3. NEW cards (limited per session via the caller's pacing if needed)
     */
    fun nextDueCard(areaId: String, nowMs: Long = now()): Pair<CardRecord, VocabEntry>? {
        val vocab = vocabForArea(areaId).associateBy { it.id }
        if (vocab.isEmpty()) return null
        val baseAreaId = splitPseudoAreaId(areaId)?.first ?: areaId
        val counts = lemmaCounts.countsFor(baseAreaId)
        if (counts != null) {
            // Frequency mode: the gate measures how much of this area's text
            // is readable, so new cards come in order of how often the
            // area's dialog uses them, whichever area they were first seen in.
            return pickByPriority(vocab.keys, vocab, nowMs) { rec ->
                -(counts[vocab[rec.vocabId]?.korean] ?: 0)
            }
        }
        val (homeVocab, refVocab) = vocab.values.partition { it.areaId == baseAreaId }
        pickByPriority(homeVocab.map { it.id }.toSet(), vocab, nowMs)?.let { return it }
        return pickByPriority(refVocab.map { it.id }.toSet(), vocab, nowMs)
    }

    private fun pickByPriority(
        vocabIdSubset: Set<String>,
        vocab: Map<String, VocabEntry>,
        nowMs: Long,
        /** Sort key for NEW cards, applied before creation order. */
        newCardRank: (CardRecord) -> Int = { 0 },
    ): Pair<CardRecord, VocabEntry>? {
        if (vocabIdSubset.isEmpty()) return null
        val cards = _cards.value.values.filter { it.vocabId in vocabIdSubset && !it.suspended }
        val learning = cards.filter { it.snapshot.state == CardState.LEARNING && it.snapshot.dueAt <= nowMs }
            .sortedBy { it.snapshot.dueAt }
        if (learning.isNotEmpty()) {
            val rec = learning.first()
            return rec to (vocab[rec.vocabId] ?: return null)
        }
        val review = cards.filter { it.snapshot.state == CardState.REVIEW && it.snapshot.dueAt <= nowMs }
            .sortedBy { it.snapshot.dueAt }
        if (review.isNotEmpty()) {
            val rec = review.first()
            return rec to (vocab[rec.vocabId] ?: return null)
        }
        val news = cards.filter { it.snapshot.state == CardState.NEW }
            .sortedWith(compareBy(newCardRank).thenBy { it.createdAt })
        if (news.isNotEmpty()) {
            val rec = news.first()
            return rec to (vocab[rec.vocabId] ?: return null)
        }
        return null
    }

    /**
     * Apply [rating] to [vocabId]; persists the new card state and updates
     * the in-memory flow.
     */
    fun grade(vocabId: String, rating: Rating, nowMs: Long = now()) {
        val current = _cards.value[vocabId] ?: return
        val nextSnap = Sm2.schedule(current.snapshot, rating, nowMs)
        val updated = current.copy(snapshot = nextSnap, lastReviewedAt = nowMs)
        store.put(updated)
        _cards.value = _cards.value + (vocabId to updated)
    }

    /** Total cards due across all areas at [nowMs]. NEW cards always count as due. */
    fun totalDueCount(nowMs: Long = now()): Int {
        val visible = visibleVocabIds()
        return _cards.value.values.count { rec ->
            rec.vocabId in visible && !rec.suspended &&
                (rec.snapshot.state == CardState.NEW || rec.snapshot.dueAt <= nowMs)
        }
    }

    /** Cards due in the player's currently-selected target area. */
    fun dueCountForArea(areaId: String, nowMs: Long = now()): Int {
        val ids = vocabForArea(areaId).map { it.id }.toSet()
        return _cards.value.values.count { rec ->
            rec.vocabId in ids && !rec.suspended &&
                (rec.snapshot.state == CardState.NEW || rec.snapshot.dueAt <= nowMs)
        }
    }

    /**
     * Mark [vocabId] as user-suspended ("I know this") or restore it. Suspended
     * cards are hidden from review queues and progress counts but kept in the
     * store so the action is reversible (e.g. via the snackbar Undo).
     */
    fun setSuspended(vocabId: String, suspended: Boolean) {
        val current = _cards.value[vocabId] ?: return
        if (current.suspended == suspended) return
        val updated = current.copy(suspended = suspended)
        store.put(updated)
        _cards.value = _cards.value + (vocabId to updated)
    }

    /**
     * Fraction of the cards first encountered in [areaId] (VocabEntry.areaId)
     * that are "mature" — in the REVIEW state or user-suspended (the user
     * vouched they know it). Used by the area-gate to decide whether the
     * player may cross into a higher ordinal area in-game.
     *
     * Words merely referenced in the area (VocabEntry.areasReferenced) don't
     * count: most common words appear in nearly every area, so counting them
     * made each gate re-require the earlier areas' vocab.
     *
     * Returns 1.0 for areas with no visible vocab first seen there (vacuously
     * cleared) so empty/unused areas never block progression.
     */
    fun maturityPct(areaId: String): Float {
        val ids = vocabForArea(areaId).filter { it.areaId == areaId }.map { it.id }.toSet()
        if (ids.isEmpty()) return 1f
        val cards = _cards.value.values.filter { it.vocabId in ids }
        if (cards.isEmpty()) return 1f
        val mature = cards.count { it.suspended || it.snapshot.state == CardState.REVIEW }
        return mature.toFloat() / cards.size
    }

    /**
     * How ready the player is to enter [areaId], 0..1. With lemma counts for
     * the area this is text coverage: the share of the area's deck-word
     * tokens whose word the player knows (some visible card with that
     * [VocabEntry.korean] is in REVIEW or suspended). Words hidden by the
     * deck filters don't count either way. Without counts it falls back to
     * [maturityPct].
     */
    fun readiness(areaId: String): Float {
        val counts = lemmaCounts.countsFor(areaId) ?: return maturityPct(areaId)
        val excludedTags = _excludedSourceTags.value
        val excludedTypes = _excludedSourceTypes.value
        val cards = _cards.value
        val visible = HashSet<String>()
        val known = HashSet<String>()
        for (v in _vocab.value.values) {
            if (v.korean !in counts) continue
            if (v.sourceTag in excludedTags) continue
            if (v.primarySourceType != null && v.primarySourceType in excludedTypes) continue
            visible += v.korean
            val rec = cards[v.id] ?: continue
            if (rec.suspended || rec.snapshot.state == CardState.REVIEW) known += v.korean
        }
        var total = 0L
        var covered = 0L
        for (lemma in visible) {
            val n = counts.getValue(lemma)
            total += n
            if (lemma in known) covered += n
        }
        return if (total == 0L) 1f else covered.toFloat() / total
    }

    /** Wipe all SRS state. Used by debug actions. */
    fun resetAllProgress() {
        store.clear()
        store.ensureExists(_vocab.value.keys, now())
        _cards.value = store.all().associateBy { it.vocabId }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PLACEHOLDER_GLOSS = Regex("""\(.*\bexample\b.*\)""", RegexOption.IGNORE_CASE)
    }
}
