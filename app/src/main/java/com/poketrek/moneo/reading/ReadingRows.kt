package com.poketrek.moneo.reading

import com.poketrek.moneo.data.CardRecord
import com.poketrek.moneo.data.MoneoRepository
import com.poketrek.moneo.srs.CardState

/**
 * A word of the on-screen message: [id] is the card to star, or null when
 * its deck is switched off (shown for its meaning, but not counted).
 */
data class ReadingRow(val korean: String, val gloss: String, val id: String?, val known: Boolean)

/** The deck words and names of [shown], with glosses and whether the player knows each. */
fun readingRows(
    repository: MoneoRepository,
    shown: DialogReader.OnScreen,
    cards: Map<String, CardRecord>,
): List<ReadingRow> =
    (shown.line?.words.orEmpty() + shown.names).distinct().mapNotNull { w ->
        val entries = repository.visibleEntriesFor(w)
        if (entries.isEmpty()) {
            val hidden = repository.anyEntryFor(w) ?: return@mapNotNull null
            return@mapNotNull ReadingRow(w, hidden.gloss, null, false)
        }
        val known = entries.any { e ->
            cards[e.id]?.let { it.suspended || it.snapshot.state == CardState.REVIEW } == true
        }
        val primary = entries.firstOrNull { cards[it.id]?.snapshot?.state != CardState.REVIEW } ?: entries.first()
        ReadingRow(w, primary.gloss, primary.id, known)
    }
