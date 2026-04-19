package com.example.wearableai.shared

import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** NFPA 1620-leaning category tags. Freeform markdown still wins — this is just for grouping. */
@Serializable
enum class NoteCategory(val key: String, val heading: String) {
    CONSTRUCTION("construction", "Construction"),
    ACCESS("access", "Access & Egress"),
    HAZARDS("hazards", "Hazards"),
    PROTECTION("protection", "Protection Systems"),
    UTILITIES("utilities", "Utilities"),
    WATER("water", "Water Supply"),
    CONTACTS("contacts", "Contacts"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String): NoteCategory =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER
    }
}

@Serializable
data class Note(
    // Stable per-note ID. Empty string default allows back-compat decoding of pre-id snapshots —
    // InspectionStore.load fills any blanks. Newly created notes always get a real id from NoteTaker.add.
    val id: String = "",
    val category: NoteCategory,
    val markdown: String,
    val timestampMs: Long,
    val photoPath: String? = null,
)

/**
 * Append-only markdown note log grouped by category. Thread-safe writes, StateFlow reads.
 * The "summary" operation just renders the whole thing to a single markdown string.
 */
class NoteTaker {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun add(category: NoteCategory, markdown: String, photoPath: String? = null, nowMs: Long) {
        val note = Note(
            id = generateNoteId(nowMs),
            category = category,
            markdown = markdown.trim(),
            timestampMs = nowMs,
            photoPath = photoPath,
        )
        _notes.value = _notes.value + note
    }

    fun clear() {
        _notes.value = emptyList()
    }

    fun replaceAll(list: List<Note>) {
        _notes.value = list
    }

    /** Patch the photoPath of an existing note. Used by deferred-photo reconciliation
     *  when the cloud backend ties a queued photo back to a previously-recorded note. */
    fun attachPhoto(noteId: String, photoPath: String): Boolean {
        val current = _notes.value
        val idx = current.indexOfFirst { it.id == noteId }
        if (idx < 0) return false
        _notes.value = current.toMutableList().apply { set(idx, current[idx].copy(photoPath = photoPath)) }
        return true
    }

    /** Grouped markdown rendering. One H2 per non-empty category, bullets beneath. */
    fun render(): String {
        val byCat = _notes.value.groupBy { it.category }
        if (byCat.isEmpty()) return "_No observations yet._"
        val sb = StringBuilder()
        for (cat in NoteCategory.entries) {
            val list = byCat[cat] ?: continue
            sb.append("## ").append(cat.heading).append("\n\n")
            for (n in list) {
                sb.append("- ").append(n.markdown)
                if (n.photoPath != null) sb.append(" _(photo: ${n.photoPath.substringAfterLast('/')})_")
                sb.append("\n")
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}

/** Stable per-note ID. Random suffix collision-free in practice for single-user usage. */
fun generateNoteId(nowMs: Long): String =
    "$nowMs-${Random.nextLong().toString(16).removePrefix("-")}"
