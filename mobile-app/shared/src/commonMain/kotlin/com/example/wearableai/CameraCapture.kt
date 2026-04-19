package com.example.wearableai.shared

/**
 * Captures a single still photo from the glasses camera and writes it to a
 * local file, returning the path. Returns null on any failure (timeout,
 * permission, no stream). Platform-specific; see android actual.
 *
 * The glasses' hardware shutter button is NOT observable via the DAT SDK 0.6.x
 * (physical-button photos go to the Meta AI companion app only). So this is
 * called app-side: on every significant utterance, on voice command, or on
 * explicit button press in the phone UI.
 */
expect class CameraCapture() {
    /** Must be called once after the wearable connection is established. */
    suspend fun open(): Boolean

    /** Blocks until a photo is captured or [timeoutMs] elapses. Returns the JPEG path or null. */
    suspend fun capture(timeoutMs: Long = 3000): String?

    /** Directs [capture] to write photos into [dir]. Null (default) falls back to the
     *  cache directory. Called by the session layer whenever the active session changes. */
    fun setOutputDir(dir: String?)

    fun close()
}
