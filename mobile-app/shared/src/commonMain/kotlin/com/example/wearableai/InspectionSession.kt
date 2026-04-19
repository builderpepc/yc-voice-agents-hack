package com.example.wearableai.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val cloudFallback: CloudFallback,
    private val ragIndexDir: String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agent = VoiceAgent(cloudFallback)
    // Serializes reconciliation against itself so toggle-flip + top-of-turn
    // drains can't double-fire against the same queue.
    private val reconcileMutex = kotlinx.coroutines.sync.Mutex()

    val notes = NoteTaker()
    val building = BuildingContext()
    private val rag = BuildingDocRag(ragIndexDir)
    private val camera = CameraCapture()

    // Photos captured during force-local operation that are awaiting cloud
    // reconciliation. Snapshot persists so a restart doesn't lose them.
    private val _deferredPhotos = MutableStateFlow<List<DeferredPhoto>>(emptyList())
    val deferredPhotos: StateFlow<List<DeferredPhoto>> = _deferredPhotos.asStateFlow()

    fun deferredPhotosSnapshot(): List<DeferredPhoto> = _deferredPhotos.value
    fun setDeferredPhotos(list: List<DeferredPhoto>) { _deferredPhotos.value = list }

    /** Fired after any mutation the store should persist (note added, pin dropped,
     *  photo captured, turn completed, floor plan loaded). The ViewModel wires
     *  this to a debounced `scheduleSave(snapshot())`. */
    var onMutation: (() -> Unit)? = null

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
                if (preferCloud) {
                    wearableConnector.startAudioStream { utteranceWavPath ->
                        onUtterance(utteranceWavPath)
                        queue.trySend(utteranceWavPath)
                    }
                } else {
                    // Local path: SpeechRecognizer emits a final transcript per utterance.
                    // We push the transcript through the same queue so the consumer loop
                    // below can stay shape-compatible with the cloud path.
                    wearableConnector.startTranscriptStream { transcript ->
                        onUtterance(transcript)
                        queue.trySend(transcript)
                    }
                }
            }
            launch {
                for (item in queue) {
                    try {
                        if (preferCloud) runCloudTurn(item, onTurn, onPhoto)
                        else runLocalTurn(item, onTurn, onPhoto)
                    } catch (t: Throwable) {
                        println("[InspectionSession] turn failed: ${t::class.simpleName}: ${t.message}")
                        t.printStackTrace()
                        onError(t.message ?: "Turn error")
                    }
                }
            }
        }
    }

    private suspend fun runCloudTurn(
        audioPath: String,
        onTurn: (TurnResult) -> Unit,
        onPhoto: (String) -> Unit,
    ) {
        // Drain any photos queued while we were force-local. Gemini reconciles
        // them against the notes recorded so far before we process this turn.
        drainDeferredPhotos()

        // Per-turn photo slot — capture_photo tool writes it, add_note reads it
        // so the photo auto-attaches to any notes recorded in the same turn.
        val turnPhoto = arrayOf<String?>(null)
        val result = agent.processTurn(
            audioFilePath = audioPath,
            imageFilePaths = emptyList(),
            systemPrompt = InspectionPrompt.SYSTEM_PROMPT_CLOUD,
            tools = InspectionPrompt.TOOLS,
            dispatcher = buildDispatcher(turnPhoto, onPhoto, deferPhotos = false, transcriptForDeferral = null),
            preferCloud = true,
        )
        onMutation?.invoke()
        onTurn(result)
    }

    private suspend fun runLocalTurn(
        transcript: String,
        onTurn: (TurnResult) -> Unit,
        onPhoto: (String) -> Unit,
    ) {
        // Local path: FunctionGemma is text-only. Photos captured via capture_photo
        // go onto a deferred queue and will be reconciled by Gemini on the next
        // cloud turn. turnPhoto is unused here (kept for dispatcher-signature parity).
        val turnPhoto = arrayOf<String?>(null)
        val result = agent.processTurnText(
            transcript = transcript,
            systemPrompt = InspectionPrompt.SYSTEM_PROMPT_LOCAL,
            tools = InspectionPrompt.TOOLS,
            dispatcher = buildDispatcher(turnPhoto, onPhoto, deferPhotos = true, transcriptForDeferral = transcript),
        )
        onMutation?.invoke()
        onTurn(result)
    }

    /**
     * Reconciles the deferred-photo queue against the current notes via the
     * cloud backend. Safe to call from the VM (e.g. when the user flips force-
     * local off) or automatically at the start of each cloud turn. No-op when
     * the queue is empty or reconciliation fails — failed items remain queued
     * for a later retry.
     */
    suspend fun drainDeferredPhotos(): Int = reconcileMutex.withLock {
        val pending = _deferredPhotos.value
        if (pending.isEmpty()) return 0
        val result = try {
            cloudFallback.reconcilePhotos(notes.notes.value, pending)
        } catch (t: Throwable) {
            println("[InspectionSession] reconcilePhotos failed: ${t::class.simpleName}: ${t.message}")
            return 0
        }
        var attached = 0
        val resolvedPaths = mutableSetOf<String>()
        for (att in result) {
            resolvedPaths.add(att.photoPath)
            val noteId = att.noteId ?: continue
            if (notes.attachPhoto(noteId, att.photoPath)) attached++
        }
        // Drop every photo the model considered — even unmatched ones — so we
        // don't endlessly re-send the same null-attachments on every cloud turn.
        _deferredPhotos.value = pending.filter { it.photoPath !in resolvedPaths }
        onMutation?.invoke()
        attached
    }

    /** Exposed so the VM can grab the agent's history for session persistence. */
    fun historySnapshot(): List<Map<String, String>> = agent.historySnapshot()

    /** Restore a loaded session's conversation history into the agent. Must be
     *  called on the IO/Default scope — it suspends on the agent's turn mutex. */
    suspend fun restoreConversation(saved: List<Map<String, String>>) {
        agent.restoreConversation(saved)
    }

    /** Redirect subsequent captures into [dir]. Passing null restores cache-dir default. */
    fun setSessionPhotosDir(dir: String?) {
        camera.setOutputDir(dir)
    }

    private fun buildDispatcher(
        turnPhoto: Array<String?>,
        onPhoto: (String) -> Unit,
        deferPhotos: Boolean,
        transcriptForDeferral: String?,
    ): ToolDispatcher = ToolDispatcher { call ->
        when (call.name) {
            // On the local path add_note never gets a photo inline — photos are
            // deferred and reconciled later by Gemini. Pass null so the note is
            // recorded photo-less; reconciliation may patch photoPath in later.
            "add_note" -> handleAddNote(call, if (deferPhotos) null else turnPhoto[0])
            "drop_pin" -> handleDropPin(call)
            "query_docs" -> handleQueryDocs(call)
            "read_notes" -> handleReadNotes(call)
            "capture_photo" -> handleCapturePhoto(call, turnPhoto, onPhoto, deferPhotos, transcriptForDeferral)
            else -> ToolResult(call.id, call.name, """{"error":"unknown tool"}""")
        }
    }

    private suspend fun handleCapturePhoto(
        call: ToolCall,
        turnPhoto: Array<String?>,
        onPhoto: (String) -> Unit,
        deferPhotos: Boolean,
        transcriptForDeferral: String?,
    ): ToolResult {
        val path = camera.capture(timeoutMs = 3000)
        if (path == null) {
            return ToolResult(call.id, call.name, """{"ok":false,"reason":"capture failed or timed out"}""")
        }
        if (deferPhotos) {
            _deferredPhotos.value = _deferredPhotos.value + DeferredPhoto(
                photoPath = path,
                capturedAtMs = currentTimeMs(),
                transcript = transcriptForDeferral.orEmpty(),
            )
            onPhoto(path)
            onMutation?.invoke()
            return ToolResult(call.id, call.name, """{"ok":true,"deferred":true}""")
        }
        turnPhoto[0] = path
        onPhoto(path)
        onMutation?.invoke()
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
            onMutation?.invoke()
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
        onMutation?.invoke()
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
        onMutation?.invoke()
    }

    /** Session-layer helper so the manual-pin path also fires [onMutation]. */
    fun addManualPin(x: Float, y: Float, label: String, severity: PinSeverity) {
        building.addPin(x, y, label, severity)
        onMutation?.invoke()
    }

    /** Manual photo trigger — e.g. phone UI button. */
    suspend fun captureNow(): String? {
        val path = camera.capture()
        if (path != null) onMutation?.invoke()
        return path
    }

    fun stop() {
        wearableConnector.stopAudioStream()
        wearableConnector.stopTranscriptStream()
        collectJob?.cancel()
        collectJob = null
    }

    /** End the active inspection: stop audio, unroute BT SCO, close camera stream.
     *  Keeps the loaded model + RAG index so the next session can start fast. */
    fun endSession() {
        stop()
        wearableConnector.endSession()
        camera.close()
    }

    fun resetConversation() {
        agent.resetConversation()
        notes.clear()
        building.clear()
        _deferredPhotos.value = emptyList()
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
