package com.example.wearableai

import android.util.Log
import com.example.wearableai.shared.SessionSnapshot
import com.example.wearableai.shared.SessionSummary
import com.example.wearableai.shared.generateNoteId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * On-disk inspection persistence. One directory per session under [rootDir]:
 *   <rootDir>/<sessionId>/session.json
 *   <rootDir>/<sessionId>/floorplan.img   (optional)
 *   <rootDir>/<sessionId>/photos/...      (written in-place by CameraCapture)
 *
 * [saveScope] is the ViewModel scope — not the session scope — so a pending
 * save survives session teardown (e.g. switching to a different inspection).
 */
class InspectionStore(
    private val rootDir: File,
    private val saveScope: CoroutineScope,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val idFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val nameFmt = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)

    // One pending save job at a time — new calls cancel + replace (debounce).
    private val saveMutex = Mutex()
    private var pendingJob: Job? = null

    init {
        rootDir.mkdirs()
    }

    fun sessionDir(id: String): File = File(rootDir, id)
    fun photosDir(id: String): File = File(sessionDir(id), "photos").apply { mkdirs() }
    private fun snapshotFile(id: String): File = File(sessionDir(id), "session.json")

    fun listSummaries(): List<SessionSummary> {
        val dirs = rootDir.listFiles { f -> f.isDirectory }?.toList() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val f = File(dir, "session.json")
            if (!f.exists()) return@mapNotNull null
            try {
                val snap = json.decodeFromString(SessionSnapshot.serializer(), f.readText())
                SessionSummary(
                    id = snap.id,
                    name = snap.name,
                    updatedAtMs = snap.updatedAtMs,
                    noteCount = snap.notes.size,
                    hasFloorPlan = snap.floorPlanRelPath != null,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "listSummaries: skipping unreadable ${dir.name}: ${t.message}")
                null
            }
        }.sortedByDescending { it.updatedAtMs }
    }

    suspend fun create(): SessionSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = "${idFmt.format(Date(now))}_${randomSuffix()}"
        val name = "Inspection ${nameFmt.format(Date(now))}"
        val snap = SessionSnapshot(
            id = id, name = name,
            createdAtMs = now, updatedAtMs = now,
            floorPlanRelPath = null,
            notes = emptyList(), pins = emptyList(), history = emptyList(),
        )
        sessionDir(id).mkdirs()
        photosDir(id)
        writeSnapshot(snap)
        snap
    }

    suspend fun load(id: String): SessionSnapshot = withContext(Dispatchers.IO) {
        val text = snapshotFile(id).readText()
        val raw = json.decodeFromString(SessionSnapshot.serializer(), text)
        // Back-compat: pre-id snapshots have notes with id="". Patch in stable ids on load.
        val needsMigration = raw.notes.any { it.id.isBlank() }
        if (!needsMigration) raw
        else raw.copy(notes = raw.notes.map { n ->
            if (n.id.isNotBlank()) n else n.copy(id = generateNoteId(n.timestampMs))
        })
    }

    /** Debounced save — coalesces bursts of mutations (e.g. a single turn with
     *  add_note + drop_pin + history append) into one write. */
    fun scheduleSave(snapshot: SessionSnapshot) {
        saveScope.launch {
            saveMutex.withLock {
                pendingJob?.cancel()
                pendingJob = saveScope.launch {
                    delay(500)
                    runCatching { writeSnapshot(snapshot) }
                        .onFailure { Log.e(TAG, "scheduleSave write failed", it) }
                }
            }
        }
    }

    /** Await any in-flight debounce. Call from onCleared / session switch. */
    suspend fun flushPendingSave() {
        saveMutex.withLock { pendingJob }?.join()
    }

    fun delete(id: String) {
        sessionDir(id).deleteRecursively()
    }

    suspend fun rename(id: String, newName: String) = withContext(Dispatchers.IO) {
        val snap = load(id)
        writeSnapshot(snap.copy(name = newName.trim(), updatedAtMs = System.currentTimeMillis()))
    }

    /** Copies the picked floor-plan image into the session dir and returns the
     *  archived file. The session snapshot stores the *relative* path. */
    fun archiveFloorPlan(id: String, src: File): File {
        val dst = File(sessionDir(id), "floorplan.img")
        src.copyTo(dst, overwrite = true)
        return dst
    }

    private fun writeSnapshot(snap: SessionSnapshot) {
        val dir = sessionDir(snap.id).apply { mkdirs() }
        val tmp = File(dir, "session.json.tmp")
        val final = File(dir, "session.json")
        tmp.writeText(json.encodeToString(SessionSnapshot.serializer(), snap))
        if (!tmp.renameTo(final)) {
            // Rename can fail across filesystems — fall back to copy + delete.
            tmp.copyTo(final, overwrite = true)
            tmp.delete()
        }
    }

    private fun randomSuffix(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until 4).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private companion object {
        const val TAG = "InspectionStore"
    }
}
