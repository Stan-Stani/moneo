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
 * A box is on screen when BG0's message-box rows have tiles ([boxShown]).
 * [MESSAGE_PTR] is text printer 0's current character (sTextPrinters[0]),
 * which points into the expanded message; found by diffing save states on
 * the 2024 KR ROM, so [isSupported] gates everything.
 */
class DialogReader(
    private val reader: RamCapture.BusReader,
    private val text: KoText2024,
    private val index: DialogIndex,
    private val isSupported: () -> Boolean,
    /** Called once per newly shown message that no dialog line matched. */
    private val onUnmatched: ((String) -> Unit)? = null,
    private val names: NameFinder = NameFinder.EMPTY,
    /** Called once per newly shown message (e.g. to keep [RecentLines]). */
    private val onShown: ((OnScreen) -> Unit)? = null,
) {
    /**
     * [names]: Pokémon/move/ability names in the message, looked for when no
     * line matched or the line is a template whose names are filled in at
     * runtime.
     */
    data class OnScreen(val message: String, val line: DialogIndex.Line?, val names: List<String> = emptyList())

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
        if (!boxShown()) { _current.value = null; return }
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
        val found = if (line == null || line.template) names.find(message) else emptyList()
        Log.d(TAG, "0x${addr.toString(16)} line=${line?.id} names=$found ${message.replace('\n', ' ')}")
        val shown = OnScreen(message, line, found)
        _current.value = shown
        onShown?.invoke(shown)
        if (line == null && found.isEmpty() && message.isNotBlank()) onUnmatched?.invoke(message)
    }

    /** Whether a message box is on screen (see [boxRowsHaveTiles]). */
    private fun boxShown(): Boolean {
        val io = reader.readBytes(IO_BASE, 12) ?: return false
        val dispcnt = u16(io, 0)
        if (dispcnt and DISPCNT_BG0_ON == 0) return false
        val screenblock = (u16(io, 8) shr 8) and 31
        val rows = reader.readBytes(VRAM_BASE + screenblock * 0x800 + BOX_FIRST_ROW * 64, BOX_ROWS * 64)
            ?: return false
        return boxRowsHaveTiles(rows)
    }

    companion object {
        /**
         * Message boxes (field and battle alike) are drawn on BG0, in tile
         * rows 14-19 of its screenblock; with no box those rows are empty.
         * Found by diffing save states open/closed/in battle (2026-09-28).
         * This replaced a RAM byte (0x02036E81) that looked like a box flag
         * in the first states tested but stayed 0 in battles after Continue.
         * [rows] is the tilemap of those rows: 32 u16 entries per row; the
         * low 10 bits are the tile index, and columns 30-31 are off-screen.
         */
        fun boxRowsHaveTiles(rows: ByteArray): Boolean {
            for (r in 0 until rows.size / 64) for (c in 0 until 30) {
                if (u16(rows, r * 64 + c * 2) and 0x3FF != 0) return true
            }
            return false
        }

        private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

        private const val IO_BASE = 0x04000000
        private const val VRAM_BASE = 0x06000000
        private const val DISPCNT_BG0_ON = 0x100
        private const val BOX_FIRST_ROW = 14
        private const val BOX_ROWS = 6

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
        const val MESSAGE_PTR = 0x02020010
        private const val MESSAGE_MAX = 1000
        private const val EWRAM_START = 0x02000000
        private const val EWRAM_END = 0x02040000
    }
}
