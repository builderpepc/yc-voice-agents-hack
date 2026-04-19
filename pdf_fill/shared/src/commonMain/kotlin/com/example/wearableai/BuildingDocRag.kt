package com.example.wearableai.shared

import com.cactus.cactusEmbed
import com.cactus.cactusIndexAdd
import com.cactus.cactusIndexDestroy
import com.cactus.cactusIndexInit
import com.cactus.cactusIndexQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin wrapper over Cactus's on-device vector index. Takes raw text documents,
 * chunks them, embeds each chunk via the loaded Cactus model, and writes them to
 * a persistent index directory. Supports top-k similarity queries. All offline.
 *
 * Embeddings come from the same model handle used for inference (Gemma 4 E2B
 * here via Cactus). Embedding dim is 768 for the gemma-4-E2B-it build.
 */
class BuildingDocRag(
    private val indexDir: String,
    private val embeddingDim: Int = 768,
) {
    private var indexHandle: Long = 0L
    private var nextId: Int = 0

    /** Open or create the persistent index at [indexDir]. Must be called once per session. */
    suspend fun open() = withContext(Dispatchers.Default) {
        if (indexHandle == 0L) {
            try {
                indexHandle = cactusIndexInit(indexDir, embeddingDim)
            } catch (e: Throwable) {
                println("[BuildingDocRag] Index init failed (cloud-only mode): ${e.message}")
                indexHandle = 0L
            }
        }
    }

    fun close() {
        if (indexHandle != 0L) {
            try { cactusIndexDestroy(indexHandle) } catch (_: Throwable) {}
            indexHandle = 0L
        }
    }

    /**
     * Chunk [text] to ~500-char windows (sentence-aware), embed each chunk with the
     * supplied [modelHandle], and add them to the index under [sourceName] metadata.
     * Returns the number of chunks indexed.
     */
    suspend fun addDocument(
        modelHandle: Long,
        sourceName: String,
        text: String,
    ): Int = withContext(Dispatchers.Default) {
        require(indexHandle != 0L) { "BuildingDocRag.open() must be called first" }
        val chunks = chunk(text, targetLen = 500, overlap = 80)
        if (chunks.isEmpty()) return@withContext 0
        val ids = IntArray(chunks.size) { nextId + it }
        nextId += chunks.size
        val embeddings: Array<FloatArray> = Array(chunks.size) { i ->
            cactusEmbed(modelHandle, chunks[i], /* normalize = */ true)
        }
        val metas: Array<String> = Array(chunks.size) { i ->
            """{"source":"${escape(sourceName)}","chunk":$i}"""
        }
        cactusIndexAdd(indexHandle, ids, chunks.toTypedArray(), embeddings, metas)
        chunks.size
    }

    /** Returns top-K matching passages concatenated with their source tag. */
    suspend fun query(modelHandle: Long, question: String, topK: Int = 4): String = withContext(Dispatchers.Default) {
        if (indexHandle == 0L) return@withContext ""
        val embedding = cactusEmbed(modelHandle, question, /* normalize = */ true)
        val resultJson = cactusIndexQuery(
            indexHandle,
            embedding,
            """{"top_k":$topK}""",
        )
        parsePassages(resultJson)
    }

    private fun chunk(text: String, targetLen: Int, overlap: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= targetLen) return listOf(clean)
        val out = mutableListOf<String>()
        var i = 0
        while (i < clean.length) {
            val end = (i + targetLen).coerceAtMost(clean.length)
            // Prefer to break on sentence end if within the last 80 chars of the window.
            val breakAt = clean.lastIndexOf('.', end).let { if (it >= i + targetLen - 80) it + 1 else end }
            val stop = breakAt.coerceAtMost(clean.length).coerceAtLeast(i + 1)
            out.add(clean.substring(i, stop).trim())
            if (stop >= clean.length) break
            i = (stop - overlap).coerceAtLeast(stop - targetLen / 2)
        }
        return out.filter { it.isNotBlank() }
    }

    // cactusIndexQuery returns JSON like:
    //   { "results": [{"id":..., "document":"...", "score":..., "metadata":"..."}, ...] }
    private fun parsePassages(resultJson: String): String = try {
        val obj = Json.parseToJsonElement(resultJson).jsonObject
        val arr = obj["results"] as? JsonArray ?: obj["hits"] as? JsonArray ?: return ""
        val sb = StringBuilder()
        arr.forEachIndexed { i, item ->
            val o = item.jsonObject
            val doc = o["document"]?.jsonPrimitive?.contentOrNull ?: o["text"]?.jsonPrimitive?.contentOrNull
            val source = try {
                val metaRaw = o["metadata"]?.jsonPrimitive?.contentOrNull ?: ""
                Json.parseToJsonElement(metaRaw).jsonObject["source"]?.jsonPrimitive?.contentOrNull
            } catch (_: Throwable) { null }
            if (!doc.isNullOrBlank()) {
                if (i > 0) sb.append("\n\n")
                sb.append("[").append(source ?: "doc").append("] ").append(doc)
            }
        }
        sb.toString()
    } catch (_: Throwable) { "" }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
