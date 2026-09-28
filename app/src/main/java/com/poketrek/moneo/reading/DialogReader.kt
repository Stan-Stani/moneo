package com.poketrek.moneo.reading

import android.util.Log
import com.poketrek.moneo.corpus.RamCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the message box of the 2024 KR ROM and publishes the dialog line
 * on screen, matched against [DialogIndex], while a box is open.
 *
 * RAM locations were found by diffing save states with a box open/closed
 * (2026-09-28): [BOX_OPEN_FLAG] is 1 only while a field message box is up,
 * and [MESSAGE_PTR] points at the expanded message (0x02021D18, the
 * gStringVar4-style buffer the text printer reads). They're specific to that
 * ROM, so [isSupported] gates everything.
 */
class DialogReader(
    private val reader: RamCapture.BusReader,
    private val text: KoText2024,
    private val index: DialogIndex,
    private val isSupported: () -> Boolean,
) {
    data class OnScreen(val message: String, val line: DialogIndex.Line?)

    private val _current = MutableStateFlow<OnScreen?>(null)
    /** The open message box's text and matched line, or null when no box is open. */
    val current: StateFlow<OnScreen?> = _current.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { poll() }.onFailure { Log.w(TAG, "poll failed", it) }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _current.value = null
    }

    private fun poll() {
        if (!isSupported()) { _current.value = null; return }
        val open = reader.readBytes(BOX_OPEN_FLAG, 1)?.firstOrNull()?.toInt() == 1
        if (!open) { _current.value = null; return }
        val p = reader.readBytes(MESSAGE_PTR, 4) ?: return
        val addr = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or
            ((p[2].toInt() and 0xFF) shl 16) or ((p[3].toInt() and 0xFF) shl 24)
        if (addr !in EWRAM_START until EWRAM_END - MESSAGE_MAX) return
        val bytes = reader.readBytes(addr, MESSAGE_MAX) ?: return
        val message = text.decode(bytes)
        if (_current.value?.message == message) return
        _current.value = OnScreen(message, index.match(message))
    }

    companion object {
        private const val TAG = "DialogReader"
        private const val POLL_MS = 250L
        const val BOX_OPEN_FLAG = 0x02036E81
        const val MESSAGE_PTR = 0x02020010
        private const val MESSAGE_MAX = 1000
        private const val EWRAM_START = 0x02000000
        private const val EWRAM_END = 0x02040000
    }
}
