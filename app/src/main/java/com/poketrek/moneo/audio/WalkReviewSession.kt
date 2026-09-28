package com.poketrek.moneo.audio

import com.poketrek.moneo.data.TtsLanguage
import com.poketrek.moneo.srs.Rating
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands-free flashcard review for walking: speak the front, pause to think,
 * speak the back, then wait briefly for a grade from the earbud buttons
 * (see [WalkReviewService]). No grade means the card is skipped, not failed
 * -- a missed tap shouldn't reset a card's interval -- and after
 * [Timing.maxSkipsBeforePause] skips in a row the session pauses itself,
 * since the player has probably stopped listening.
 *
 * Grades count from the moment a card starts (a listener who knows the
 * word taps while it's still being said); the back is still read out
 * (hearing it is the review), then the grade applies without waiting out
 * the window.
 */
class WalkReviewSession(
    private val cards: CardSource,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
) {
    /** A card to review: its id, and the two sides in the order they're spoken. */
    data class Card(val id: String, val front: String, val frontLang: TtsLanguage, val back: String, val backLang: TtsLanguage)

    interface CardSource {
        /** Next card due other than [skip], or null when nothing is left to review. */
        fun next(skip: Set<String>): Card?
        fun grade(id: String, rating: Rating)
    }

    fun interface Speaker {
        suspend fun say(text: String, language: TtsLanguage): Boolean
    }

    data class Timing(
        val thinkMs: Long = 2_500,
        val gradeWindowMs: Long = 5_000,
        val maxSkipsBeforePause: Int = 3,
    )

    enum class Phase { IDLE, FRONT, THINKING, BACK, AWAITING_GRADE, PAUSED, FINISHED }

    data class State(val phase: Phase = Phase.IDLE, val card: Card? = null, val reviewed: Int = 0, val skipsInARow: Int = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Short spoken notices, e.g. when the session pauses or runs out of cards. */
    var announce: suspend (String) -> Unit = {}

    private var job: Job? = null
    @Volatile private var gradeSignal = CompletableDeferred<Rating>()
    @Volatile private var resumeSignal = CompletableDeferred<Unit>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(phase = Phase.IDLE, card = null)
    }

    /** Grade the current card; ignored when no card is up. */
    fun grade(rating: Rating) {
        when (_state.value.phase) {
            Phase.FRONT, Phase.THINKING, Phase.BACK, Phase.AWAITING_GRADE -> gradeSignal.complete(rating)
            Phase.PAUSED -> togglePause()
            else -> Unit
        }
    }

    /** Pause after the current card, or resume a paused session. */
    fun togglePause() {
        if (_state.value.phase == Phase.PAUSED) resumeSignal.complete(Unit)
        else pauseRequested = true
    }

    @Volatile private var pauseRequested = false
    /** Cards skipped this session; still due, so they'd otherwise come right back. */
    private val skipped = HashSet<String>()

    private suspend fun loop() {
        while (true) {
            if (pauseRequested) {
                pauseRequested = false
                pauseUntilResumed()
            }
            val card = cards.next(skipped)
            if (card == null) {
                _state.value = _state.value.copy(phase = Phase.FINISHED, card = null)
                announce("done")
                return
            }
            gradeSignal = CompletableDeferred()
            set(Phase.FRONT, card)
            speaker.say(card.front, card.frontLang)
            set(Phase.THINKING, card)
            if (!gradeSignal.isCompleted) withTimeoutOrNull(timing.thinkMs) { gradeSignal.await() }
            set(Phase.BACK, card)
            speaker.say(card.back, card.backLang)
            set(Phase.AWAITING_GRADE, card)
            val rating = if (gradeSignal.isCompleted) gradeSignal.await()
            else withTimeoutOrNull(timing.gradeWindowMs) { gradeSignal.await() }
            if (rating != null) {
                cards.grade(card.id, rating)
                _state.value = _state.value.copy(reviewed = _state.value.reviewed + 1, skipsInARow = 0)
            } else {
                skipped += card.id
                val skips = _state.value.skipsInARow + 1
                _state.value = _state.value.copy(skipsInARow = skips)
                if (skips >= timing.maxSkipsBeforePause) {
                    announce("paused")
                    pauseUntilResumed()
                }
            }
        }
    }

    private suspend fun pauseUntilResumed() {
        resumeSignal = CompletableDeferred()
        _state.value = _state.value.copy(phase = Phase.PAUSED, card = null, skipsInARow = 0)
        resumeSignal.await()
        delay(300)  // let the button-press sound settle before speaking
    }

    private fun set(phase: Phase, card: Card) {
        _state.value = _state.value.copy(phase = phase, card = card)
    }
}
