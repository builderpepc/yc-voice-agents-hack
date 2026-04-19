package com.example.wearableai

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wearableai.shared.FormField
import com.example.wearableai.shared.FormSection
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

    private val _status = MutableStateFlow("Ready — tap Start Inspection to begin.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _inspectionEnabled = MutableStateFlow(false)
    val inspectionEnabled: StateFlow<Boolean> = _inspectionEnabled.asStateFlow()

    private val _inspectionLabel = MutableStateFlow("Start Inspection")
    val inspectionLabel: StateFlow<String> = _inspectionLabel.asStateFlow()

    private val _forceLocal = MutableStateFlow(false)
    val forceLocal: StateFlow<Boolean> = _forceLocal.asStateFlow()

    private val _pdfExported = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val pdfExported: SharedFlow<File> = _pdfExported.asSharedFlow()

    val notes get() = session.notes.notes
    val formFields: StateFlow<Map<String, FormField>> get() = session.form.fields
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
                _inspectionLabel.value = "Start Inspection"
                _inspectionEnabled.value = true
                return@launch
            }
            _status.value = "Connecting…"
            val connected = session.connect()
            if (!connected) {
                _status.value = "No glasses — using phone mic."
            }

            agentRunning = true
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
                        if (turn.assistantReply.isNotBlank()) speak(turn.assistantReply)
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
            _inspectionLabel.value = "Start Inspection"
            _status.value = "Inspection ended — glasses disconnected."
            _inspectionEnabled.value = true
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
        val filled = session.form.filledCount()
        val total = session.form.totalCount()
        if (filled == 0) {
            speak("No fields filled yet.")
            return
        }
        val gaps = session.form.gaps()
        val sb = StringBuilder()
        sb.append("$filled of $total fields filled. ")
        if (gaps.isNotEmpty()) {
            sb.append("Missing: ${gaps.take(5).joinToString(", ")}.")
            if (gaps.size > 5) sb.append(" And ${gaps.size - 5} more.")
        } else {
            sb.append("All fields complete!")
        }
        sb.append(" Pins on floor plan: ${pins.value.size}.")
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
                    PdfExporter.export(getApplication(), formFields.value)
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
