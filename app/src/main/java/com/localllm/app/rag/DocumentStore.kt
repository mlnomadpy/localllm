package com.localllm.app.rag

import android.content.Context
import com.localllm.app.LogManager
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query

/**
 * Thin wrapper around ObjectBox holding the document-chunk box. One instance
 * per application process — the underlying [BoxStore] is process-wide.
 *
 * Not thread-safe by itself, but ObjectBox box operations are; we serialise
 * higher-level write workflows (chunk-then-embed-then-store) in
 * `LLMServerService` because the embedding step is the bottleneck and
 * holding the mutex across it keeps the per-document operation atomic.
 */
class DocumentStore(context: Context) {

    private val store: BoxStore = MyObjectBox.builder()
        .androidContext(context.applicationContext)
        .build()
        .also { LogManager.i("DocumentStore", "ObjectBox store opened") }

    private val box: Box<DocumentChunk> = store.boxFor()

    init {
        migrateLegacyTenantIds()
    }

    /**
     * Pre-multi-tenant chunks had `tenantId = ""`. Bucket them under
     * `"anonymous"` so curl / legacy clients still see their corpus.
     *
     * Cheap-path first: count() runs against the index without materializing
     * any rows. Only if the legacy bucket is non-empty do we pay to load the
     * chunks (which carry the embedding column).
     */
    private fun migrateLegacyTenantIds() {
        val legacyCount = box.query(DocumentChunk_.tenantId.equal(""))
            .build()
            .use { it.count() }
        if (legacyCount == 0L) return
        val legacy = box.query(DocumentChunk_.tenantId.equal(""))
            .build()
            .use { it.find() }
        legacy.forEach { it.tenantId = "anonymous" }
        box.put(legacy)
        LogManager.i(
            "DocumentStore",
            "Migrated ${legacy.size} legacy chunk(s) to tenant 'anonymous'",
        )
    }

    fun count(): Long = box.count()

    /** Persist a freshly-built set of chunks for one document. */
    fun put(chunks: List<DocumentChunk>) {
        if (chunks.isEmpty()) return
        box.put(chunks)
    }

    /** All chunks belonging to [documentId] within [tenantId], ordered by [DocumentChunk.chunkIndex]. */
    fun byDocument(tenantId: String, documentId: String): List<DocumentChunk> =
        box.query(
            DocumentChunk_.documentId.equal(documentId)
                .and(DocumentChunk_.tenantId.equal(tenantId)),
        )
            .order(DocumentChunk_.chunkIndex)
            .build()
            .use { it.find() }

    /**
     * Distinct document ids for [tenantId], paired with the count of
     * chunks for each. Backs `GET /v1/documents`.
     *
     * Projection-based — never reads the `embedding` column. Walks the
     * tenant query once to pull (documentId, embeddingModel) string pairs
     * via PropertyQuery, aggregates in-memory. Previously this loaded every
     * full [DocumentChunk] including its ~1.5 KB embedding vector per chunk,
     * which on a 10k-chunk corpus meant ~15 MB allocated per call.
     */
    fun listDocuments(tenantId: String): List<DocumentSummary> {
        val tenantQuery = box.query(DocumentChunk_.tenantId.equal(tenantId)).build()
        val docIds: Array<String> = tenantQuery.use { q ->
            q.property(DocumentChunk_.documentId).findStrings()
        }
        if (docIds.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>(docIds.size)
        for (id in docIds) counts[id] = (counts[id] ?: 0) + 1
        // Look up the embedding model for each distinct document with a
        // single-column projection filtered to that document. N small queries
        // (no row materialization, no embedding column read) — fine for a UI
        // list endpoint and avoids the ordering assumption of a parallel
        // projection over the whole tenant.
        return counts.entries
            .map { (id, n) ->
                val modelQ = box.query(
                    DocumentChunk_.tenantId.equal(tenantId)
                        .and(DocumentChunk_.documentId.equal(id))
                ).build()
                val model = modelQ.use { q ->
                    q.property(DocumentChunk_.embeddingModel).findStrings().firstOrNull()
                }.orEmpty()
                DocumentSummary(
                    documentId = id,
                    chunkCount = n,
                    embeddingModel = model,
                )
            }
            .sortedBy { it.documentId }
    }

    /** Remove every chunk for [documentId] owned by [tenantId]. Returns how many were removed. */
    fun deleteDocument(tenantId: String, documentId: String): Int {
        val ids = box.query(
            DocumentChunk_.documentId.equal(documentId)
                .and(DocumentChunk_.tenantId.equal(tenantId)),
        )
            .build()
            .use { it.findIds() }
        if (ids.isEmpty()) return 0
        box.remove(*ids)
        return ids.size
    }

    /**
     * Top-[k] nearest neighbours to [queryVec] (must be L2-normalised),
     * restricted to chunks embedded with [embeddingModel] and owned by
     * [tenantId]. Returns (chunk, distance) tuples where distance is the HNSW
     * DOT_PRODUCT distance — lower = closer for unit-norm vectors.
     *
     * ObjectBox HNSW does not compose cleanly with the tenant index, so we
     * over-fetch candidates then filter post-hoc to preserve recall.
     */
    fun nearest(
        tenantId: String,
        queryVec: FloatArray,
        k: Int,
        embeddingModel: String,
    ): List<Pair<DocumentChunk, Float>> {
        val fetchK = maxOf(k * 4, 16)
        val query = box.query(
            DocumentChunk_.embedding
                .nearestNeighbors(queryVec, fetchK)
                .and(DocumentChunk_.embeddingModel.equal(embeddingModel)),
        ).build()
        return query.use {
            it.findWithScores()
                .map { ws -> ws.get() to ws.score.toFloat() }
                .filter { (chunk, _) -> chunk.tenantId == tenantId }
                .take(k)
        }
    }

    /**
     * Global tenant inventory for `GET /v1/tenants`.
     *
     * Projection-based: pulls only tenantId / documentId string columns (no
     * embedding vector). Previously `box.all` materialised every full row
     * which on a busy install meant tens of MB allocated per call.
     */
    fun listTenants(): List<TenantSummary> {
        val allTenantsQ = box.query().build()
        val tenants: Array<String> = allTenantsQ.use {
            it.property(DocumentChunk_.tenantId).findStrings()
        }
        if (tenants.isEmpty()) return emptyList()
        val chunkCounts = LinkedHashMap<String, Int>()
        for (t in tenants) chunkCounts[t] = (chunkCounts[t] ?: 0) + 1
        return chunkCounts.entries
            .map { (t, n) ->
                val docQ = box.query(DocumentChunk_.tenantId.equal(t)).build()
                val docIds: Array<String> = docQ.use {
                    it.property(DocumentChunk_.documentId).findStrings()
                }
                TenantSummary(
                    tenantId = t,
                    documentCount = docIds.toHashSet().size,
                    chunkCount = n,
                )
            }
            .sortedBy { it.tenantId }
    }

    /** Remove every chunk for [tenantId]. Returns how many chunks were removed. */
    fun deleteTenant(tenantId: String): Int {
        val ids = box.query(DocumentChunk_.tenantId.equal(tenantId))
            .build()
            .use { it.findIds() }
        if (ids.isEmpty()) return 0
        box.remove(*ids)
        return ids.size
    }

    fun close() {
        try { store.close() } catch (e: Exception) {
            LogManager.w("DocumentStore", "Error closing ObjectBox store: ${e.message}")
        }
    }

    data class DocumentSummary(
        val documentId: String,
        val chunkCount: Int,
        val embeddingModel: String,
    )

    data class TenantSummary(
        val tenantId: String,
        val documentCount: Int,
        val chunkCount: Int,
    )
}
