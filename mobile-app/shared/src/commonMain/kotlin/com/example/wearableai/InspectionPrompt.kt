package com.example.wearableai.shared

object InspectionPrompt {

    /** Categories used by the add_note tool. Kept in sync with [NoteCategory]. */
    private val CATEGORY_KEYS = NoteCategory.entries.map { it.key }
    private val SEVERITY_KEYS = PinSeverity.entries.map { it.key }

    val SYSTEM_PROMPT: String = """You are a fire-department pre-incident inspection assistant running on Meta AI glasses.
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
