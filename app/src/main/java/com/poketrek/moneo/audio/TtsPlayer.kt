package com.poketrek.moneo.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.poketrek.moneo.data.TtsLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Bilingual (Korean + English) Text-to-Speech wrapper for headword and
 * example-sentence playback.
 *
 * Many Samsung devices ship with Google TTS as the default engine but
 * Samsung TTS as the only one with Korean voice data installed (or vice
 * versa). Asking the default engine and giving up if it doesn't speak
 * Korean made the warning card appear on perfectly capable phones, so we
 * probe each installed engine in turn and use the first one that supports
 * at least one of {Korean, English}.
 *
 * Once an engine is mounted, [speak] switches the loaded locale lazily on
 * each call — `TextToSpeech.setLanguage` is synchronous, so flipping
 * mid-session between Korean and English is cheap.
 */
class TtsPlayer(context: Context) {
    enum class Status {
        /** Engine init hasn't completed yet — UI should wait, not warn. */
        INITIALIZING,
        /** Engine ready; at least one of Korean/English is available. */
        READY,
        /** Engine works but neither Korean nor English voice data is installed. */
        MISSING_DATA,
        /** No installed TTS engine reports Korean *or* English support. */
        UNSUPPORTED,
        /** Engine itself failed to start. */
        ENGINE_FAILED,
    }

    private val _status = MutableStateFlow(Status.INITIALIZING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * The set of languages the currently-mounted engine can speak. Empty
     * until init completes (or if init failed). Callers should check this
     * before showing a speaker button.
     */
    private val _availableLanguages = MutableStateFlow<Set<TtsLanguage>>(emptySet())
    val availableLanguages: StateFlow<Set<TtsLanguage>> = _availableLanguages.asStateFlow()

    /**
     * Backwards-compat: true iff Korean is available. The original UI
     * gated the speaker button + warning card on this flag; downstream
     * call sites can keep using it without worrying about the new
     * bilingual API.
     */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /**
     * True while a Korean utterance is actively being spoken. Driven by
     * [UtteranceProgressListener]; observers (e.g. [EmulatorScreen]) use
     * this to duck the game's [AudioTrack] so the spoken Korean isn't
     * drowned out by the GBA soundtrack.
     */
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    @Volatile private var pendingRate: Float = 1f

    private val appContext = context.applicationContext

    /** Engines we've already attempted, by package name. */
    private val triedEngines = mutableSetOf<String>()

    /** Worst observed failure across attempts; chosen so MISSING_DATA wins
     *  over UNSUPPORTED — "you have an engine, just install the voice" is
     *  a more actionable hint than "no engine speaks Korean." */
    private var fallbackStatus: Status? = null

    /** The currently-mounted TTS instance. Replaced as we probe engines. */
    @Volatile private var tts: TextToSpeech? = null

    /** Locale currently loaded into [tts] — null before init, switched lazily by [speak]. */
    @Volatile private var currentLocale: Locale? = null

    /** Engine name we asked for in the latest `startEngine` call. Used by
     *  [handleInit] because TextToSpeech.defaultEngine returns the *system*
     *  default, not the engine this instance is using. */
    @Volatile private var pendingEngineName: String? = null

    /** Utterances [speakAndWait] is waiting on, by utterance id. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val nextUtterance = java.util.concurrent.atomic.AtomicLong()

    private fun finish(utteranceId: String?, ok: Boolean) {
        _isSpeaking.value = false
        utteranceId?.let { pending.remove(it)?.complete(ok) }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
        override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId, false) }
        @Deprecated("Pre-API 21 fallback; the variant with errorCode is preferred.")
        override fun onError(utteranceId: String?) { finish(utteranceId, false) }
        override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
    }

    init {
        // Try the system-default engine first; it's usually what the user
        // expects and avoids unnecessary engine churn on devices where it
        // already supports Korean.
        startEngine(engineName = null)
    }

    private fun startEngine(engineName: String?) {
        pendingEngineName = engineName
        val previous = tts
        tts = null
        previous?.let {
            runCatching { it.stop(); it.shutdown() }
        }
        val instance = if (engineName != null) {
            TextToSpeech(appContext, { handleInit(it) }, engineName)
        } else {
            TextToSpeech(appContext, { handleInit(it) })
        }
        tts = instance
    }

    private fun handleInit(status: Int) {
        val current = tts ?: return
        val engineName = pendingEngineName ?: current.defaultEngine ?: ""
        if (engineName.isNotEmpty()) triedEngines.add(engineName)

        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS engine '$engineName' init failed (status=$status)")
            if (fallbackStatus == null) fallbackStatus = Status.ENGINE_FAILED
            tryNextEngine(current)
            return
        }
        val koreanRes = trySetLanguage(current, Locale.KOREAN)
        val englishRes = trySetLanguage(current, Locale.ENGLISH)
        val supported = mutableSetOf<TtsLanguage>()
        if (isLangOk(koreanRes)) supported += TtsLanguage.KOREAN
        if (isLangOk(englishRes)) supported += TtsLanguage.ENGLISH

        if (supported.isNotEmpty()) {
            // Default to Korean if available (preserves prior behavior); otherwise
            // English. Either way the loaded locale is recorded so [speak] knows
            // when to switch.
            val initialLocale = if (TtsLanguage.KOREAN in supported) Locale.KOREAN
                else Locale.ENGLISH
            current.setLanguage(initialLocale)
            currentLocale = initialLocale
            current.setSpeechRate(pendingRate)
            current.setOnUtteranceProgressListener(progressListener)
            _availableLanguages.value = supported
            _available.value = TtsLanguage.KOREAN in supported
            _status.value = Status.READY
            Log.i(TAG, "TTS ready via engine '$engineName' (langs=$supported)")
            return
        }
        Log.w(
            TAG,
            "Engine '$engineName' speaks neither Korean (=$koreanRes) nor English (=$englishRes)",
        )
        // MISSING_DATA beats UNSUPPORTED in the displayed warning, since it's
        // the more actionable case — the user can install the voice pack.
        val mapped = if (koreanRes == TextToSpeech.LANG_MISSING_DATA ||
            englishRes == TextToSpeech.LANG_MISSING_DATA) Status.MISSING_DATA
            else Status.UNSUPPORTED
        if (fallbackStatus != Status.MISSING_DATA) fallbackStatus = mapped
        tryNextEngine(current)
    }

    private fun trySetLanguage(t: TextToSpeech, locale: Locale): Int =
        runCatching { t.setLanguage(locale) }
            .getOrElse { TextToSpeech.LANG_NOT_SUPPORTED }

    private fun isLangOk(r: Int): Boolean =
        r == TextToSpeech.LANG_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

    private fun tryNextEngine(current: TextToSpeech) {
        val engines = runCatching { current.engines }.getOrNull() ?: emptyList()
        val candidate = engines.firstOrNull { it.name != null && it.name !in triedEngines }
        if (candidate != null) {
            Log.i(TAG, "Trying alternate TTS engine: '${candidate.name}'")
            startEngine(candidate.name)
        } else {
            _available.value = false
            _availableLanguages.value = emptySet()
            _status.value = fallbackStatus
                ?: if (engines.isEmpty()) Status.ENGINE_FAILED else Status.UNSUPPORTED
        }
    }

    /**
     * Speak [text] in [language]. Replaces any in-flight utterance (rapid
     * taps don't queue up). No-op when [language] isn't in
     * [availableLanguages] or is [TtsLanguage.OFF].
     *
     * Switches the underlying [TextToSpeech.setLanguage] lazily — a flip
     * between Korean and English is a single synchronous engine call, so
     * mixing languages in the same session is cheap.
     */
    fun speak(text: String, language: TtsLanguage = TtsLanguage.KOREAN) {
        if (language == TtsLanguage.OFF) return
        if (language !in _availableLanguages.value) return
        val instance = tts ?: return
        val target = language.toLocale() ?: return
        if (currentLocale != target) {
            instance.setLanguage(target)
            currentLocale = target
        }
        instance.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Speak [text] and suspend until it finishes. Returns false when it
     * couldn't be spoken (language unavailable, engine not ready, error,
     * interrupted by another utterance or [stop]).
     */
    suspend fun speakAndWait(text: String, language: TtsLanguage): Boolean {
        if (language == TtsLanguage.OFF || language !in _availableLanguages.value) return false
        val instance = tts ?: return false
        val target = language.toLocale() ?: return false
        if (currentLocale != target) {
            instance.setLanguage(target)
            currentLocale = target
        }
        val id = "wait-" + nextUtterance.incrementAndGet()
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pending[id] = done
        if (instance.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return try {
            done.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun TtsLanguage.toLocale(): Locale? = when (this) {
        TtsLanguage.KOREAN -> Locale.KOREAN
        TtsLanguage.ENGLISH -> Locale.ENGLISH
        TtsLanguage.OFF -> null
    }

    /**
     * Set the synthesis rate. 1.0 is normal; 0.5 is half-speed, 2.0
     * double-speed. Safe to call before init completes — the value is
     * cached and applied once the engine is ready.
     */
    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        pendingRate = clamped
        if (_available.value) tts?.setSpeechRate(clamped)
    }

    /** Cancel anything currently playing. */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Release the platform engine. Called when the owning module is being
     * torn down — Android also reclaims TTS resources on process death so
     * this is mainly defensive for tests / explicit cleanup.
     */
    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        _available.value = false
        _availableLanguages.value = emptySet()
        currentLocale = null
    }

    companion object {
        private const val TAG = "TtsPlayer"
        private const val UTTERANCE_ID = "moneo-tts"
    }
}
