package com.example.wearableai.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Entry point for a voice agent session.
 *
 * Wires together:
 *  - [WearableConnector] — audio stream from Meta glasses
 *  - [VoiceAgent]        — Gemma 4 E4B local inference (Cactus) with Gemini cloud fallback
 *
 * Call [start] once the wearable is connected. Audio is buffered until a speech segment
 * is detected (via silence gap), written to a temp WAV file, then handed to [VoiceAgent].
 */
class WearableAISession(private val cloudFallback: CloudFallback) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agent = VoiceAgent(cloudFallback)
    private var collectJob: Job? = null

    /** Must be called before [start]. [modelPath] points to the on-device GGUF file. */
    suspend fun init(modelPath: String) = agent.init(modelPath)

    suspend fun connect(): Boolean = wearableConnector.connect()

    /**
     * Starts the voice agent loop.
     *
     * [preferCloud] — pass `true` to route to Gemini when internet is available.
     * [onResponse]  — called on each assistant response (main-thread dispatch is caller's job).
     * [onError]     — called if inference or I/O fails.
     */
    fun start(
        preferCloud: Boolean,
        onUtterance: (String) -> Unit,
        onResponse: (TurnResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Serial processing pipeline. The mic VAD can emit utterances faster than
        // inference completes (first turn ~16s cold); running them all in parallel
        // corrupts Gemma's context with stale audio and piles up "ghost" turns.
        // Instead we drain one at a time; if inference is already running, new
        // utterances still get processed in order — they just queue here, not
        // as concurrent model calls.
        val queue = Channel<String>(Channel.UNLIMITED)

        collectJob = scope.launch {
            launch {
                wearableConnector.startAudioStream { utteranceWavPath ->
                    println("[WearableAISession] utterance received: $utteranceWavPath")
                    onUtterance(utteranceWavPath)
                    queue.trySend(utteranceWavPath)
                }
            }
            launch {
                for (path in queue) {
                    try {
                        val turn = agent.processUtterance(path, preferCloud)
                        println("[WearableAISession] dispatching onResponse (replyLen=${turn.assistantReply.length})")
                        onResponse(turn)
                    } catch (t: Throwable) {
                        println("[WearableAISession] inference threw: ${t::class.simpleName}: ${t.message}")
                        t.printStackTrace()
                        onError(t.message ?: "Inference error")
                    }
                }
            }
        }
    }

    fun stop() {
        wearableConnector.stopAudioStream()
        collectJob?.cancel()
        collectJob = null
    }

    fun resetConversation() = agent.resetConversation()

    fun destroy() {
        stop()
        wearableConnector.disconnect()
        agent.release()
        scope.cancel()
    }
}
