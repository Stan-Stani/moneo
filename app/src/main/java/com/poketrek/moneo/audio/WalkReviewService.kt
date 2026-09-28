package com.poketrek.moneo.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.poketrek.EmulatorActivity
import com.poketrek.moneo.MoneoModule
import com.poketrek.moneo.data.FlashcardDirection
import com.poketrek.moneo.data.TtsLanguage
import com.poketrek.moneo.srs.Rating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "WalkReviewService"
private const val CHANNEL_ID = "poketrek_walk_review"
private const val NOTIFICATION_ID = 1002

/**
 * Walking audio review: runs a [WalkReviewSession] over the Moneo deck with
 * the screen off. Earbud / lock-screen media buttons grade through a
 * [MediaSession]: play-pause (single tap) = knew it, next (double tap) =
 * didn't, previous (triple tap) = pause/resume, stop = end. The same
 * actions are on the notification.
 *
 * Cards come from the study target area (the one Moneo's area picker or the
 * area-gate chip set), in the same order as the on-screen queue.
 */
class WalkReviewService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var moneo: MoneoModule
    private lateinit var session: WalkReviewSession
    private lateinit var mediaSession: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val tones by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }

    override fun onCreate() {
        super.onCreate()
        moneo = MoneoModule.get(applicationContext)
        createNotificationChannel()
        mediaSession = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = primary()
                override fun onPause() = primary()
                override fun onSkipToNext() = gradeAndCue(Rating.AGAIN)
                override fun onSkipToPrevious() = session.togglePause()
                override fun onStop() = stopSelf()
            })
            isActive = true
        }
        session = WalkReviewSession(DeckSource(moneo), ::say, scope)
        session.announce = { notice ->
            say(
                when (notice) {
                    "paused" -> "Paused. Tap to continue."
                    else -> "All done. Nice walk."
                },
                TtsLanguage.ENGLISH,
            )
            if (notice == "done") stopSelf()
        }
        startForegroundCompat(buildNotification(session.state.value))
        scope.launch {
            session.state.collect { st ->
                _running.value = st
                updatePlaybackState(st)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(st))
            }
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poketrek:walk-review")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        requestFocus()
        session.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GOOD -> gradeAndCue(Rating.GOOD)
            ACTION_AGAIN -> gradeAndCue(Rating.AGAIN)
            ACTION_PAUSE -> session.togglePause()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Stop observing first: session.stop() emits a final state, and posting
        // it after the foreground notification is gone would leave a stray one.
        scope.cancel()
        session.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        moneo.tts.stop()
        mediaSession.release()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        tones?.release()
        _running.value = null
        super.onDestroy()
    }

    /** Single tap: "knew it" while a card is up; resume when paused. */
    private fun primary() {
        if (session.state.value.phase == WalkReviewSession.Phase.PAUSED) session.togglePause()
        else gradeAndCue(Rating.GOOD)
    }

    private fun gradeAndCue(rating: Rating) {
        val phase = session.state.value.phase
        if (phase !in GRADABLE) return
        session.grade(rating)
        tones?.startTone(if (rating == Rating.AGAIN) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 150)
    }

    private suspend fun say(text: String, language: TtsLanguage): Boolean {
        val ok = moneo.tts.speakAndWait(text, language)
        if (!ok) Log.w(TAG, "couldn't speak ($language): $text")
        return ok
    }

    /** Media buttons go to the session that holds focus and reports PLAYING. */
    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = req
        getSystemService(AudioManager::class.java).requestAudioFocus(req)
    }

    private fun updatePlaybackState(st: WalkReviewSession.State) {
        val state = when (st.phase) {
            WalkReviewSession.Phase.PAUSED -> PlaybackState.STATE_PAUSED
            WalkReviewSession.Phase.FINISHED, WalkReviewSession.Phase.IDLE -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PLAYING
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun buildNotification(st: WalkReviewSession.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, EmulatorActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(icon: Int, title: String, act: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, act.hashCode(), Intent(this, WalkReviewService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val paused = st.phase == WalkReviewSession.Phase.PAUSED
        val text = when (st.phase) {
            WalkReviewSession.Phase.PAUSED -> "Paused · tap ▶ or an earbud to continue"
            WalkReviewSession.Phase.FINISHED -> "All done"
            else -> st.card?.front ?: "Starting…"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Walk review · ${st.reviewed} reviewed")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(android.R.drawable.checkbox_on_background, "Knew it", ACTION_GOOD))
            .addAction(action(android.R.drawable.ic_delete, "Again", ACTION_AGAIN))
            .addAction(
                action(
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (paused) "Resume" else "Pause", ACTION_PAUSE,
                )
            )
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Walk review", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Hands-free flashcard review while walking"
                    setShowBadge(false)
                }
            )
        }
    }

    /** Cards from the study target area, sides in the deck's direction. */
    private class DeckSource(private val moneo: MoneoModule) : WalkReviewSession.CardSource {
        private fun area(): String =
            moneo.prefs.targetAreaId.value ?: moneo.repository.areas.value.firstOrNull()?.id ?: "pallet_town"

        override fun next(skip: Set<String>): WalkReviewSession.Card? {
            val (_, v) = moneo.repository.nextDueCard(area(), exclude = skip) ?: return null
            return when (moneo.prefs.direction.value) {
                FlashcardDirection.KO_TO_EN ->
                    WalkReviewSession.Card(v.id, v.korean, TtsLanguage.KOREAN, v.gloss, TtsLanguage.ENGLISH)
                FlashcardDirection.EN_TO_KO ->
                    WalkReviewSession.Card(v.id, v.gloss, TtsLanguage.ENGLISH, v.korean, TtsLanguage.KOREAN)
            }
        }

        override fun grade(id: String, rating: Rating) = moneo.repository.grade(id, rating)
    }

    companion object {
        private const val ACTION_GOOD = "com.poketrek.walk.GOOD"
        private const val ACTION_AGAIN = "com.poketrek.walk.AGAIN"
        private const val ACTION_PAUSE = "com.poketrek.walk.PAUSE"
        private const val ACTION_STOP = "com.poketrek.walk.STOP"
        private val GRADABLE = setOf(
            WalkReviewSession.Phase.FRONT, WalkReviewSession.Phase.THINKING, WalkReviewSession.Phase.BACK, WalkReviewSession.Phase.AWAITING_GRADE,
        )

        private val _running = MutableStateFlow<WalkReviewSession.State?>(null)
        /** Live session state while the service runs, null otherwise. */
        val running: StateFlow<WalkReviewSession.State?> = _running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WalkReviewService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkReviewService::class.java))
        }
    }
}
