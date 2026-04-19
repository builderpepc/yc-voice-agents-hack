package com.example.wearableai

import android.app.Application
import com.cactus.CactusLogCallback
import com.cactus.cactusLogSetCallback
import com.cactus.cactusLogSetLevel
import com.cactus.cactusSetAppId
import com.cactus.cactusSetGemmThreads
import com.cactus.cactusSetTelemetryEnvironment
import com.example.wearableai.shared.appContext
import com.meta.wearable.dat.core.Wearables

class WearableAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        Wearables.initialize(this)
        try {
            cactusSetTelemetryEnvironment(cacheDir.absolutePath)
            cactusSetAppId("com.example.wearableai")
            cactusSetGemmThreads(1) // force single-threaded GEMM to avoid thread-pool crash in audio path
            cactusLogSetLevel(0) // DEBUG — diagnose why audio input is being ignored
            cactusLogSetCallback(CactusLogCallback { level, component, message ->
                android.util.Log.d("Cactus", "[$level/$component] $message")
            })
        } catch (e: Throwable) {
            android.util.Log.w("WearableAIApp", "libcactus.so not found — on-device inference unavailable: ${e.message}")
        }
    }
}
