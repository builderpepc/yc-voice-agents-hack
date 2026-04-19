package com.example.wearableai

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wearableai.shared.InspectionPrompt
import com.example.wearableai.shared.InspectionSession
import com.example.wearableai.shared.ModelConfig
import com.example.wearableai.shared.NoteCategory
import com.example.wearableai.shared.Pin
import com.example.wearableai.shared.PinSeverity
import com.example.wearableai.shared.SessionSnapshot
import com.example.wearableai.shared.SessionSummary
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "inspection_prefs"
private const val KEY_CURRENT_SESSION = "currentSessionId"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val session = InspectionSession(
        cloudFallback = GeminiCloudFallback(),
        ragIndexDir = File(app.filesDir, "rag_index").apply { mkdirs() }.absolutePath,
    )

    private val store = InspectionStore(
        rootDir = File(app.filesDir, "inspections").apply { mkdirs() },
        saveScope = viewModelScope,
    )

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow("Loading last inspection…")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _inspectionEnabled = MutableStateFlow(false)
    val inspectionEnabled: StateFlow<Boolean> = _inspectionEnabled.asStateFlow()

    private val _inspectionLabel = MutableStateFlow("Start Inspection")
    val inspectionLabel: StateFlow<String> = _inspectionLabel.asStateFlow()

    /** "Start" for empty sessions, "Resume" once the session already has prior
     *  observations or conversation history to pick back up from. */
    private fun idleLabel(): String {
        val hasState = session.notes.notes.value.isNotEmpty() ||
            session.building.pins.value.isNotEmpty() ||
            session.historySnapshot().isNotEmpty()
        return if (hasState) "Resume Inspection" else "Start Inspection"
    }

    private val _forceLocal = MutableStateFlow(false)
    val forceLocal: StateFlow<Boolean> = _forceLocal.asStateFlow()

    private val _pdfExported = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val pdfExported: SharedFlow<File> = _pdfExported.asSharedFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _currentSessionName = MutableStateFlow("")
    val currentSessionName: StateFlow<String> = _currentSessionName.asStateFlow()

    val notes get() = session.notes.notes
    val pins: StateFlow<List<Pin>> get() = session.building.pins
    val floorPlanPath: StateFlow<String?> get() = session.building.floorPlanPath
    val docsIndexedChunks: StateFlow<Int> get() = session.building.docsIndexedChunks
    val deferredPhotos get() = session.deferredPhotos

    private var agentRunning = false
    private var ttsReady = false
    private var tts: TextToSpeech? = null

    // Session metadata for the active snapshot — kept out of SessionSnapshot so
    // the VM can build a fresh snapshot on every mutation without re-parsing disk.
    private var currentCreatedAtMs: Long = 0L

    private val connectivityManager =
        app.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Regardless of force-local state, as soon as internet returns we try
            // to drain any photos captured while offline. No-op if queue is empty.
            viewModelScope.launch { maybeDrainDeferredPhotos(reason = "network returned") }
        }
    }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
        session.onMutation = { scheduleSave() }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(req, networkCallback)
        viewModelScope.launch { resumeOrCreateSession() }
    }

    /** Drains deferred photos via cloud if there's anything to drain and we're
     *  actually online. Safe to call from any trigger (network-return, toggle,
     *  manual). Concurrent calls are serialized by [InspectionSession.drainDeferredPhotos]. */
    private suspend fun maybeDrainDeferredPhotos(reason: String) {
        val pending = session.deferredPhotos.value
        if (pending.isEmpty() || !isOnline()) return
        val count = pending.size
        android.util.Log.d("MainViewModel", "drain trigger=$reason count=$count")
        _status.value = "Reconciling $count photo${if (count == 1) "" else "s"}…"
        val attached = session.drainDeferredPhotos()
        _status.value = "Reconciled $attached of $count."
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.currentTimeMillis()}")
    }

    fun setForceLocal(enabled: Boolean) {
        val previous = _forceLocal.value
        _forceLocal.value = enabled
        // Flipping back to cloud drains any photos captured while offline, even
        // without waiting for the next utterance — so the user sees attachments
        // resolve immediately after switching.
        if (previous && !enabled) {
            viewModelScope.launch { maybeDrainDeferredPhotos(reason = "toggle off") }
        }
    }

    fun onPermissionsGranted() {
        _status.value = "Ready — tap Start Inspection."
        _inspectionEnabled.value = true
    }

    fun onWearablePermissionDenied() {
        _status.value = "Microphone permission denied — cannot use glasses mic."
        _inspectionEnabled.value = false
    }

    fun onStatus(msg: String) { _status.value = msg }

    /**
     * Single button that gates the whole inspection lifecycle. Idle taps spin up
     * the model + glasses connection and start the agent loop; running taps tear
     * down audio + camera so the glasses are free between inspections.
     */
    fun toggleInspection() {
        if (!agentRunning) startInspection() else stopInspection()
    }

    private fun startInspection() {
        _inspectionEnabled.value = false
        _inspectionLabel.value = "Starting…"
        viewModelScope.launch {
            _status.value = "Loading model…"
            try {
                session.init(resolveModelPath())
            } catch (e: Throwable) {
                _status.value = "Model load failed: ${e.message}"
                _inspectionLabel.value = idleLabel()
                _inspectionEnabled.value = true
                return@launch
            }
            _status.value = "Connecting to glasses…"
            val connected = session.connect()
            if (!connected) {
                _status.value = "Connection failed — is the device paired?"
                _inspectionLabel.value = idleLabel()
                _inspectionEnabled.value = true
                return@launch
            }

            agentRunning = true
            val usingCloud = isOnline() && !_forceLocal.value
            _status.value = if (usingCloud) "Inspecting — Gemini 2.5 Flash." else "Inspecting — local Gemma 3 1B."
            session.start(
                preferCloud = usingCloud,
                onUtterance = {
                    viewModelScope.launch { _status.value = if (usingCloud) "Thinking… (Gemini)" else "Thinking… (Gemma 3 1B)" }
                },
                onTurn = { turn ->
                    viewModelScope.launch {
                        _status.value = if (usingCloud) "Inspecting — Gemini 2.5 Flash." else "Inspecting — local Gemma 3 1B."
                        // Local path: Gemma 3 1B 270M tends to paraphrase the system
                        // prompt back at us for observation turns instead of staying
                        // silent like we asked. Enforce silence at the TTS layer —
                        // only speak when the transcript is clearly a direct question.
                        val shouldSpeak = turn.assistantReply.isNotBlank() &&
                            (usingCloud || InspectionPrompt.isQATrigger(turn.userTranscript))
                        if (shouldSpeak) speak(turn.assistantReply)
                    }
                },
                onPhoto = { path ->
                    viewModelScope.launch { _status.value = "📸 captured ${File(path).name}" }
                },
                onError = { error ->
                    // Per-turn errors (bad audio, camera glitch, rate limit) shouldn't
                    // tear down the inspection. Keep the loop + button state alive.
                    viewModelScope.launch { _status.value = "Error: $error" }
                },
            )
            _inspectionLabel.value = "Stop Inspection"
            _inspectionEnabled.value = true
        }
    }

    private fun stopInspection() {
        _inspectionEnabled.value = false
        _inspectionLabel.value = "Stopping…"
        viewModelScope.launch {
            session.endSession()
            agentRunning = false
            _inspectionLabel.value = idleLabel()
            _status.value = "Inspection ended — glasses disconnected."
            _inspectionEnabled.value = true
        }
    }

    fun loadFloorPlan(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val sessionId = _currentSessionId.value
            if (sessionId.isEmpty()) {
                _status.value = "Floor plan load failed: no active session."
                return@launch
            }
            // Write through a temp file then let the store archive it into the session dir.
            val tmp = File(app.cacheDir, "floorplan_${System.currentTimeMillis()}.img")
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("cannot open uri")
                    val archived = store.archiveFloorPlan(sessionId, tmp)
                    tmp.delete()
                    session.loadFloorPlan(archived.absolutePath)
                }
                _status.value = "Floor plan loaded."
            } catch (e: Throwable) {
                tmp.delete()
                _status.value = "Floor plan load failed: ${e.message}"
            }
        }
    }

    fun ingestDocs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            var totalChunks = 0
            var failed = 0
            for (uri in uris) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "doc_${System.currentTimeMillis()}"
                _status.value = "Indexing $name…"
                try {
                    val text = withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    } ?: ""
                    if (text.isBlank()) { failed++; continue }
                    totalChunks += session.ingestDocument(name, text)
                } catch (e: Throwable) {
                    android.util.Log.w("MainViewModel", "ingest failed for $name: ${e.message}")
                    failed++
                }
            }
            _status.value = "Indexed $totalChunks chunks" + if (failed > 0) " (${failed} files skipped)" else ""
        }
    }

    /** Speaks a locally-generated recap. Cheaper and more reliable than a model roundtrip. */
    fun speakSummary() {
        val list = notes.value
        if (list.isEmpty()) {
            speak("No observations recorded yet.")
            return
        }
        val byCat = list.groupBy { it.category }
        val sb = StringBuilder()
        sb.append("${list.size} observation${if (list.size == 1) "" else "s"} so far. ")
        for (cat in NoteCategory.entries) {
            val items = byCat[cat] ?: continue
            sb.append("${cat.heading}: ${items.size}. ")
        }
        sb.append("Pins on floor plan: ${pins.value.size}.")
        speak(sb.toString())
        _status.value = "Speaking summary."
    }

    fun addManualPin(x: Float, y: Float, label: String = "manual", severity: PinSeverity = PinSeverity.INFO) {
        session.addManualPin(x, y, label, severity)
    }

    fun exportPdf() {
        viewModelScope.launch {
            _status.value = "Exporting PDF…"
            try {
                val file = withContext(Dispatchers.IO) {
                    PdfExporter.export(getApplication(), notes.value)
                }
                _status.value = "Saved: ${file.name}"
                android.util.Log.i("MainViewModel", "PDF exported to ${file.absolutePath}")
                _pdfExported.tryEmit(file)
            } catch (e: Throwable) {
                _status.value = "PDF export failed: ${e.message}"
                android.util.Log.e("MainViewModel", "PDF export failed", e)
            }
        }
    }

    fun captureNow() {
        viewModelScope.launch {
            val path = session.captureNow()
            _status.value = if (path != null) "📸 captured ${File(path).name}" else "Capture failed."
        }
    }

    fun clearNotes() {
        session.resetConversation()
        _status.value = "Notes and pins cleared."
        scheduleSave()
    }

    // region Session management

    fun refreshSessionList() {
        viewModelScope.launch(Dispatchers.IO) {
            _sessions.value = store.listSummaries()
        }
    }

    fun newInspection() {
        viewModelScope.launch {
            if (agentRunning) stopInspectionSuspending()
            store.flushPendingSave()
            val snap = store.create()
            applyLoadedSnapshot(snap)
            refreshSessionList()
            _status.value = "Started new inspection."
        }
    }

    fun loadSession(id: String) {
        viewModelScope.launch {
            if (agentRunning) stopInspectionSuspending()
            store.flushPendingSave()
            try {
                val snap = withContext(Dispatchers.IO) { store.load(id) }
                applyLoadedSnapshot(snap)
                refreshSessionList()
                _status.value = "Loaded ${snap.name}."
            } catch (t: Throwable) {
                _status.value = "Load failed: ${t.message}"
            }
        }
    }

    /** Inline sibling of [stopInspection] that the session-switch flow can suspend on. */
    private suspend fun stopInspectionSuspending() {
        _inspectionEnabled.value = false
        _inspectionLabel.value = "Stopping…"
        session.endSession()
        agentRunning = false
        _inspectionLabel.value = idleLabel()
        _inspectionEnabled.value = true
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            // If we deleted the active session, fall back to a fresh one.
            if (id == _currentSessionId.value) {
                val snap = store.create()
                withContext(Dispatchers.Main) { applyLoadedSnapshot(snap) }
            }
            _sessions.value = store.listSummaries()
        }
    }

    fun renameSession(id: String, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, newName) }
            if (id == _currentSessionId.value) {
                _currentSessionName.value = newName.trim()
            }
            refreshSessionList()
        }
    }

    private suspend fun resumeOrCreateSession() {
        val lastId = prefs.getString(KEY_CURRENT_SESSION, null)
        val snap = if (lastId != null && store.sessionDir(lastId).isDirectory) {
            runCatching { withContext(Dispatchers.IO) { store.load(lastId) } }.getOrNull()
                ?: store.create()
        } else {
            store.create()
        }
        applyLoadedSnapshot(snap)
        _status.value = "Ready — tap Start Inspection."
        refreshSessionList()
    }

    /** Apply a loaded [SessionSnapshot] to in-memory state. onMutation is detached
     *  during the swap so the restore itself doesn't trigger a redundant save. */
    private suspend fun applyLoadedSnapshot(snap: SessionSnapshot) {
        session.onMutation = null
        try {
            session.notes.replaceAll(snap.notes)
            val floorAbs = snap.floorPlanRelPath?.let {
                File(store.sessionDir(snap.id), it).absolutePath
            }
            session.building.replace(floorAbs, snap.pins)
            session.restoreConversation(snap.history)
            session.setSessionPhotosDir(store.photosDir(snap.id).absolutePath)
            session.setDeferredPhotos(snap.deferredPhotos)

            _currentSessionId.value = snap.id
            _currentSessionName.value = snap.name
            currentCreatedAtMs = snap.createdAtMs
            prefs.edit().putString(KEY_CURRENT_SESSION, snap.id).apply()
            if (!agentRunning) _inspectionLabel.value = idleLabel()
        } finally {
            session.onMutation = { scheduleSave() }
        }
    }

    /** Build a snapshot of the current in-memory state and schedule a debounced save. */
    private fun scheduleSave() {
        val id = _currentSessionId.value
        if (id.isEmpty()) return
        val floorAbs = session.building.floorPlanPath.value
        val sessionDirPath = store.sessionDir(id).absolutePath
        val floorRel = floorAbs?.let {
            if (it.startsWith(sessionDirPath)) it.removePrefix("$sessionDirPath/") else null
        }
        val snap = SessionSnapshot(
            id = id,
            name = _currentSessionName.value,
            createdAtMs = if (currentCreatedAtMs > 0L) currentCreatedAtMs else System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            floorPlanRelPath = floorRel,
            notes = session.notes.notes.value,
            pins = session.building.pins.value,
            history = session.historySnapshot(),
            deferredPhotos = session.deferredPhotosSnapshot(),
        )
        store.scheduleSave(snap)
    }

    // endregion

    override fun onCleared() {
        super.onCleared()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        session.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    private fun resolveModelPath(): String {
        val name = ModelConfig.GEMMA3_1B_DIR
        val internal = File(getApplication<Application>().filesDir, name)
        if (internal.isDirectory) return internal.absolutePath
        val sdCard = File(Environment.getExternalStorageDirectory(), name)
        if (sdCard.isDirectory) return sdCard.absolutePath
        return internal.absolutePath
    }

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
