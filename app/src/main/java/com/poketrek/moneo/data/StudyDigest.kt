package com.poketrek.moneo.data

import com.poketrek.moneo.srs.CardState
import com.poketrek.moneo.srs.Rating
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A Markdown list of the words to go over with a tutor (e.g. a Claude
 * project reading it from Google Drive): the ones the player marked Again or
 * Hard lately, and the ones they started learning lately.
 *
 * Built from [ReviewLog] entries. Grades from before the log existed are
 * unknown, so while the log is younger than the window, card state stands in:
 * a card reviewed lately that has lapsed counts as struggling, and one
 * reviewed lately that is still learning (or has one successful review)
 * counts as recently learned.
 */
object StudyDigest {

    const val WINDOW_DAYS = 14
    private const val MAX_PER_SECTION = 40
    private const val DAY_MS = 86_400_000L

    data class Word(
        val korean: String,
        val gloss: String,
        val again: Int,
        val hard: Int,
        val lastMs: Long,
        val status: CardState,
        val example: String?,
        /** From card state, not the review log (see class doc). */
        val estimated: Boolean,
    )

    data class Digest(val struggling: List<Word>, val learned: List<Word>)

    fun collect(
        entries: List<ReviewLog.Entry>,
        logStartMs: Long?,
        cards: Map<String, CardRecord>,
        vocab: Map<String, VocabEntry>,
        example: (String) -> String?,
        nowMs: Long,
        windowDays: Int = WINDOW_DAYS,
    ): Digest {
        val since = nowMs - windowDays * DAY_MS
        val recent = entries.filter { it.atMs >= since }
        fun word(id: String, again: Int, hard: Int, lastMs: Long, estimated: Boolean): Word? {
            val v = vocab[id] ?: return null
            val card = cards[id]
            if (card?.suspended == true) return null
            return Word(v.korean, v.gloss, again, hard, lastMs, card?.snapshot?.state ?: CardState.NEW, example(id), estimated)
        }

        val struggling = LinkedHashMap<String, Word>()
        recent.filter { it.rating == Rating.AGAIN || it.rating == Rating.HARD }
            .groupBy { it.vocabId }
            .forEach { (id, misses) ->
                word(id, misses.count { it.rating == Rating.AGAIN }, misses.count { it.rating == Rating.HARD },
                    misses.maxOf { it.atMs }, estimated = false)?.let { struggling[id] = it }
            }

        val learned = LinkedHashMap<String, Word>()
        recent.filter { it.before == CardState.NEW }.forEach { e ->
            if (e.vocabId in struggling) return@forEach
            val last = recent.filter { it.vocabId == e.vocabId }.maxOf { it.atMs }
            word(e.vocabId, 0, 0, last, estimated = false)?.let { learned[e.vocabId] = it }
        }

        // Before the log covered the whole window, fill in from card state.
        if (logStartMs == null || logStartMs > since) {
            val logged = recent.map { it.vocabId }.toSet()
            for (card in cards.values) {
                val at = card.lastReviewedAt ?: continue
                if (at < since || card.vocabId in logged || card.suspended) continue
                val s = card.snapshot
                when {
                    s.lapses > 0 ->
                        word(card.vocabId, s.lapses, 0, at, estimated = true)?.let { struggling[card.vocabId] = it }
                    s.state == CardState.LEARNING || (s.state == CardState.REVIEW && s.reps <= 1) ->
                        word(card.vocabId, 0, 0, at, estimated = true)?.let { learned[card.vocabId] = it }
                }
            }
        }

        return Digest(
            struggling.values.sortedWith(compareByDescending<Word> { it.again * 2 + it.hard }.thenByDescending { it.lastMs })
                .take(MAX_PER_SECTION),
            learned.values.sortedByDescending { it.lastMs }.take(MAX_PER_SECTION),
        )
    }

    fun markdown(d: Digest, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), windowDays: Int = WINDOW_DAYS): String {
        val day = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(zone)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(zone)
        fun status(s: CardState) = when (s) {
            CardState.NEW -> "new"
            CardState.LEARNING -> "still learning"
            CardState.REVIEW -> "passing reviews"
        }
        fun StringBuilder.item(w: Word, note: String?) {
            val parts = listOfNotNull(
                "${w.korean} — ${w.gloss}",
                note,
                "Last ${day.format(Instant.ofEpochMilli(w.lastMs))}, ${status(w.status)}",
            )
            append("- ").append(parts.joinToString(". ")).append(".\n")
            w.example?.let { append("  Example: ").append(it).append('\n') }
        }
        return buildString {
            append("# Moneo study words\n\n")
            append("Korean vocabulary from the player's flashcards for Pokémon LeafGreen (Korean 2024 translation), ")
            append("last $windowDays days. Updated ").append(stamp.format(Instant.ofEpochMilli(nowMs))).append(".\n\n")
            append("## Struggling (marked Again or Hard)\n\n")
            if (d.struggling.isEmpty()) append("None lately.\n")
            for (w in d.struggling) {
                val note = if (w.estimated) "Forgot it ${w.again}× so far" else
                    listOfNotNull(
                        w.again.takeIf { it > 0 }?.let { "Again ×$it" },
                        w.hard.takeIf { it > 0 }?.let { "Hard ×$it" },
                    ).joinToString(", ")
                item(w, note)
            }
            append("\n## Recently learned (first studied in the last $windowDays days)\n\n")
            if (d.learned.isEmpty()) append("None lately.\n")
            for (w in d.learned) item(w, null)
        }
    }
}
