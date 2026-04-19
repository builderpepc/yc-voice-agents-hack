package com.example.wearableai.shared

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.meta.wearable.dat.core.Wearables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "WearableConnector"

// ApplicationContext injected once from WearableAIApp before any session starts.
lateinit var appContext: Context

actual val wearableConnector: WearableConnector = WearableConnector()

actual class WearableConnector {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var transcriptAsr: AndroidSpeechRecognizerAsr? = null

    actual suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d(TAG, "Calling Wearables.devices…")
            val devices = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                Wearables.devices.first { it.isNotEmpty() }
            }
            android.util.Log.d(TAG, "Wearables.devices result: $devices")
            if (devices == null || devices.isEmpty()) return@withContext false
            routeAudioToBluetoothSco()
            true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "connect() failed: ${e::class.simpleName}: ${e.message}", e)
            false
        }
    }

    actual fun endSession() {
        stopAudioStream()
        stopTranscriptStream()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    actual fun disconnect() {
        endSession()
        scope.cancel()
    }

    actual fun startAudioStream(onUtteranceReady: AudioChunkCallback) {
        stopAudioStream() // ensure any previous stream is fully stopped first
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096)

        // On emulator, VOICE_COMMUNICATION returns silence. Use MIC first.
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK")
                || Build.MANUFACTURER.contains("Google") && Build.PRODUCT.contains("sdk")

        val primarySource = if (isEmulator) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val fallbackSource = if (isEmulator) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val primaryLabel = if (isEmulator) "MIC" else "VOICE_COMMUNICATION"
        val fallbackLabel = if (isEmulator) "VOICE_COMMUNICATION" else "MIC"

        val record = AudioRecord(primarySource, sampleRate, channelConfig, encoding, bufferSize)

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            android.util.Log.e(TAG, "AudioRecord ($primaryLabel) failed to initialize — falling back to $fallbackLabel")
            record.release()
            startAudioStreamWithSource(fallbackSource, sampleRate, channelConfig, encoding, bufferSize, onUtteranceReady)
            return
        }

        android.util.Log.d(TAG, "AudioRecord initialized: source=$primaryLabel sampleRate=$sampleRate bufferSize=$bufferSize")
        audioRecord = record
        launchRecordLoop(record, bufferSize, onUtteranceReady)
    }

    private fun startAudioStreamWithSource(
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
        onUtteranceReady: AudioChunkCallback,
    ) {
        val record = AudioRecord(source, sampleRate, channelConfig, encoding, bufferSize)
        android.util.Log.d(TAG, "AudioRecord initialized: source=MIC sampleRate=$sampleRate")
        audioRecord = record
        launchRecordLoop(record, bufferSize, onUtteranceReady)
    }

    private fun launchRecordLoop(record: AudioRecord, bufferSize: Int, onUtteranceReady: AudioChunkCallback) {
        record.startRecording()
        val sampleRate = 16_000
        recordJob = scope.launch {
            val chunk = ByteArray(bufferSize)
            val utterance = mutableListOf<ByteArray>()
            var silenceFrames = 0
            var totalFrames = 0
            // ~700 ms of silence to end an utterance
            val silenceThreshold = (0.7 * sampleRate * 2 / bufferSize).toInt().coerceAtLeast(1)
            // minimum ~0.5 s of speech to avoid BT noise bursts (500ms * 16000 * 2 / bufferSize)
            val minSpeechChunks = (0.5 * sampleRate * 2 / bufferSize).toInt().coerceAtLeast(1)

            android.util.Log.d(TAG, "Audio loop started. silenceThreshold=$silenceThreshold minSpeech=$minSpeechChunks")

            while (isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) { delay(10); continue }

                val frame = chunk.copyOf(read)
                val peak = frame.peakAmplitude()
                // 1500 was too high — agent was missing normal-volume speech.
                // 800 still rejects HVAC hum / breathing seen at ~500 peak.
                val isSilent = peak < 800

                totalFrames++
                if (totalFrames % 100 == 0) {
                    android.util.Log.d(TAG, "Audio: frames=$totalFrames peak=$peak utteranceChunks=${utterance.size} silenceFrames=$silenceFrames")
                }

                if (!isSilent) {
                    silenceFrames = 0
                    utterance.add(frame)
                } else if (utterance.isNotEmpty()) {
                    silenceFrames++
                    utterance.add(frame)
                    if (silenceFrames >= silenceThreshold) {
                        val speechChunks = utterance.size - silenceFrames
                        if (speechChunks >= minSpeechChunks) {
                            val wavPath = flushUtteranceToWav(utterance, sampleRate)
                            android.util.Log.d(TAG, "Utterance ready: speechChunks=$speechChunks path=$wavPath")
                            onUtteranceReady(wavPath)
                        } else {
                            android.util.Log.d(TAG, "Skipped short utterance: speechChunks=$speechChunks < min=$minSpeechChunks")
                        }
                        utterance.clear()
                        silenceFrames = 0
                    }
                }
            }
            android.util.Log.d(TAG, "Audio loop exited cleanly")
        }
    }

    actual fun stopAudioStream() {
        // Stop AudioRecord first so the blocking read() call returns, then cancel the coroutine.
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordJob?.cancel()
        recordJob = null
        android.util.Log.d(TAG, "Audio stream stopped")
    }

    actual fun startTranscriptStream(onTranscript: TranscriptCallback) {
        stopTranscriptStream()
        // The BT SCO route established by connect() applies system-wide for
        // VOICE_COMMUNICATION-sourced consumers; the recognizer inherits it.
        val asr = AndroidSpeechRecognizerAsr(appContext)
        transcriptAsr = asr
        asr.start(onTranscript)
        android.util.Log.d(TAG, "Transcript stream started (SpeechRecognizer)")
    }

    actual fun stopTranscriptStream() {
        transcriptAsr?.stop()
        transcriptAsr = null
        android.util.Log.d(TAG, "Transcript stream stopped")
    }

    private fun routeAudioToBluetoothSco() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val allDevices = audioManager.availableCommunicationDevices
            android.util.Log.d(TAG, "availableCommunicationDevices: ${allDevices.map { "${it.type}(${it.productName})" }}")

            val btDevice = allDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (btDevice != null) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                val ok = audioManager.setCommunicationDevice(btDevice)
                android.util.Log.d(TAG, "Routed to BT SCO (${btDevice.productName}): success=$ok")
            } else {
                android.util.Log.w(TAG, "No BT SCO device found — recording from default mic")
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            android.util.Log.d(TAG, "startBluetoothSco() called (pre-S device)")
        }
    }

    private fun flushUtteranceToWav(frames: List<ByteArray>, sampleRate: Int): String {
        val pcm = frames.fold(ByteArray(0)) { acc, b -> acc + b }
        val file = File(appContext.cacheDir, "utterance_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { fos ->
            fos.write(wavHeader(pcm.size, sampleRate))
            fos.write(pcm)
        }
        return file.absolutePath
    }

    private fun ByteArray.peakAmplitude(): Int {
        var peak = 0
        for (i in 0 until size - 1 step 2) {
            val sample = kotlin.math.abs((this[i + 1].toInt() shl 8) or (this[i].toInt() and 0xFF))
            if (sample > peak) peak = sample
        }
        return peak
    }

    private fun wavHeader(pcmBytes: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + pcmBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(pcmBytes)
        }.array()
    }
}
