package com.example.wearableai.shared

actual val wearableConnector: WearableConnector = WearableConnector()

actual class WearableConnector {
    actual suspend fun connect(): Boolean {
        TODO("Implement with Meta Wearables iOS DAT SDK")
    }

    actual fun endSession() {}

    actual fun disconnect() {}

    actual fun startAudioStream(onUtteranceReady: AudioChunkCallback) {
        TODO("Implement with Meta Wearables iOS DAT SDK")
    }

    actual fun stopAudioStream() {}
}
