package com.example.wearableai.shared

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream

private const val TAG = "CameraCapture"

actual class CameraCapture actual constructor() {

    private var session: StreamSession? = null

    actual suspend fun open(): Boolean {
        if (session != null) return true
        return try {
            val ctx = appContext
            val result = Wearables.startStreamSession(
                context = ctx,
                deviceSelector = AutoDeviceSelector(),
                streamConfiguration = StreamConfiguration(),
            )
            session = result
            android.util.Log.d(TAG, "startStreamSession ok, state=${result.state.value}")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "open() failed: ${t::class.simpleName}: ${t.message}", t)
            false
        }
    }

    actual suspend fun capture(timeoutMs: Long): String? {
        // Auto-reopen: the DAT stream session can silently die (BLE reconnect,
        // device sleep, Meta AI app lifecycle). Don't leave the user stuck —
        // on any miss we null the session so the next call re-establishes it.
        if (session == null) {
            android.util.Log.d(TAG, "capture: session missing, reopening")
            if (!open()) return null
        }
        val s = session ?: return null
        return try {
            val datResult = withTimeout(timeoutMs) { s.capturePhoto() }
            val photo = datResult.getOrNull()
            if (photo == null) {
                android.util.Log.w(TAG, "capturePhoto returned no data — invalidating session")
                recycleSession()
                return null
            }
            writePhoto(photo)
        } catch (_: TimeoutCancellationException) {
            android.util.Log.w(TAG, "capturePhoto timed out after ${timeoutMs}ms — invalidating session")
            recycleSession()
            null
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "capturePhoto failed: ${t::class.simpleName}: ${t.message} — invalidating session", t)
            recycleSession()
            null
        }
    }

    private fun recycleSession() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    actual fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    private fun writePhoto(photo: PhotoData): String {
        val ts = System.currentTimeMillis()
        return when (photo) {
            is PhotoData.Bitmap -> {
                val file = File(appContext.cacheDir, "photo_$ts.jpg")
                FileOutputStream(file).use { fos ->
                    photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }
                file.absolutePath
            }
            is PhotoData.HEIC -> {
                val file = File(appContext.cacheDir, "photo_$ts.heic")
                val bytes = ByteArray(photo.data.remaining())
                photo.data.duplicate().get(bytes)
                FileOutputStream(file).use { it.write(bytes) }
                file.absolutePath
            }
        }
    }
}
