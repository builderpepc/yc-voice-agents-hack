package com.example.wearableai.shared

import kotlinx.serialization.Serializable

/**
 * A photo captured during force-local operation. Local FunctionGemma can't analyze images,
 * so the photo is queued with the utterance transcript that triggered the capture; when the
 * cloud backend is reachable again, Gemini reconciles each entry to the right note (or none).
 */
@Serializable
data class DeferredPhoto(
    val photoPath: String,
    val capturedAtMs: Long,
    val transcript: String,
)
