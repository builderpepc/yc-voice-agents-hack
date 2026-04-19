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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Orchestrates one voice-agent turn:
 *  1. Receives a speech audio file (optional) + image files (optional)
 *  2. Routes to local Gemma 4 E2B (via Cactus) or Gemini cloud fallback
 *  3. Each path runs its own tool-dispatch loop (up to [maxRoundtrips]) when a
 *     [ToolDispatcher] is supplied — Cactus here, Gemini inside [CloudFallback].
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

    private val maxRoundtrips = 4

    suspend fun init(modelPath: String) = withContext(Dispatchers.Default) {
        if (modelHandle == 0L) {
            try {
                modelHandle = cactusInit(modelPath, null, false)
            } catch (e: Throwable) {
                println("[VoiceAgent] Local model init failed (cloud-only mode): ${e.message}")
                modelHandle = 0L
            }
        }
    }

    /** Exposed so the session layer can reuse the loaded model for embeddings (RAG). */
    fun modelHandleOrZero(): Long = modelHandle

    suspend fun processTurn(
        audioFilePath: String?,
        imageFilePaths: List<String>,
        systemPrompt: String,
        tools: List<ToolSpec>,
        dispatcher: ToolDispatcher?,
        preferCloud: Boolean,
    ): TurnResult = turnMutex.withLock {
        withContext(Dispatchers.Default) {
            println("[VoiceAgent] processTurn start preferCloud=$preferCloud audio=$audioFilePath images=${imageFilePaths.size} tools=${tools.size}")

            val request = TurnRequest(
                audioFilePath = audioFilePath,
                imageFilePaths = imageFilePaths,
                systemPrompt = systemPrompt,
                history = history.toList(),
                tools = tools,
            )
            val reply = if (preferCloud) {
                cloudFallback.generateTurn(request, dispatcher)
            } else {
                runLocalTurnWithToolLoop(request, dispatcher)
            }

            val turn = splitHeard(reply.text)
            println("[VoiceAgent] transcript=${turn.userTranscript.take(120)} reply=${turn.assistantReply.take(120)}")
            if (audioFilePath != null) {
                history.add(mapOf("role" to "user", "content" to turn.userTranscript))
                history.add(mapOf("role" to "assistant", "content" to turn.assistantReply))
            }
            turn
        }
    }

    /** Back-compat wrapper for the earlier audio-only scaffold. */
    suspend fun processUtterance(audioFilePath: String, preferCloud: Boolean): TurnResult =
        processTurn(
            audioFilePath = audioFilePath,
            imageFilePaths = emptyList(),
            systemPrompt = ModelConfig.SYSTEM_PROMPT,
            tools = emptyList(),
            dispatcher = null,
            preferCloud = preferCloud,
        )

    fun resetConversation() = history.clear()

    fun release() {
        if (modelHandle != 0L) {
            try { cactusDestroy(modelHandle) } catch (_: Throwable) {}
            modelHandle = 0L
        }
    }

    private suspend fun runLocalTurnWithToolLoop(
        request: TurnRequest,
        dispatcher: ToolDispatcher?,
    ): TurnReply {
        // Clear Cactus's prefix KV cache at the start of each new turn. Without this,
        // prefill is ~20x faster on subsequent turns and the model emits character-
        // identical replies, ignoring new audio/images entirely.
        cactusReset(modelHandle)

        val toolResults = mutableListOf<ToolResult>()
        var attachedMedia = true // first call carries audio+images
        var loops = 0
        var lastReply = TurnReply("", emptyList())

        while (loops <= maxRoundtrips) {
            val messagesJson = buildMessagesJson(
                systemPrompt = request.systemPrompt,
                audioFilePath = if (attachedMedia) request.audioFilePath else null,
                imageFilePaths = if (attachedMedia) request.imageFilePaths else emptyList(),
                priorToolResults = toolResults,
            )
            val toolsJson = if (request.tools.isEmpty()) null else buildToolsJson(request.tools)
            println("[VoiceAgent] cactusComplete loop=$loops messagesLen=${messagesJson.length} toolsLen=${toolsJson?.length ?: 0}")
            val resultJson = cactusComplete(
                model = modelHandle,
                messagesJson = messagesJson,
                optionsJson = ModelConfig.COMPLETION_OPTIONS,
                toolsJson = toolsJson,
                callback = null,
            )
            lastReply = parseCactusReply(resultJson)

            if (lastReply.toolCalls.isEmpty() || dispatcher == null) break

            for (call in lastReply.toolCalls) {
                val result = try {
                    dispatcher.dispatch(call)
                } catch (t: Throwable) {
                    ToolResult(call.id, call.name, """{"error":${jsonString(t.message ?: "unknown")}}""")
                }
                toolResults.add(result)
            }
            attachedMedia = false
            loops++
        }

        // If we hit the cap or ran out of tool calls, prefer the model's final text.
        // If the cloud returned a pure-toolcall reply with no text, expose the tool
        // calls to the caller so the session layer can log / surface them.
        return lastReply
    }

    private fun buildMessagesJson(
        systemPrompt: String,
        audioFilePath: String?,
        imageFilePaths: List<String>,
        priorToolResults: List<ToolResult>,
    ): String {
        val sb = StringBuilder("[")
        sb.append("""{"role":"system","content":${jsonString(systemPrompt)}}""")
        for (msg in history) {
            sb.append(",")
            sb.append("""{"role":${jsonString(msg["role"]!!)},"content":${jsonString(msg["content"]!!)}}""")
        }
        for (r in priorToolResults) {
            sb.append(",")
            sb.append(
                """{"role":"tool","name":${jsonString(r.name)},"tool_call_id":${jsonString(r.id)},"content":${jsonString(r.resultJson)}}"""
            )
        }
        if (audioFilePath != null || imageFilePaths.isNotEmpty()) {
            // User content is empty on purpose: if we put instructional text here,
            // Gemma 4 echoes it back inside the <heard>...</heard> tag starting at
            // turn 2. Transcription directive lives in the system prompt instead.
            sb.append(""",{"role":"user","content":""""")
            if (audioFilePath != null) {
                sb.append(""","audio":["${audioFilePath.replace("\\", "/")}"]""")
            }
            if (imageFilePaths.isNotEmpty()) {
                val paths = imageFilePaths.joinToString(",") { "\"${it.replace("\\", "/")}\"" }
                sb.append(""","images":[$paths]""")
            }
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun buildToolsJson(tools: List<ToolSpec>): String {
        val sb = StringBuilder("[")
        tools.forEachIndexed { i, tool ->
            if (i > 0) sb.append(",")
            sb.append("""{"type":"function","function":{"name":${jsonString(tool.name)},"description":${jsonString(tool.description)},"parameters":""")
            sb.append("""{"type":"object","properties":{""")
            tool.parameters.forEachIndexed { j, p ->
                if (j > 0) sb.append(",")
                sb.append("""${jsonString(p.name)}:{"type":${jsonString(p.type)},"description":${jsonString(p.description)}""")
                if (p.enumValues != null) {
                    sb.append(""","enum":[""")
                    p.enumValues.forEachIndexed { k, v ->
                        if (k > 0) sb.append(",")
                        sb.append(jsonString(v))
                    }
                    sb.append("]")
                }
                sb.append("}")
            }
            sb.append("},")
            val required = tool.parameters.filter { it.required }.map { it.name }
            sb.append(""""required":[""")
            required.forEachIndexed { j, n ->
                if (j > 0) sb.append(",")
                sb.append(jsonString(n))
            }
            sb.append("]}}}")
        }
        sb.append("]")
        return sb.toString()
    }

    // cactusComplete returns JSON like:
    //   { "success": true, "response": "...", "function_calls": [{ "id":"...","name":"...","arguments":{...} }, ...] }
    private fun parseCactusReply(resultJson: String): TurnReply = try {
        val obj = Json.parseToJsonElement(resultJson).jsonObject
        val text = obj["response"]?.jsonPrimitive?.contentOrNull
            ?: obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val calls = parseFunctionCalls(obj["function_calls"])
        TurnReply(text = text, toolCalls = calls)
    } catch (e: Throwable) {
        println("[VoiceAgent] parseCactusReply failed: ${e.message}")
        TurnReply(text = resultJson.trim(), toolCalls = emptyList())
    }

    private fun parseFunctionCalls(element: JsonElement?): List<ToolCall> {
        if (element == null || element !is JsonArray) return emptyList()
        return element.mapIndexedNotNull { idx, item ->
            val o = (item as? JsonObject) ?: return@mapIndexedNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$idx"
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val args = when (val a = o["arguments"] ?: o["args"]) {
                null -> "{}"
                is JsonObject -> a.toString()
                else -> a.jsonPrimitive.contentOrNull ?: "{}"
            }
            ToolCall(id = id, name = name, argsJson = args)
        }
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

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
