package com.localllm.app

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [RequestTracker.ChunkAccumulator] is the per-stream batcher introduced to
 * keep the SSE hot path off the per-token allocation curve. These tests pin
 * its contract: add() never under-counts, flushes happen on the configured
 * chunk threshold, and a final flush() drains any partial batch.
 */
class ChunkAccumulatorTest {

    @Before
    fun setUp() = runTest { reset() }

    @After
    fun tearDown() = runTest { reset() }

    private suspend fun reset() {
        RequestTracker.resetAll()
        RequestTracker.clearHistory()
        RequestTracker.resetStats()
    }

    @Test
    fun `accumulator does not push until chunk threshold reached`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        val acc = RequestTracker.accumulatorFor(entry.id, flushEveryChunks = 4, flushEveryMs = 60_000L)

        acc.add(3); acc.add(3); acc.add(3)
        // Still buffered; current entry's chunkCount should be untouched.
        assertEquals(0, RequestTracker.current.value!!.chunkCount)
        assertEquals(0, RequestTracker.current.value!!.outputChars)
    }

    @Test
    fun `chunk-count flush emits a single Entry copy with batched totals`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        val acc = RequestTracker.accumulatorFor(entry.id, flushEveryChunks = 4, flushEveryMs = 60_000L)

        // Three sub-threshold adds — no flush.
        acc.add(2); acc.add(2); acc.add(2)
        // Fourth crosses the threshold → flush.
        acc.add(2)
        val cur = RequestTracker.current.value!!
        assertEquals(4, cur.chunkCount)
        assertEquals(8, cur.outputChars)
    }

    @Test
    fun `final flush drains remaining partial batch`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        val acc = RequestTracker.accumulatorFor(entry.id, flushEveryChunks = 100, flushEveryMs = 60_000L)

        acc.add(5); acc.add(7)
        assertEquals(0, RequestTracker.current.value!!.chunkCount)
        acc.flush()
        val cur = RequestTracker.current.value!!
        assertEquals(2, cur.chunkCount)
        assertEquals(12, cur.outputChars)
    }

    @Test
    fun `repeated flushes are idempotent after drain`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        val acc = RequestTracker.accumulatorFor(entry.id, flushEveryChunks = 100, flushEveryMs = 60_000L)
        acc.add(1); acc.flush()
        val firstSnapshot = RequestTracker.current.value!!
        acc.flush(); acc.flush()
        assertEquals(firstSnapshot.chunkCount, RequestTracker.current.value!!.chunkCount)
        assertEquals(firstSnapshot.outputChars, RequestTracker.current.value!!.outputChars)
    }

    @Test
    fun `recordChunkBatch with zero work is a no-op`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.recordChunkBatch(entry.id, 0, 0)
        assertEquals(0, RequestTracker.current.value!!.chunkCount)
    }

    @Test
    fun `recordChunkBatch ignored when id doesn't match current`() = runTest {
        val entry = RequestTracker.enqueue("m", true, 1, 100)
        RequestTracker.markStarted(entry.id)
        RequestTracker.recordChunkBatch("not-the-id", 99, 9999)
        assertEquals(0, RequestTracker.current.value!!.chunkCount)
        assertEquals(0, RequestTracker.current.value!!.outputChars)
    }
}
