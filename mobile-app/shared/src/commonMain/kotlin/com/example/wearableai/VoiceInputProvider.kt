package com.example.wearableai.shared

/** Called with the absolute path to a temp WAV file containing one speech utterance. */
typealias AudioChunkCallback = (wavFilePath: String) -> Unit

expect val voiceInputProvider: VoiceInputProvider

expect class VoiceInputProvider {
    suspend fun connect(): Boolean
    fun disconnect()
    /** Buffers PCM from the phone mic and calls [onUtteranceReady] with a WAV file path per utterance. */
    fun startAudioStream(onUtteranceReady: AudioChunkCallback)
    fun stopAudioStream()
}
