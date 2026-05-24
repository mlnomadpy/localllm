package com.localllm.app.inference.aicore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `runBenchmark` and `runOne` require a live AICore session; only the
 * whitespace-split `approxTokenCount` heuristic is testable on the JVM.
 * It backs the `/v1/aicore/benchmark` `tokensPerSec` metric, so its
 * behavior on edge cases (empty, all-whitespace, repeated whitespace) is
 * part of the wire contract clients see.
 */
class AICoreBenchmarkTokenCountTest {

    @Test
    fun `empty input is zero tokens`() {
        assertEquals(0, AICoreBenchmark.approxTokenCount(""))
    }

    @Test
    fun `whitespace-only input is zero tokens`() {
        assertEquals(0, AICoreBenchmark.approxTokenCount("   \n\t  "))
    }

    @Test
    fun `single token`() {
        assertEquals(1, AICoreBenchmark.approxTokenCount("hello"))
    }

    @Test
    fun `collapses repeated whitespace between tokens`() {
        assertEquals(3, AICoreBenchmark.approxTokenCount("a   b\t\tc"))
    }

    @Test
    fun `trims leading and trailing whitespace before counting`() {
        assertEquals(2, AICoreBenchmark.approxTokenCount("  hello world  "))
    }

    @Test
    fun `newlines count as whitespace separators`() {
        assertEquals(3, AICoreBenchmark.approxTokenCount("alpha\nbeta\rgamma"))
    }
}
