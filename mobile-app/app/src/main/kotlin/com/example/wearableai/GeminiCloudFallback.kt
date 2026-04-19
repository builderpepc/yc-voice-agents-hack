package com.example.wearableai

import android.util.Log
import com.example.wearableai.shared.CloudFallback
import com.example.wearableai.shared.DeferredPhoto
import com.example.wearableai.shared.ModelConfig
import com.example.wearableai.shared.Note
import com.example.wearableai.shared.PhotoAttachment
import com.example.wearableai.shared.ToolCall
import com.example.wearableai.shared.ToolDispatcher
import com.example.wearableai.shared.ToolResult
import com.example.wearableai.shared.ToolSpec
import com.example.wearableai.shared.TurnReply
import com.example.wearableai.shared.TurnRequest
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlobPart
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class GeminiCloudFallback : CloudFallback {

    private data class ModelKey(val systemPrompt: String, val toolsSig: String)
    private var cachedKey: ModelKey? = null
    private var cachedModel: GenerativeModel? = null

    private val maxRoundtrips = 4

    private fun modelFor(systemPrompt: String, tools: List<ToolSpec>): GenerativeModel {
        val key = ModelKey(systemPrompt, tools.signature())
        if (cachedModel == null || cachedKey != key) {
            cachedModel = GenerativeModel(
                modelName = ModelConfig.GEMINI_MODEL,
                apiKey = BuildConfig.GEMINI_API_KEY,
                systemInstruction = content { text(systemPrompt) },
                tools = if (tools.isEmpty()) null else listOf(Tool(tools.map { it.toDeclaration() })),
            )
            cachedKey = key
        }
        return cachedModel!!
    }

    override suspend fun generateTurn(
        request: TurnRequest,
        dispatcher: ToolDispatcher?,
    ): TurnReply {
        val model = modelFor(request.systemPrompt, request.tools)

        val historyContent = request.history.map { msg ->
            val role = if (msg["role"] == "assistant") "model" else (msg["role"] ?: "user")
            content(role = role) { text(msg["content"] ?: "") }
        }
        val chat = model.startChat(history = historyContent)

        val userMessage = content(role = "user") {
            request.audioFilePath?.let { path ->
                val bytes = File(path).readBytes()
                part(BlobPart("audio/wav", bytes))
            }
            for (img in request.imageFilePaths) {
                val bytes = File(img).readBytes()
                part(BlobPart("image/jpeg", bytes))
            }
            // When nothing multimodal is attached, send a nudge so the API accepts the turn.
            if (request.audioFilePath == null && request.imageFilePaths.isEmpty()) {
                part(TextPart("(continue)"))
            }
        }

        println("[Gemini] sendMessage historySize=${request.history.size} audio=${request.audioFilePath != null} images=${request.imageFilePaths.size} tools=${request.tools.size}")
        var response = chat.sendMessage(userMessage)
        var parsed = response.toTurnReply()

        if (dispatcher == null) return parsed

        var loops = 0
        while (parsed.toolCalls.isNotEmpty() && loops < maxRoundtrips) {
            val results = parsed.toolCalls.map { call ->
                try {
                    dispatcher.dispatch(call)
                } catch (t: Throwable) {
                    ToolResult(call.id, call.name, """{"error":"${t.message?.replace("\"", "'") ?: "unknown"}"}""")
                }
            }
            val toolMsg = content(role = "user") {
                for (r in results) {
                    val responseJson = try { JSONObject(r.resultJson) } catch (_: Throwable) { JSONObject().put("result", r.resultJson) }
                    part(FunctionResponsePart(r.name, responseJson))
                }
            }
            response = chat.sendMessage(toolMsg)
            parsed = response.toTurnReply()
            loops++
        }
        return parsed
    }

    override suspend fun reconcilePhotos(
        notes: List<Note>,
        pending: List<DeferredPhoto>,
    ): List<PhotoAttachment> {
        if (pending.isEmpty()) return emptyList()

        val systemPrompt = """You are reconciling offline-captured inspection photos against the notes the inspector recorded.
For each photo you receive, decide which note (if any) it best illustrates. Use the utterance transcript that triggered the capture as the primary hint — photos are almost always taken right as the inspector is describing what they see. The photo content itself is a secondary hint.

Respond with ONLY a JSON array, no prose, no markdown fences. Schema:
  [{"photo_path": "<exact path you were given>", "note_id": "<id from the notes list, or null>"}, ...]

Return exactly one entry per photo. If no note is a clear match, set note_id to null rather than forcing a bad attachment."""

        val model = GenerativeModel(
            modelName = ModelConfig.GEMINI_MODEL,
            apiKey = BuildConfig.GEMINI_API_KEY,
            systemInstruction = content { text(systemPrompt) },
            generationConfig = generationConfig { responseMimeType = "application/json" },
        )

        val notesBlock = buildString {
            append("Notes recorded so far (JSON):\n")
            val arr = JSONArray()
            for (n in notes) {
                arr.put(JSONObject().apply {
                    put("id", n.id)
                    put("category", n.category.key)
                    put("markdown", n.markdown)
                    put("timestamp_ms", n.timestampMs)
                    put("has_photo", n.photoPath != null)
                })
            }
            append(arr.toString())
        }

        val userMessage = content(role = "user") {
            part(TextPart(notesBlock))
            for (p in pending) {
                val header = "Photo — captured_at_ms=${p.capturedAtMs}, transcript=${JSONObject.quote(p.transcript)}, photo_path=${JSONObject.quote(p.photoPath)}"
                part(TextPart(header))
                try {
                    part(BlobPart("image/jpeg", File(p.photoPath).readBytes()))
                } catch (t: Throwable) {
                    Log.w("GeminiReconcile", "failed reading ${p.photoPath}: ${t.message}")
                }
            }
            part(TextPart("Return the JSON array now."))
        }

        Log.d("GeminiReconcile", "reconciling ${pending.size} photos against ${notes.size} notes")
        val response = model.generateContent(userMessage)
        val raw = response.candidates.firstOrNull()?.content?.parts
            ?.filterIsInstance<TextPart>()?.joinToString("") { it.text }
            ?: ""
        return parseReconciliationJson(raw, pending)
    }

    private fun parseReconciliationJson(
        raw: String,
        pending: List<DeferredPhoto>,
    ): List<PhotoAttachment> {
        val trimmed = raw.trim().trim('`').trim().removePrefix("json").trim()
        val arr = try {
            JSONArray(trimmed)
        } catch (t: Throwable) {
            Log.w("GeminiReconcile", "unparseable response, dropping all: ${t.message}: ${raw.take(200)}")
            return pending.map { PhotoAttachment(it.photoPath, null) }
        }
        val byPath = mutableMapOf<String, String?>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("photo_path").takeIf { it.isNotBlank() } ?: continue
            val noteId = if (o.isNull("note_id")) null else o.optString("note_id").takeIf { it.isNotBlank() }
            byPath[path] = noteId
        }
        return pending.map { PhotoAttachment(it.photoPath, byPath[it.photoPath]) }
    }

    private fun GenerateContentResponse.toTurnReply(): TurnReply {
        val parts = candidates.firstOrNull()?.content?.parts.orEmpty()
        val text = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
        val calls = parts.filterIsInstance<FunctionCallPart>().mapIndexed { i, fc ->
            val argsJson = JSONObject().apply {
                fc.args.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
            }.toString()
            ToolCall(id = "call_$i", name = fc.name, argsJson = argsJson)
        }
        println("[Gemini] reply textLen=${text.length} toolCalls=${calls.size}")
        return TurnReply(text = text, toolCalls = calls)
    }

    private fun List<ToolSpec>.signature(): String =
        joinToString("|") { t -> "${t.name}:${t.parameters.joinToString(",") { it.name + ":" + it.type }}" }

    private fun ToolSpec.toDeclaration(): FunctionDeclaration {
        val params: List<Schema<out Any>> = parameters.map { p ->
            val values = p.enumValues
            when {
                values != null -> Schema.enum(name = p.name, description = p.description, values = values)
                p.type == "number" -> Schema.double(name = p.name, description = p.description)
                p.type == "integer" -> Schema.int(name = p.name, description = p.description)
                p.type == "boolean" -> Schema.bool(name = p.name, description = p.description)
                else -> Schema.str(name = p.name, description = p.description)
            }
        }
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = params,
            requiredParameters = parameters.filter { it.required }.map { it.name },
        )
    }
}
