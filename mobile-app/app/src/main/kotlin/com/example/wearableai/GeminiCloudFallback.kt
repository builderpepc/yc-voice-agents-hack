package com.example.wearableai

import com.example.wearableai.shared.CloudFallback
import com.example.wearableai.shared.ModelConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlobPart
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content
import java.io.File

class GeminiCloudFallback : CloudFallback {

    private var cachedPrompt: String? = null
    private var cachedModel: GenerativeModel? = null

    private fun modelFor(systemPrompt: String): GenerativeModel {
        if (cachedModel == null || cachedPrompt != systemPrompt) {
            cachedModel = GenerativeModel(
                modelName = ModelConfig.GEMINI_MODEL,
                apiKey = BuildConfig.GEMINI_API_KEY,
                systemInstruction = content { text(systemPrompt) },
            )
            cachedPrompt = systemPrompt
        }
        return cachedModel!!
    }

    override suspend fun generateFromAudio(
        audioFilePath: String,
        systemPrompt: String,
        history: List<Map<String, String>>,
    ): String {
        val historyContent = history.map { msg ->
            val role = if (msg["role"] == "assistant") "model" else (msg["role"] ?: "user")
            content(role = role) { text(msg["content"] ?: "") }
        }
        println("[Gemini] sendMessage historySize=${history.size}")

        val chat = modelFor(systemPrompt).startChat(history = historyContent)

        val audioBytes = File(audioFilePath).readBytes()
        val userMessage = content(role = "user") {
            part(BlobPart("audio/wav", audioBytes))
            part(TextPart("Respond to the user's spoken message."))
        }

        val reply = chat.sendMessage(userMessage).text ?: ""
        println("[Gemini] raw reply: ${reply.take(500)}")
        return reply
    }
}
