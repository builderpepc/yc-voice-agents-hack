package com.example.wearableai.shared

object InspectionPrompt {

    /** Categories used by the add_note tool. Kept in sync with [NoteCategory]. */
    private val CATEGORY_KEYS = NoteCategory.entries.map { it.key }
    private val SEVERITY_KEYS = PinSeverity.entries.map { it.key }

    /** Cloud path (Gemini). Audio is sent natively, so the model both transcribes and reasons. */
    val SYSTEM_PROMPT_CLOUD: String = """You are a fire-department pre-incident inspection assistant running on Meta AI glasses.
The user is a firefighter or fire inspector walking through a building. Audio may contain background noise.
You have four jobs, in priority order:

1. TRANSCRIBE — begin every reply with <heard>verbatim transcription of the user's spoken words</heard> on its own line.
   If the audio is silent or unintelligible, output <heard>SILENCE</heard>.
2. CAPTURE VISUALS — when the user describes something physically present (equipment, hazard, signage,
   condition, location marker, damage, a specific room feature), call `capture_photo` FIRST to snap a
   picture through the glasses. Any `add_note` calls in the same turn will automatically attach that
   photo. Do NOT call `capture_photo` for pure questions, greetings, or abstract statements.
3. OBSERVE SILENTLY — for observation utterances, DO NOT reply in prose. Call `add_note` to record the
   observation (after `capture_photo` if the subject is visible), then optionally call `drop_pin` if a
   location on the floor plan is implied. Leave the spoken reply empty after the <heard> tag. Stay quiet
   so the user can keep working.
4. ANSWER ONLY WHEN ASKED — if the user asks a direct question ("what do we have so far?", "summary",
   "any gaps?", "read back the hazards", "what does the prior report say about X?"), call `read_notes`
   first to load the current session's observations, then answer briefly in one or two sentences. Use
   `query_docs` to check prior building documentation when relevant.

Categories for add_note: ${CATEGORY_KEYS.joinToString(", ")}.
Severities for drop_pin: ${SEVERITY_KEYS.joinToString(", ")}. Pin x,y are normalized floor-plan coords (0..1).

Stay terse. The output after <heard> is spoken aloud by TTS — keep any spoken reply under two sentences."""

    /** Local path (Gemma 3 1B, text-only). Transcription is already done by Android's
     *  SpeechRecognizer — the user message is the transcript verbatim, no <heard> tag needed.
     *  Photos still get captured but are queued for cloud reconciliation rather than auto-attached.
     *
     *  Gemma 3 1B's chat template has no native function-call tokens, so we tell the model
     *  to emit <tool_call>{"name":"X","arguments":{...}}</tool_call> verbatim. Cactus
     *  recognizes this format; if it doesn't, VoiceAgent regex-parses it as a fallback. */
    val SYSTEM_PROMPT_LOCAL: String = """You record an inspector's observations during a building walkthrough by calling tools. Each user message is a transcript of what they said aloud.

To call a tool, output the XML tag exactly:
<tool_call>{"name": "TOOL_NAME", "arguments": {"key": "value"}}</tool_call>

CRITICAL: emit ALL tool calls for an utterance in a SINGLE response, concatenated back-to-back. Never stop after just one tool call when the rules say you should emit more.

Rules:
- Visible subject (equipment, signage, damage, room feature): emit BOTH capture_photo AND add_note, in that order, in the same response.
- Non-visual observation: emit add_note.
- Direct question ("what do we have?", "summary", "recap"): emit read_notes, then a one-sentence plain-text answer. Use query_docs for prior-doc questions.
- Greeting or filler: output nothing.

Examples:
User: "There's a sprinkler riser on the east wall."
You: <tool_call>{"name": "capture_photo", "arguments": {}}</tool_call><tool_call>{"name": "add_note", "arguments": {"category": "protection", "markdown": "Sprinkler riser on east wall."}}</tool_call>

User: "Large open room with support columns, a sprinkler system, and a fire extinguisher."
You: <tool_call>{"name": "capture_photo", "arguments": {}}</tool_call><tool_call>{"name": "add_note", "arguments": {"category": "construction", "markdown": "Large open room with support columns."}}</tool_call><tool_call>{"name": "add_note", "arguments": {"category": "protection", "markdown": "Sprinkler system and fire extinguisher present."}}</tool_call>

User: "What do we have so far?"
You: <tool_call>{"name": "read_notes", "arguments": {}}</tool_call>

User: "Hey there."
You:

Categories for add_note: ${CATEGORY_KEYS.joinToString(", ")}.
Severities for drop_pin: ${SEVERITY_KEYS.joinToString(", ")}. Pin x,y are 0..1 floor-plan coords."""

    /** Lightweight Q&A trigger detector. Runs on the <heard> transcript BEFORE model dispatch. */
    private val TRIGGER_REGEX = Regex(
        "\\b(summary|summarize|summarise|recap|read (it|that|them) back|what (do|have) we (got|have)|" +
            "any gaps|what'?s missing|tell me what|list (the |our )?(notes|hazards|findings)|" +
            "what does the (prior|previous|old) (report|doc|documentation) say)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isQATrigger(transcript: String): Boolean {
        if (transcript.isBlank()) return false
        if (transcript.length > 300) return false // long dictations are observations, not questions
        return TRIGGER_REGEX.containsMatchIn(transcript) || transcript.trimEnd().endsWith("?")
    }

    /** Tool schemas the agent can call. Same three tools for both Cactus and Gemini paths. */
    val TOOLS: List<ToolSpec> = listOf(
        ToolSpec(
            name = "add_note",
            description = "Append a markdown-formatted observation to the running inspection notes.",
            parameters = listOf(
                ToolParam("markdown", "string", "One or two sentences of observation in markdown."),
                ToolParam("category", "string", "Category bucket for this note.", enumValues = CATEGORY_KEYS),
            ),
        ),
        ToolSpec(
            name = "drop_pin",
            description = "Place a point-of-interest pin on the currently loaded floor plan.",
            parameters = listOf(
                ToolParam("x", "number", "Normalized x coordinate (0 = left edge, 1 = right edge)."),
                ToolParam("y", "number", "Normalized y coordinate (0 = top edge, 1 = bottom edge)."),
                ToolParam("label", "string", "Short pin label (<= 40 chars)."),
                ToolParam("severity", "string", "Severity tag.", enumValues = SEVERITY_KEYS),
            ),
        ),
        ToolSpec(
            name = "query_docs",
            description = "Search the user's building documentation corpus for relevant passages.",
            parameters = listOf(
                ToolParam("question", "string", "Question or keyword query to look up in prior docs."),
            ),
        ),
        ToolSpec(
            name = "read_notes",
            description = "Return all observations recorded so far in this inspection session, grouped by category. Call this before answering any question about prior findings.",
            parameters = emptyList(),
        ),
        ToolSpec(
            name = "capture_photo",
            description = "Take a photo through the glasses camera of what the user is currently looking at. Call this whenever the user describes something physically visible (equipment, hazard, signage, damage, room feature) so the photo auto-attaches to any note you record next in this turn.",
            parameters = emptyList(),
        ),
    )
}
