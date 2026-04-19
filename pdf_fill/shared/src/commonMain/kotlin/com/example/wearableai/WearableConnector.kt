package com.example.wearableai.shared

/** Called with the absolute path to a temp WAV file containing one speech utterance. */
typealias AudioChunkCallback = (wavFilePath: String) -> Unit

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
}
