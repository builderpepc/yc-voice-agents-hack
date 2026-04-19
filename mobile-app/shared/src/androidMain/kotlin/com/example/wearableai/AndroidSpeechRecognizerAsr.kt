package com.example.wearableai.shared

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "SpeechRecognizerAsr"

/**
 * Wraps Android's system SpeechRecognizer and presents it as a continuous
 * utterance stream. Each `onResults` → callback → immediately re-arm the
 * recognizer for the next utterance. All SpeechRecognizer API calls must run
 * on the main thread, so this class marshals starts/stops onto the main Looper.
 *
 * Audio source is pinned to VOICE_COMMUNICATION on API 33+ so that the
 * recognizer pulls from the same BT-SCO-routed stream the rest of the app uses
 * for the glasses mic. On older devices the default source applies, and SCO
 * routing typically still takes effect system-wide, but that's OEM-dependent.
 */
class AndroidSpeechRecognizerAsr(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onTranscript: TranscriptCallback? = null
    @Volatile private var running = false

    fun start(onTranscript: TranscriptCallback) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "No recognition service available on this device")
            return
        }
        this.onTranscript = onTranscript
        running = true
        mainHandler.post { launchRecognizer() }
    }

    fun stop() {
        running = false
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun launchRecognizer() {
        if (!running) return
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(Listener())
        rec.startListening(buildIntent())
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ties the recognizer to the same audio source the rest of the app uses,
            // which — combined with setCommunicationDevice(btDevice) in connect() —
            // makes it pull from the Meta glasses mic over BT SCO.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
    }

    private fun restart() {
        if (!running) return
        // Small delay avoids a tight loop if the recognizer is reporting spurious errors.
        mainHandler.postDelayed({
            if (!running) return@postDelayed
            recognizer?.destroy()
            recognizer = null
            launchRecognizer()
        }, 50)
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "onReadyForSpeech") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            Log.w(TAG, "onError=$error (${errorName(error)})")
            // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT are expected during silence —
            // just restart. Other errors we also restart but log at WARN.
            restart()
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = list?.firstOrNull()?.trim().orEmpty()
            if (transcript.isNotEmpty()) {
                Log.d(TAG, "onResults: ${transcript.take(120)}")
                onTranscript?.invoke(transcript)
            }
            restart()
        }

        private fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            else -> "code=$error"
        }
    }
}
