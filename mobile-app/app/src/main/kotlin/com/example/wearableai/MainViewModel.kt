package com.example.wearableai

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wearableai.shared.ModelConfig
import com.example.wearableai.shared.MobileAISession
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val session = MobileAISession(GeminiCloudFallback())

    private val _status = MutableStateFlow("Ready — tap Connect Mic to begin.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _connectEnabled = MutableStateFlow(false)
    val connectEnabled: StateFlow<Boolean> = _connectEnabled.asStateFlow()

    private val _agentEnabled = MutableStateFlow(false)
    val agentEnabled: StateFlow<Boolean> = _agentEnabled.asStateFlow()

    private val _agentLabel = MutableStateFlow("Start Agent")
    val agentLabel: StateFlow<String> = _agentLabel.asStateFlow()

    private val _forceLocal = MutableStateFlow(false)
    val forceLocal: StateFlow<Boolean> = _forceLocal.asStateFlow()

    private var agentRunning = false

    // Speaks assistant replies aloud. With the mic connected as the active
    // BT audio sink, Android routes system TTS output to them automatically.
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

    fun setForceLocal(enabled: Boolean) {
        _forceLocal.value = enabled
    }

    fun onPermissionsGranted() {
        _status.value = "Ready — tap Connect Mic."
        _connectEnabled.value = true
    }

    fun onMicPermissionDenied() {
        _status.value = "Microphone permission denied — cannot use mic mic."
        _connectEnabled.value = false
    }

    fun onStatus(msg: String) {
        _status.value = msg
    }

    fun connectMic() {
        viewModelScope.launch {
            _status.value = "Connecting to mic…"
            _connectEnabled.value = false

            val modelPath = resolveModelPath()
            try {
                session.init(modelPath)
            } catch (e: Throwable) {
                _status.value = "Model load failed: ${e.message}"
                _connectEnabled.value = true
                return@launch
            }

            val connected = session.connect()
            if (connected) {
                _status.value = "Connected. Tap Start Agent."
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
            _agentLabel.value = "Stop Agent"
            val usingCloud = isOnline() && !_forceLocal.value
            _status.value = if (usingCloud) "Running — Gemini 3.1 Flash." else "Running — local Gemma 4 E2B."

            session.start(
                preferCloud = usingCloud,
                onUtterance = {
                    viewModelScope.launch {
                        _status.value = if (usingCloud) "Thinking… (Gemini)" else "Thinking… (Gemma 4)"
                    }
                },
                onResponse = { turn ->
                    viewModelScope.launch {
                        val userLine = if (turn.userTranscript == "[silence]") "You: 🎤 (silence)"
                            else if (turn.userTranscript == "[audio]") "You: 🎤"
                            else "You: ${turn.userTranscript}"
                        val reply = turn.assistantReply.ifBlank { "(no reply)" }
                        _transcript.value = _transcript.value + "\n$userLine\nAssistant: $reply"
                        _status.value = if (usingCloud) "Running — Gemini 2.5 Flash." else "Running — local Gemma 4 E2B."
                        speak(turn.assistantReply)
                    }
                },
                onError = { error ->
                    viewModelScope.launch {
                        _status.value = "Error: $error"
                        agentRunning = false
                        _agentLabel.value = "Start Agent"
                    }
                },
            )
        } else {
            agentRunning = false
            _agentLabel.value = "Start Agent"
            _status.value = "Agent stopped."
            session.stop()
        }
    }

    override fun onCleared() {
        super.onCleared()
        session.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    private fun resolveModelPath(): String {
        val name = ModelConfig.GEMMA4_DIR
        // Prefer app internal ext4 storage — Cactus mmap+MADV_DONTNEED is unreliable
        // on FUSE-mounted /sdcard and crashes in cactus_gemm_int4 under memory pressure.
        val internal = File(getApplication<Application>().filesDir, name)
        if (internal.isDirectory) return internal.absolutePath
        val sdCard = File(Environment.getExternalStorageDirectory(), name)
        if (sdCard.isDirectory) return sdCard.absolutePath
        return internal.absolutePath
    }

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
