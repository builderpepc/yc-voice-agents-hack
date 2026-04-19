package com.example.wearableai

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wearableai.shared.InspectionSession
import com.example.wearableai.shared.ModelConfig
import com.example.wearableai.shared.NoteCategory
import com.example.wearableai.shared.Pin
import com.example.wearableai.shared.PinSeverity
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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val session = InspectionSession(
        cloudFallback = GeminiCloudFallback(),
        ragIndexDir = File(app.filesDir, "rag_index").apply { mkdirs() }.absolutePath,
    )

    private val _status = MutableStateFlow("Ready — tap Connect Glasses to begin.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connectEnabled = MutableStateFlow(false)
    val connectEnabled: StateFlow<Boolean> = _connectEnabled.asStateFlow()

    private val _agentEnabled = MutableStateFlow(false)
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()

    private val _agentLabel = MutableStateFlow("Start Inspection")
    val agentLabel: StateFlow<String> = _agentLabel.asStateFlow()

    private val _forceLocal = MutableStateFlow(false)
    val forceLocal: StateFlow<Boolean> = _forceLocal.asStateFlow()

    private val _pdfExported = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val pdfExported: SharedFlow<File> = _pdfExported.asSharedFlow()

    val notes get() = session.notes.notes
    val pins: StateFlow<List<Pin>> get() = session.building.pins
    val floorPlanPath: StateFlow<String?> get() = session.building.floorPlanPath
    val docsIndexedChunks: StateFlow<Int> get() = session.building.docsIndexedChunks

    private var agentRunning = false
    private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-${System.currentTimeMillis()}")
    }

    fun setForceLocal(enabled: Boolean) { _forceLocal.value = enabled }

    fun onPermissionsGranted() {
        _status.value = "Ready — tap Connect Glasses."
        _connectEnabled.value = true
    }

    fun onWearablePermissionDenied() {
        _status.value = "Microphone permission denied — cannot use glasses mic."
        _connectEnabled.value = false
    }

    fun onStatus(msg: String) { _status.value = msg }

    fun connectGlasses() {
        viewModelScope.launch {
            _status.value = "Connecting to glasses…"
            _connectEnabled.value = false

            try {
                session.init(resolveModelPath())
            } catch (e: Throwable) {
                _status.value = "Model load failed: ${e.message}"
                _connectEnabled.value = true
                return@launch
            }

            val connected = session.connect()
            if (connected) {
                _status.value = "Connected. Tap Start Inspection."
                _agentEnabled.value = true
            } else {
                _status.value = "Connection failed — is the device paired?"
                _connectEnabled.value = true
            }
        }
    }

    fun toggleAgent() {
        if (!agentRunning) {
            agentRunning = true
            _agentLabel.value = "Stop Inspection"
            val usingCloud = isOnline() && !_forceLocal.value
            _status.value = if (usingCloud) "Inspecting — Gemini 2.5 Flash." else "Inspecting — local Gemma 4 E2B."

            session.start(
                preferCloud = usingCloud,
                onUtterance = {
                    viewModelScope.launch { _status.value = if (usingCloud) "Thinking… (Gemini)" else "Thinking… (Gemma 4)" }
                },
                onTurn = { turn ->
                    viewModelScope.launch {
                        _status.value = if (usingCloud) "Inspecting — Gemini 2.5 Flash." else "Inspecting — local Gemma 4 E2B."
                        // Silent mode: model returns an empty reply when it only logged notes.
                        // Only speak when the user explicitly asked something.
                        if (turn.assistantReply.isNotBlank()) speak(turn.assistantReply)
                    }
                },
                onPhoto = { path ->
                    viewModelScope.launch { _status.value = "📸 captured ${File(path).name}" }
                },
                onError = { error ->
                    // Per-turn errors (bad audio, camera glitch, rate limit) shouldn't
                    // tear down the inspection. Surface the message but keep the
                    // agent loop + button state alive so the user can keep working
                    // or stop manually.
                    viewModelScope.launch { _status.value = "Error: $error" }
                },
            )
        } else {
            agentRunning = false
            _agentLabel.value = "Start Inspection"
            _status.value = "Inspection paused."
            session.stop()
        }
    }

    fun loadFloorPlan(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val cached = File(app.cacheDir, "floorplan_${System.currentTimeMillis()}.img")
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        cached.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("cannot open uri")
                }
                session.loadFloorPlan(cached.absolutePath)
                _status.value = "Floor plan loaded."
            } catch (e: Throwable) {
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
        session.building.addPin(x, y, label, severity)
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
    }

    override fun onCleared() {
        super.onCleared()
        session.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    private fun resolveModelPath(): String {
        val name = ModelConfig.GEMMA4_DIR
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
