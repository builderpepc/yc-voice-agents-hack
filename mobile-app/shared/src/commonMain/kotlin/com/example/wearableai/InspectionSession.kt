package com.example.wearableai.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replaces [WearableAISession] for the fire-inspection product.
 *
 * Wires together audio capture, VAD-derived utterances, glasses photo capture,
 * tool dispatch (add_note / drop_pin / query_docs), and note + pin state. The
 * agent runs in silent-note-taker mode by default: it observes via tool calls
 * and only speaks when the user asks a direct question.
 */
class InspectionSession(
    cloudFallback: CloudFallback,
    private val ragIndexDir: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agent = VoiceAgent(cloudFallback)

    val notes = NoteTaker()
    val building = BuildingContext()
    private val rag = BuildingDocRag(ragIndexDir)
    private val camera = CameraCapture()

    private var collectJob: Job? = null

    /** Must be called before [start]. [modelPath] points to the on-device GGUF dir. */
    suspend fun init(modelPath: String) {
        agent.init(modelPath)
        rag.open()
    }

    suspend fun connect(): Boolean {
        val ok = wearableConnector.connect()
        if (ok) camera.open()
        return ok
    }

    /**
     * Starts the inspection loop.
     *
     * [preferCloud] — true routes to Gemini when internet is available.
     * [onUtterance] — fired with the raw WAV path as soon as the VAD closes an utterance.
     * [onTurn]      — fired after the model + tools produce a final TurnResult.
     * [onPhoto]     — fired when a photo is captured (auto-snapshot per utterance).
     * [onError]     — fired on inference or I/O failure.
     */
    fun start(
        preferCloud: Boolean,
        onUtterance: (String) -> Unit,
        onTurn: (TurnResult) -> Unit,
        onPhoto: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val queue = Channel<String>(Channel.UNLIMITED)

        collectJob = scope.launch {
            launch {
                wearableConnector.startAudioStream { utteranceWavPath ->
                    onUtterance(utteranceWavPath)
                    queue.trySend(utteranceWavPath)
                }
            }
            launch {
                for (path in queue) {
                    try {
                        runOneTurn(path, preferCloud, onTurn, onPhoto)
                    } catch (t: Throwable) {
                        println("[InspectionSession] turn failed: ${t::class.simpleName}: ${t.message}")
                        t.printStackTrace()
                        onError(t.message ?: "Turn error")
                    }
                }
            }
        }
    }

    private suspend fun runOneTurn(
        audioPath: String,
        preferCloud: Boolean,
        onTurn: (TurnResult) -> Unit,
        onPhoto: (String) -> Unit,
    ) {
        // Per-turn photo slot — capture_photo tool writes it, add_note reads it
        // so the photo auto-attaches to any notes recorded in the same turn.
        val turnPhoto = arrayOf<String?>(null)

        val result = agent.processTurn(
            audioFilePath = audioPath,
            imageFilePaths = emptyList(),
            systemPrompt = InspectionPrompt.SYSTEM_PROMPT,
            tools = InspectionPrompt.TOOLS,
            dispatcher = buildDispatcher(turnPhoto, onPhoto),
            preferCloud = preferCloud,
        )
        onTurn(result)
    }

    private fun buildDispatcher(
        turnPhoto: Array<String?>,
        onPhoto: (String) -> Unit,
    ): ToolDispatcher = ToolDispatcher { call ->
        when (call.name) {
            "add_note" -> handleAddNote(call, turnPhoto[0])
            "drop_pin" -> handleDropPin(call)
            "query_docs" -> handleQueryDocs(call)
            "read_notes" -> handleReadNotes(call)
            "capture_photo" -> handleCapturePhoto(call, turnPhoto, onPhoto)
            else -> ToolResult(call.id, call.name, """{"error":"unknown tool"}""")
        }
    }

    private suspend fun handleCapturePhoto(
        call: ToolCall,
        turnPhoto: Array<String?>,
        onPhoto: (String) -> Unit,
    ): ToolResult {
        val path = camera.capture(timeoutMs = 3000)
        if (path == null) {
            return ToolResult(call.id, call.name, """{"ok":false,"reason":"capture failed or timed out"}""")
        }
        turnPhoto[0] = path
        onPhoto(path)
        return ToolResult(call.id, call.name, """{"ok":true}""")
    }

    private fun handleReadNotes(call: ToolCall): ToolResult {
        val md = notes.render()
        val escaped = md.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val pinList = building.pins.value
        val pinsJson = if (pinList.isEmpty()) "[]" else pinList.joinToString(",", "[", "]") { p ->
            """{"label":"${p.label.replace("\"", "\\\"")}","severity":"${p.severity.key}","x":${p.x},"y":${p.y}}"""
        }
        return ToolResult(call.id, call.name, """{"notes_markdown":"$escaped","pins":$pinsJson}""")
    }

    private fun handleAddNote(call: ToolCall, photoPath: String?): ToolResult {
        val args = parseArgs(call.argsJson)
        val md = args["markdown"]?.jsonPrimitive?.contentOrNull ?: ""
        val catKey = args["category"]?.jsonPrimitive?.contentOrNull ?: "other"
        val category = NoteCategory.fromKey(catKey)
        if (md.isNotBlank()) {
            notes.add(category, md, photoPath, currentTimeMs())
        }
        return ToolResult(call.id, call.name, """{"ok":true}""")
    }

    private fun handleDropPin(call: ToolCall): ToolResult {
        if (building.floorPlanPath.value == null) {
            return ToolResult(call.id, call.name, """{"ok":false,"reason":"no floor plan loaded"}""")
        }
        val args = parseArgs(call.argsJson)
        val x = args["x"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return ToolResult(call.id, call.name, """{"ok":false,"reason":"missing x"}""")
        val y = args["y"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: return ToolResult(call.id, call.name, """{"ok":false,"reason":"missing y"}""")
        val label = args["label"]?.jsonPrimitive?.contentOrNull ?: "POI"
        val severity = PinSeverity.fromKey(args["severity"]?.jsonPrimitive?.contentOrNull ?: "info")
        building.addPin(x, y, label, severity)
        return ToolResult(call.id, call.name, """{"ok":true}""")
    }

    private suspend fun handleQueryDocs(call: ToolCall): ToolResult {
        val args = parseArgs(call.argsJson)
        val question = args["question"]?.jsonPrimitive?.contentOrNull ?: ""
        val handle = agent.modelHandleOrZero()
        if (handle == 0L) return ToolResult(call.id, call.name, """{"passages":""}""")
        val passages = rag.query(handle, question, topK = 4)
        val escaped = passages.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return ToolResult(call.id, call.name, """{"passages":"$escaped"}""")
    }

    /** Ingest a building document by chunking, embedding, and adding to the RAG index. */
    suspend fun ingestDocument(sourceName: String, text: String): Int {
        val handle = agent.modelHandleOrZero()
        if (handle == 0L) return 0
        val count = rag.addDocument(handle, sourceName, text)
        building.setDocsIndexed(building.docsIndexedChunks.value + count)
        return count
    }

    fun loadFloorPlan(path: String?) {
        building.setFloorPlan(path)
    }

    /** Manual photo trigger — e.g. phone UI button. */
    suspend fun captureNow(): String? = camera.capture()

    fun stop() {
        wearableConnector.stopAudioStream()
        collectJob?.cancel()
        collectJob = null
    }

    fun resetConversation() {
        agent.resetConversation()
        notes.clear()
        building.clear()
    }

    fun destroy() {
        stop()
        wearableConnector.disconnect()
        camera.close()
        rag.close()
        agent.release()
        scope.cancel()
    }

    private fun parseArgs(json: String): Map<String, kotlinx.serialization.json.JsonElement> = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Throwable) {
        emptyMap()
    }

    // Exposed separately so platforms can override (commonMain can't use Clock.System here).
    private fun currentTimeMs(): Long = currentTimeMillis()
}

/** Platform-provided millisecond clock so commonMain code doesn't drag in stdlib-mpp extras. */
expect fun currentTimeMillis(): Long
