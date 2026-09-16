package org.qinda.deck.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech-to-keystrokes, wrapped around the platform [SpeechRecognizer].
 *
 * The interaction model is a toggle: tap once to start listening, tap again to
 * stop. The recognizer finalises a segment whenever you pause; that segment is
 * emitted on [finals] (and typed on the host by the repository) while listening
 * continues, so sentences appear on the computer as you say them. [partial]
 * carries the in-flight segment for display only — it is never typed, because
 * recognizers revise early words and a revision cannot be untyped.
 *
 * Everything funnels through a main-thread [Handler], because SpeechRecognizer
 * insists on being created and driven from aLooper with a Looper. The owning
 * scope lives in the repository, which has no Main dispatcher of its own.
 */
class VoiceTyper(private val context: Context) {

    enum class State { Idle, Listening }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** Completed segments, ready to be typed. Buffered so bursts never drop. */
    val finals = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The running transcript for this session, oldest first, capped. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True while the user wants to be listening, across recognizer restarts. */
    @Volatile private var wantListening = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var soundsMuted = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!hasPermission) {
            _error.value = "Microphone permission is not granted."
            return
        }
        if (!available) {
            _error.value = "No speech recognition service is installed on this phone."
            return
        }
        wantListening = true
        _error.value = null
        main.post {
            muteSessionSounds(true)
            startLocked()
        }
    }

    /** Stop after the in-flight segment; its final text still gets typed. */
    fun stop() {
        wantListening = false
        main.post {
            recognizer?.stopListening()
            _state.value = State.Idle
            muteSessionSounds(false)
        }
    }

    /** Abandon the in-flight segment and stop right now. */
    fun cancel() {
        wantListening = false
        main.post {
            recognizer?.cancel()
            _state.value = State.Idle
            _partial.value = ""
            muteSessionSounds(false)
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    fun destroy() {
        wantListening = false
        main.post {
            recognizer?.destroy()
            recognizer = null
            muteSessionSounds(false)
        }
    }

    /**
     * Silence the recognizer's start/stop dings for the session.
     *
     * Continuous dictation restarts the recognizer after every phrase, and each
     * restart plays a tone on the system/notification streams — a metronome of
     * beeps while you talk. Muting those streams for the session keeps the
     * feedback visual only. Restored on every exit path, and never touches the
     * music stream, so whatever is playing keeps playing.
     */
    private fun muteSessionSounds(mute: Boolean) {
        if (mute == soundsMuted) return
        soundsMuted = mute
        // The recognizer's dings ride the system/notification streams. The
        // assistant stream is off-limits to third-party apps since API 29, and
        // music stays untouched on purpose.
        for (stream in intArrayOf(
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
        )) {
            runCatching { audio.setStreamMute(stream, mute) }
        }
    }

    // ------------------------------------------------------------ main thread

    private fun startLocked() {
        if (recognizer == null) {
            @Suppress("DEPRECATION")
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer?.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching {
            recognizer?.startListening(intent)
            _state.value = State.Listening
            _partial.value = ""
        }.onFailure {
            _error.value = "Could not start the recognizer: ${it.message}"
            _state.value = State.Idle
            wantListening = false
        }
    }

    private fun restartLocked(delayMs: Long = 250) {
        if (!wantListening) return
        main.postDelayed({ if (wantListening) startLocked() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _error.value = null
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            _partial.value = ""
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Silence or mumbling: just open the mic again.
                    restartLocked()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartLocked(600)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wantListening = false
                    _state.value = State.Idle
                    _error.value = "Microphone permission was revoked."
                    muteSessionSounds(false)
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    wantListening = false
                    _state.value = State.Idle
                    _error.value = "The speech service refused this request ($error)."
                    muteSessionSounds(false)
                }
                else -> {
                    // Network and server errors: the service may recover, so back
                    // off briefly and try once more before giving up on this tap.
                    _error.value = "Speech service hiccup ($error); retrying…"
                    restartLocked(1200)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            handleFinal(results)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            _partial.value = text
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        private fun handleFinal(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            _partial.value = ""
            if (text.isNotEmpty()) {
                _log.value = (_log.value + text).takeLast(200)
                finals.tryEmit(text)
            }
            if (wantListening) {
                restartLocked()
            } else {
                _state.value = State.Idle
            }
        }
    }
}
