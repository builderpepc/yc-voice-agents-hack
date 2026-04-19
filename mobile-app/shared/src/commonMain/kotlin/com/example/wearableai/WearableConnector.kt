package com.example.wearableai.shared

/** Called with the absolute path to a temp WAV file containing one speech utterance. */
typealias AudioChunkCallback = (wavFilePath: String) -> Unit

/** Called with a finalized transcript string from the on-device recognizer. */
typealias TranscriptCallback = (transcript: String) -> Unit

expect val wearableConnector: WearableConnector

expect class WearableConnector {
    suspend fun connect(): Boolean
    /** Stops audio + unroutes BT SCO but keeps internal resources (scope) alive for reconnect. */
    fun endSession()
    /** Full teardown — also cancels the internal scope. Call on app shutdown only. */
    fun disconnect()
    /** Buffers PCM from the glasses mic and calls [onUtteranceReady] with a WAV file path per utterance. */
    fun startAudioStream(onUtteranceReady: AudioChunkCallback)
    fun stopAudioStream()
    /** Local-only path: drives Android SpeechRecognizer against the BT-SCO-routed mic and
     *  emits one final transcript per utterance. Use instead of [startAudioStream] when
     *  running force-local — FunctionGemma is text-only, no audio plumbing needed. */
    fun startTranscriptStream(onTranscript: TranscriptCallback)
    fun stopTranscriptStream()
}
