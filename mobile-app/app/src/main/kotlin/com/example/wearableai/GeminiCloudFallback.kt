package com.example.wearableai

import com.example.wearableai.shared.CloudFallback
import com.example.wearableai.shared.ModelConfig
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
import java.io.File
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
