package com.example.wearableai.shared

import kotlinx.serialization.Serializable

/**
 * On-disk representation of one inspection session. Written incrementally as
 * the agent makes tool calls, so an app crash loses at most the in-flight utterance.
 */
@Serializable
data class SessionSnapshot(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val floorPlanRelPath: String?,
    val notes: List<Note>,
    val pins: List<Pin>,
    val history: List<Map<String, String>>,
    val deferredPhotos: List<DeferredPhoto> = emptyList(),
)

/** Lightweight row for the sessions list UI — avoids parsing the full snapshot. */
@Serializable
data class SessionSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val noteCount: Int,
    val hasFloorPlan: Boolean,
)
