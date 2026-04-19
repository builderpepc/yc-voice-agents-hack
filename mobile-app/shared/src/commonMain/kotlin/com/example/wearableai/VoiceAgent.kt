package com.example.wearableai.shared

import com.cactus.cactusComplete
import com.cactus.cactusDestroy
import com.cactus.cactusInit
import com.cactus.cactusReset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Orchestrates one voice conversation turn:
 *  1. Receives a speech audio file path from [WearableAISession]
 *  2. Routes to local Gemma 4 E4B (via Cactus) or Gemini cloud fallback
 *  3. Returns the assistant text response
 *
 * [cloudFallback] is injected per-platform (Android: Firebase AI; iOS: TODO).
 */
data class TurnResult(val userTranscript: String, val assistantReply: String)

class VoiceAgent(private val cloudFallback: CloudFallback) {

    private var modelHandle: Long = 0L
    // Text-only history. Prior user turns store the transcription parsed from
    // the model's <heard>...</heard> tag, never the raw audio — replaying audio
    // in history corrupts Cactus's prefix KV cache and breaks turn 2+.
    private val history = mutableListOf<Map<String, String>>()
    // Cactus is not thread-safe on a single model handle. Serialize inference
    // calls so overlapping utterances don't corrupt KV cache / graph state.
    private val turnMutex = Mutex()

    suspend fun init(modelPath: String) = withContext(Dispatchers.Default) {
        if (modelHandle == 0L) {
            modelHandle = cactusInit(modelPath, null, false)
        }
    }

    suspend fun processUtterance(
        audioFilePath: String,
        preferCloud: Boolean,
    ): TurnResult = turnMutex.withLock {
        withContext(Dispatchers.Default) {
            println("[VoiceAgent] processUtterance start preferCloud=$preferCloud path=$audioFilePath")
            val rawReply = if (preferCloud) {
                cloudFallback.generateFromAudio(
                    audioFilePath = audioFilePath,
                    systemPrompt = ModelConfig.SYSTEM_PROMPT,
                    history = history,
                )
            } else {
                // Clear Cactus's prefix KV cache. Without this, turn 2's prefill is
                // ~20x faster than turn 1 and the model emits a character-identical
                // response to turn 1, ignoring the new audio entirely.
                cactusReset(modelHandle)
                val messagesJson = buildMessagesJson(audioFilePath)
                println("[VoiceAgent] calling cactusComplete, handle=$modelHandle messagesLen=${messagesJson.length}")
                val resultJson = cactusComplete(
                    model = modelHandle,
                    messagesJson = messagesJson,
                    optionsJson = ModelConfig.COMPLETION_OPTIONS,
                    toolsJson = null,
                    callback = null,
                )
                println("[VoiceAgent] cactusComplete returned, len=${resultJson.length}")
                println("[VoiceAgent] raw response: ${resultJson.take(800)}")
                parseResponseText(resultJson)
            }
            val turn = splitHeard(rawReply)
            println("[VoiceAgent] transcript=${turn.userTranscript.take(120)} reply=${turn.assistantReply.take(120)}")
            history.add(mapOf("role" to "user", "content" to turn.userTranscript))
            history.add(mapOf("role" to "assistant", "content" to turn.assistantReply))
            turn
        }
    }

    fun resetConversation() = history.clear()

    fun release() {
        if (modelHandle != 0L) {
            cactusDestroy(modelHandle)
            modelHandle = 0L
        }
    }

    // Gemma 4 accepts audio natively via "audio" key in the message object.
    // Prior turns are replayed as TEXT only (transcript / reply); only the
    // current turn carries audio so Cactus actually re-encodes it.
    private fun buildMessagesJson(audioFilePath: String): String {
        val sb = StringBuilder("[")
        sb.append("""{"role":"system","content":${jsonString(ModelConfig.SYSTEM_PROMPT)}}""")
        for (msg in history) {
            sb.append(",")
            sb.append("""{"role":${jsonString(msg["role"]!!)},"content":${jsonString(msg["content"]!!)}}""")
        }
        // User content is empty on purpose: if we put instructional text here,
        // Gemma 4 echoes it back inside the <heard>...</heard> tag starting at
        // turn 2 (once it has seen the <heard> pattern in prior assistant turns).
        // The transcription directive lives in the system prompt instead; cactusReset()
        // above ensures the audio is freshly encoded, so empty content is safe.
        sb.append(""",{"role":"user","content":"","audio":["${audioFilePath.replace("\\", "/")}"]}""")
        sb.append("]")
        return sb.toString()
    }

    // System prompt tells the model to prefix replies with <heard>...</heard>.
    // Split it back out for display and for text history.
    private fun splitHeard(raw: String): TurnResult {
        val match = Regex("<heard>([\\s\\S]*?)</heard>").find(raw)
        if (match == null) return TurnResult("[audio]", raw.trim())
        val transcript = match.groupValues[1].trim()
        val reply = raw.removeRange(match.range).trim()
        val normalizedTranscript = if (transcript.equals("SILENCE", ignoreCase = true)) "[silence]" else transcript
        return TurnResult(normalizedTranscript, reply)
    }

    // cactusComplete returns JSON shaped like:
    //   { "success": true, "response": "...", "function_calls": [...], ... }
    // See cactus/docs/cactus_engine.md — the generated text lives under "response".
    private fun parseResponseText(resultJson: String): String = try {
        val obj = Json.parseToJsonElement(resultJson).jsonObject
        obj["response"]?.jsonPrimitive?.contentOrNull
            ?: obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonPrimitive?.contentOrNull
            ?: resultJson.trim()
    } catch (e: Throwable) {
        resultJson.trim()
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}

/** Cloud inference interface — implemented per-platform and injected into [WearableAISession]. */
interface CloudFallback {
    suspend fun generateFromAudio(
        audioFilePath: String,
        systemPrompt: String,
        history: List<Map<String, String>>,
    ): String
}
