package com.example.wearableai.shared

actual class CameraCapture actual constructor() {
    actual suspend fun open(): Boolean = false
    actual suspend fun capture(timeoutMs: Long): String? = null
    actual fun close() {}
}
