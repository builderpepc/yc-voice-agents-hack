package com.example.wearableai.shared

/**
 * Shared types for a multimodal, tool-using turn. Used by both the local (Cactus)
 * and cloud (Gemini) paths so the caller can drive a single tool-dispatch loop.
 */

data class ToolParam(
    val name: String,
    val type: String, // "string", "number", "integer", "boolean"
    val description: String,
    val enumValues: List<String>? = null,
    val required: Boolean = true,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>,
)

/** A tool invocation requested by the model. [argsJson] is raw JSON object text. */
data class ToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/** The result the caller returns to the model for a previous [ToolCall]. */
data class ToolResult(
    val id: String,
    val name: String,
    /** Arbitrary JSON-serializable payload rendered back to the model. */
    val resultJson: String,
)

data class TurnRequest(
    val audioFilePath: String?,
    val imageFilePaths: List<String>,
    val systemPrompt: String,
    val history: List<Map<String, String>>,
    val tools: List<ToolSpec>,
)

/**
 * Final reply from a turn. If a dispatcher was provided and the path ran its own
 * tool loop internally, [toolCalls] is empty. Otherwise (no dispatcher or loop cap
 * hit) it contains the unexecuted calls from the last model response.
 */
data class TurnReply(
    val text: String,
    val toolCalls: List<ToolCall>,
)

/** Executes a tool call on behalf of the model. Implemented by the session layer. */
fun interface ToolDispatcher {
    suspend fun dispatch(call: ToolCall): ToolResult
}

/** Cloud inference interface — implemented per-platform and injected into the session. */
interface CloudFallback {
    /**
     * Runs one full turn against the cloud model. If [dispatcher] is non-null, the
     * implementation MUST run its own tool-call loop (sending function responses
     * back into the same chat session) until the model returns a final text reply
     * or the per-turn roundtrip cap is reached.
     */
    suspend fun generateTurn(request: TurnRequest, dispatcher: ToolDispatcher?): TurnReply
}
