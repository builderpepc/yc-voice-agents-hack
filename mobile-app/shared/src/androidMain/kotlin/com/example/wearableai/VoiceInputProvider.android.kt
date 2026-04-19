package com.example.wearableai.shared

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "VoiceInputProvider"

// ApplicationContext injected once from MobileAIApp before any session starts.
lateinit var appContext: Context

actual val voiceInputProvider: VoiceInputProvider = VoiceInputProvider()

actual class VoiceInputProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    actual suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Mobile-only mode: We don't need to connect to an external device.
        // We just ensure we are ready to record.
        true
    }

    actual fun disconnect() {
        stopAudioStream()
        scope.cancel()
    }

    actual fun startAudioStream(onUtteranceReady: AudioChunkCallback) {
        stopAudioStream() // ensure any previous stream is fully stopped first
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            .coerceAtLeast(4096)

        // Using MIC source for mobile-only recording.
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            android.util.Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        android.util.Log.d(TAG, "AudioRecord initialized: source=MIC sampleRate=$sampleRate bufferSize=$bufferSize")
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
            // minimum ~0.3 s of speech for mobile mic (lowered from 0.5s for better responsiveness)
            val minSpeechChunks = (0.3 * sampleRate * 2 / bufferSize).toInt().coerceAtLeast(1)

            android.util.Log.d(TAG, "Audio loop started. silenceThreshold=$silenceThreshold minSpeech=$minSpeechChunks")

            while (isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) { delay(10); continue }

                val frame = chunk.copyOf(read)
                val peak = frame.peakAmplitude()
                // Adjusted threshold for phone mic which might be further from mouth than glasses
                val isSilent = peak < 300 

                totalFrames++
                if (totalFrames % 100 == 0) {
                    android.util.Log.v(TAG, "Audio: frames=$totalFrames peak=$peak utteranceChunks=${utterance.size} silenceFrames=$silenceFrames")
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
                            android.util.Log.v(TAG, "Skipped short utterance: speechChunks=$speechChunks < min=$minSpeechChunks")
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
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordJob?.cancel()
        recordJob = null
        android.util.Log.d(TAG, "Audio stream stopped")
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
