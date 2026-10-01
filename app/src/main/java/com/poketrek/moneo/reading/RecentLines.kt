package com.poketrek.moneo.reading

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last few message-box lines the reading helper saw, so 💬 can answer
 * about a line that has already closed ("what did she just say?") and about
 * the conversation around it. Keeps at most [max] lines, none older than
 * [windowMs]. Pure apart from [clock], for JVM tests.
 */
class RecentLines(
    private val max: Int = 15,
    private val windowMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val shown: DialogReader.OnScreen, val atMs: Long)

    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    /** Oldest first; may hold lines past [windowMs] until the next [add] or [recent]. */
    val lines: StateFlow<List<Entry>> = _lines.asStateFlow()

    /** Records a newly shown line; a repeat of the latest one is ignored. */
    fun add(shown: DialogReader.OnScreen) {
        if (shown.message.isBlank()) return
        val now = clock()
        _lines.update { list ->
            if (list.lastOrNull()?.shown?.message == shown.message) list
            else (fresh(list, now) + Entry(shown, now)).takeLast(max)
        }
    }

    /** Lines within the window, oldest first. */
    fun recent(): List<Entry> = fresh(_lines.value, clock())

    /**
     * What a 💬 question is about: [line] is the open message box ([onScreen])
     * or, with none open, the latest recent line (shown at [lineAtMs]);
     * [before] are the recent lines before it, oldest first.
     */
    data class Target(
        val line: DialogReader.OnScreen?,
        val onScreen: Boolean,
        val lineAtMs: Long?,
        val before: List<Entry>,
    )

    fun target(current: DialogReader.OnScreen?): Target {
        val recent = recent()
        if (current != null) {
            // The open box is usually the latest entry already; don't send it twice.
            val i = recent.indexOfLast { it.shown.message == current.message }
            return Target(current, true, null, if (i >= 0) recent.subList(0, i) else recent)
        }
        val last = recent.lastOrNull() ?: return Target(null, false, null, emptyList())
        return Target(last.shown, false, last.atMs, recent.dropLast(1))
    }

    fun clear() = _lines.update { emptyList() }

    private fun fresh(list: List<Entry>, now: Long) = list.filter { now - it.atMs <= windowMs }
}
