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
 * (2026-09-28): bit 0 of [BOX_OPEN_FLAG] is set only while a field message box
 * is up (0x01 for an NPC line, 0x11 in a house),
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
        val open = ((reader.readBytes(BOX_OPEN_FLAG, 1)?.firstOrNull()?.toInt() ?: 0) and 1) != 0
        if (!open) { _current.value = null; return }
        val p = reader.readBytes(MESSAGE_PTR, 4) ?: return
        val addr = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8) or
            ((p[2].toInt() and 0xFF) shl 16) or ((p[3].toInt() and 0xFF) shl 24)
        if (addr !in EWRAM_START + LOOKBACK until EWRAM_END - MESSAGE_MAX) return
        // The pointer is the text printer's current character: at the start
        // of a field message, but it walks through battle text as it prints.
        // Step back to just after the previous string's 0xFF terminator.
        val bytes = reader.readBytes(addr - LOOKBACK, LOOKBACK + MESSAGE_MAX) ?: return
        val start = messageStart(bytes, LOOKBACK)
        val message = text.decode(bytes, start).trim()
        if (_current.value?.message == message) return
        val line = index.match(message)
        Log.d(TAG, "0x${addr.toString(16)} line=${line?.id} ${message.replace('\n', ' ')}")
        _current.value = OnScreen(message, line)
    }

    companion object {
        /**
         * Start of the message the printer cursor at [cursor] belongs to. A
         * finished printer sits one past its message's 0xFF, so that FF is
         * skipped first. Walks back to the previous 0xFF, or to a run of two
         * 0x00 (a lone 00 is a space, but the battle text buffer is preceded
         * by zero padding with no terminator).
         */
        fun messageStart(bytes: ByteArray, cursor: Int): Int {
            var i = cursor
            if (i > 0 && bytes[i - 1] == FF) i--
            while (i > 0 && bytes[i - 1] != FF && !(i > 1 && bytes[i - 1] == ZERO && bytes[i - 2] == ZERO)) i--
            return i
        }

        private const val FF = 0xFF.toByte()
        private const val ZERO = 0.toByte()

        private const val LOOKBACK = 512
        private const val TAG = "DialogReader"
        private const val POLL_MS = 250L
        const val BOX_OPEN_FLAG = 0x02036E81
        const val MESSAGE_PTR = 0x02020010
        private const val MESSAGE_MAX = 1000
        private const val EWRAM_START = 0x02000000
        private const val EWRAM_END = 0x02040000
    }
}
