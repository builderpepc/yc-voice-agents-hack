package com.example.wearableai.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Pin(
    val id: String,
    val x: Float, // normalized 0..1 (left→right)
    val y: Float, // normalized 0..1 (top→bottom)
    val label: String,
    val severity: PinSeverity,
    val noteRef: String? = null, // optional free-text summary shown on tap
)

enum class PinSeverity(val key: String) {
    INFO("info"), WARN("warn"), HAZARD("hazard");

    companion object {
        fun fromKey(key: String): PinSeverity =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: INFO
    }
}

/**
 * Holds per-building transient state: the floor-plan image URI (if loaded),
 * and the pin set the agent has dropped. Bitmap decoding is done platform-side.
 */
class BuildingContext {
    private val _floorPlanPath = MutableStateFlow<String?>(null)
    val floorPlanPath: StateFlow<String?> = _floorPlanPath.asStateFlow()

    private val _pins = MutableStateFlow<List<Pin>>(emptyList())
    val pins: StateFlow<List<Pin>> = _pins.asStateFlow()

    private val _docsIndexedChunks = MutableStateFlow(0)
    val docsIndexedChunks: StateFlow<Int> = _docsIndexedChunks.asStateFlow()

    fun setFloorPlan(path: String?) { _floorPlanPath.value = path }

    fun addPin(x: Float, y: Float, label: String, severity: PinSeverity, noteRef: String? = null) {
        val id = "pin_${_pins.value.size}_${label.hashCode()}"
        _pins.value = _pins.value + Pin(id, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), label, severity, noteRef)
    }

    fun removePin(id: String) {
        _pins.value = _pins.value.filterNot { it.id == id }
    }

    fun setDocsIndexed(count: Int) { _docsIndexedChunks.value = count }

    fun clear() {
        _floorPlanPath.value = null
        _pins.value = emptyList()
        _docsIndexedChunks.value = 0
    }
}
