package com.localllm.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide tracker for LLM requests. Exposes StateFlows the UI can observe.
 *
 * Lifecycle:
 *   enqueue() → (waiting in queue) → markStarted() → recordChunk()* → markCompleted()
 *
 * Concurrency model:
 *   - Compound mutations (move from queue → current, current → history) are serialized
 *     by [mutex] so the four StateFlows stay consistent with each other.
 *   - Per-chunk updates use `StateFlow.update` (lock-free CAS) — they're on the hot
 *     streaming path, so we don't want to contend with the slower compound writes.
 *
 * Memory:
 *   - History is capped at [HISTORY_CAP]; oldest entries are dropped.
 *   - Stats are cumulative and survive service restarts (in-memory only).
 */
object RequestTracker {

    enum class State { QUEUED, RUNNING, COMPLETED, ERRORED, CANCELLED }

    data class Entry(
        val id: String,
        val model: String,
        val stream: Boolean,
        val messageCount: Int,
        val promptChars: Int,
        val state: State,
        val enqueuedAt: Long,
        val startedAt: Long? = null,
        val completedAt: Long? = null,
        val chunkCount: Int = 0,
        val outputChars: Int = 0,
        val error: String? = null,
        /**
         * Client identity for fairness, rate limiting, and per-app metrics.
         * Derived from the request's `User-Agent` header at enqueue time;
         * unknown / no-UA requests fall back to `"anonymous"`. Multiple
         * sibling apps on the same phone each get their own bucket as long
         * as their UAs differ — which is the normal pattern (most HTTP
         * libraries set a vendor UA by default).
         */
        val client: String = "anonymous",
    ) {
        /** Time spent in the queue waiting for the inference mutex. */
        fun queueWaitMs(now: Long = System.currentTimeMillis()): Long =
            (startedAt ?: now) - enqueuedAt

        /** Time spent in inference (live for RUNNING, frozen once completed). */
        fun inferenceMs(now: Long = System.currentTimeMillis()): Long {
            val start = startedAt ?: return 0L
            val end = completedAt ?: now
            return (end - start).coerceAtLeast(0L)
        }

        /** Chunks per second, computed only when we have a completed duration. */
        val chunksPerSec: Float
            get() {
                val ms = inferenceMs()
                return if (ms > 0 && chunkCount > 0) chunkCount.toFloat() * 1000f / ms else 0f
            }
    }

    data class Stats(
        val totalRequests: Long = 0,
        val totalCompleted: Long = 0,
        val totalErrors: Long = 0,
        val totalCancelled: Long = 0,
        val totalChunks: Long = 0,
        val totalInferenceMs: Long = 0
    ) {
        val avgLatencyMs: Long
            get() = if (totalCompleted > 0) totalInferenceMs / totalCompleted else 0L

        val avgChunksPerSec: Float
            get() = if (totalInferenceMs > 0) totalChunks.toFloat() * 1000f / totalInferenceMs else 0f

        val errorRate: Float
            get() = if (totalRequests > 0) (totalErrors + totalCancelled).toFloat() / totalRequests else 0f
    }

    private const val HISTORY_CAP = 50

    private val mutex = Mutex()
    private val idCounter = AtomicLong(0)

    private val _queue = MutableStateFlow<List<Entry>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _current = MutableStateFlow<Entry?>(null)
    val current = _current.asStateFlow()

    private val _history = MutableStateFlow<List<Entry>>(emptyList())
    val history = _history.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats = _stats.asStateFlow()

    /** Append a new request to the queue and bump totalRequests. */
    suspend fun enqueue(model: String, stream: Boolean, messageCount: Int, promptChars: Int): Entry {
        val entry = Entry(
            id = idCounter.incrementAndGet().toString(),
            model = model,
            stream = stream,
            messageCount = messageCount,
            promptChars = promptChars,
            state = State.QUEUED,
            enqueuedAt = System.currentTimeMillis()
        )
        mutex.withLock {
            _queue.update { it + entry }
            _stats.update { it.copy(totalRequests = it.totalRequests + 1) }
        }
        return entry
    }

    /**
     * Atomically check queue capacity and enqueue. Returns null if the queue
     * is full ([maxDepth] reached) — the caller should respond 429.
     */
    suspend fun tryEnqueue(
        model: String,
        stream: Boolean,
        messageCount: Int,
        promptChars: Int,
        maxDepth: Int,
        client: String = "anonymous",
    ): Entry? = mutex.withLock {
        if (_queue.value.size >= maxDepth) return@withLock null
        val entry = Entry(
            id = idCounter.incrementAndGet().toString(),
            model = model,
            stream = stream,
            messageCount = messageCount,
            promptChars = promptChars,
            state = State.QUEUED,
            enqueuedAt = System.currentTimeMillis(),
            client = client,
        )
        _queue.update { it + entry }
        _stats.update { it.copy(totalRequests = it.totalRequests + 1) }
        entry
    }

    /**
     * Snapshot of per-client activity for the Dashboard "Top clients" panel.
     * Aggregated lazily from [history] + [current] + [queue] — kept in sync
     * implicitly without a separate state flow.
     */
    data class ClientSummary(
        val client: String,
        val inFlight: Int,
        val queued: Int,
        val completed: Long,
        val errored: Long,
        val totalChunks: Long,
        val avgInferenceMs: Long,
    )

    /** Last [maxClients] clients seen, sorted by recency × volume. */
    fun clientSummaries(maxClients: Int = 8): List<ClientSummary> {
        val hist = _history.value
        val cur = _current.value
        val q = _queue.value
        val all: List<Entry> = buildList {
            addAll(hist)
            cur?.let { add(it) }
            addAll(q)
        }
        return all.groupBy { it.client }
            .map { (client, entries) ->
                val running = entries.count { it.state == State.RUNNING }
                val queued = entries.count { it.state == State.QUEUED }
                val completed = entries.count { it.state == State.COMPLETED }.toLong()
                val errored = entries.count {
                    it.state == State.ERRORED || it.state == State.CANCELLED
                }.toLong()
                val chunks = entries.sumOf { it.chunkCount.toLong() }
                val infMs = entries.filter { it.state == State.COMPLETED }
                    .sumOf { it.inferenceMs() }
                ClientSummary(
                    client = client,
                    inFlight = running,
                    queued = queued,
                    completed = completed,
                    errored = errored,
                    totalChunks = chunks,
                    avgInferenceMs = if (completed > 0) infMs / completed else 0L,
                )
            }
            .sortedByDescending { it.completed + it.queued.toLong() + it.inFlight.toLong() }
            .take(maxClients)
    }

    /**
     * Promote [id] from queue to current. If it's no longer in the queue
     * (already completed or cancelled before reaching the inference mutex)
     * this is a no-op.
     */
    suspend fun markStarted(id: String) {
        mutex.withLock {
            val q = _queue.value
            val idx = q.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock
            val entry = q[idx].copy(state = State.RUNNING, startedAt = System.currentTimeMillis())
            _queue.update { it.toMutableList().also { l -> l.removeAt(idx) } }
            _current.update { entry }
        }
    }

    /**
     * Hot-path: called once per streamed chunk. Lock-free; only updates [_current].
     * If [id] doesn't match the current request (shouldn't happen since inference
     * is serialized), it's silently dropped.
     *
     * For streaming responses prefer [accumulatorFor], which batches multiple
     * chunks into one [Entry.copy] to keep allocation off the per-token path.
     * This non-batching variant is retained for the non-streaming code paths
     * and tests that record a single final chunk.
     */
    fun recordChunk(id: String, chunkText: String) {
        recordChunkBatch(id, chunks = 1, chars = chunkText.length)
    }

    /**
     * Bulk version of [recordChunk]. Adds [chunks] events totalling [chars]
     * output characters to the current request. One [Entry.copy] regardless
     * of batch size.
     */
    fun recordChunkBatch(id: String, chunks: Int, chars: Int) {
        if (chunks <= 0 && chars <= 0) return
        _current.update { cur ->
            if (cur != null && cur.id == id) {
                cur.copy(
                    chunkCount = cur.chunkCount + chunks,
                    outputChars = cur.outputChars + chars,
                )
            } else cur
        }
    }

    /**
     * Build a batching accumulator scoped to one streaming request.
     *
     * The route calls [ChunkAccumulator.add] once per token (cheap — atomic
     * counters only) and [ChunkAccumulator.flush] when it's time to push the
     * UI a fresh snapshot. The accumulator self-flushes when its buffered
     * chunk count crosses [flushEveryChunks] *or* its accumulated wall time
     * crosses [flushEveryMs] — whichever comes first. The final [flush]
     * (typically in a `finally` block) guarantees the last partial batch
     * lands on the flow.
     */
    fun accumulatorFor(
        id: String,
        flushEveryChunks: Int = 16,
        flushEveryMs: Long = 100L,
    ): ChunkAccumulator = ChunkAccumulator(id, flushEveryChunks, flushEveryMs)

    class ChunkAccumulator internal constructor(
        private val id: String,
        private val flushEveryChunks: Int,
        private val flushEveryMs: Long,
    ) {
        private var pendingChunks: Int = 0
        private var pendingChars: Int = 0
        private var lastFlushNanos: Long = System.nanoTime()

        fun add(deltaChars: Int) {
            pendingChunks += 1
            pendingChars += deltaChars
            if (pendingChunks >= flushEveryChunks) { flush(); return }
            val elapsedMs = (System.nanoTime() - lastFlushNanos) / 1_000_000L
            if (elapsedMs >= flushEveryMs) flush()
        }

        fun flush() {
            if (pendingChunks == 0 && pendingChars == 0) return
            recordChunkBatch(id, pendingChunks, pendingChars)
            pendingChunks = 0
            pendingChars = 0
            lastFlushNanos = System.nanoTime()
        }
    }

    /**
     * Finalize the request: move it from current to history, update stats.
     * Pass [error] for failures, [cancelled]=true for client/server cancellations.
     */
    suspend fun markCompleted(id: String, error: String? = null, cancelled: Boolean = false) {
        mutex.withLock {
            // If the request was queued and never started (rare race), remove it from the queue.
            val queued = _queue.value
            val qIdx = queued.indexOfFirst { it.id == id }
            if (qIdx >= 0) {
                val finalState = when {
                    cancelled -> State.CANCELLED
                    error != null -> State.ERRORED
                    else -> State.COMPLETED
                }
                val finalized = queued[qIdx].copy(
                    state = finalState,
                    completedAt = System.currentTimeMillis(),
                    error = error
                )
                _queue.update { it.toMutableList().also { l -> l.removeAt(qIdx) } }
                pushHistory(finalized)
                bumpStats(finalized)
                return@withLock
            }

            val cur = _current.value
            if (cur == null || cur.id != id) return@withLock

            val finalState = when {
                cancelled -> State.CANCELLED
                error != null -> State.ERRORED
                else -> State.COMPLETED
            }
            val finalized = cur.copy(
                state = finalState,
                completedAt = System.currentTimeMillis(),
                error = error
            )
            _current.update { null }
            pushHistory(finalized)
            bumpStats(finalized)
        }
    }

    /** Caller must hold [mutex]. */
    private fun pushHistory(entry: Entry) {
        _history.update { existing ->
            val next = ArrayList<Entry>(minOf(existing.size + 1, HISTORY_CAP))
            next.add(entry)
            for (i in 0 until minOf(existing.size, HISTORY_CAP - 1)) next.add(existing[i])
            next
        }
    }

    /** Caller must hold [mutex]. */
    private fun bumpStats(entry: Entry) {
        val infMs = entry.inferenceMs()
        _stats.update { s ->
            s.copy(
                totalCompleted = s.totalCompleted + if (entry.state == State.COMPLETED) 1 else 0,
                totalErrors = s.totalErrors + if (entry.state == State.ERRORED) 1 else 0,
                totalCancelled = s.totalCancelled + if (entry.state == State.CANCELLED) 1 else 0,
                totalChunks = s.totalChunks + entry.chunkCount,
                totalInferenceMs = s.totalInferenceMs + if (entry.state == State.COMPLETED) infMs else 0L
            )
        }
    }

    /** Wipe history; stats and any in-flight work are untouched. */
    suspend fun clearHistory() {
        mutex.withLock { _history.update { emptyList() } }
    }

    /** Reset cumulative counters. Useful after a config change. */
    suspend fun resetStats() {
        mutex.withLock { _stats.update { Stats() } }
    }

    /** Called when the service is torn down — any pending work is moot. */
    suspend fun resetAll() {
        mutex.withLock {
            _queue.update { emptyList() }
            _current.update { null }
        }
    }
}
