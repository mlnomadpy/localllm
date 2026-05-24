package com.localllm.app

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogManager] is a process-wide hot SharedFlow with a 100-entry replay
 * buffer. Across test runs in the same JVM that replay buffer accumulates,
 * so per-test subscriptions see prior tests' emissions first.
 *
 * We work around that by tagging each emission with a unique marker built
 * from a monotonic counter and filtering for it via `Flow.first { … }`.
 * That keeps each test independent without needing a reset hook on the
 * SharedFlow (there isn't one — the contract is "long-lived process-wide
 * log bus").
 */
class LogManagerTest {

    companion object {
        private val markerCounter = AtomicLong(0)
    }

    private fun nextMarker(): String = "logmgr-test-${markerCounter.incrementAndGet()}"

    private suspend fun firstWithMarker(marker: String): LogEntry =
        withTimeout(2_000L) {
            LogManager.logs.first { it.message.contains(marker) }
        }

    @Test
    fun `info emits with level INFO and bracketed tag prefix`() = runTest {
        val marker = nextMarker()
        LogManager.i("Foo", marker)
        val ev = firstWithMarker(marker)
        assertEquals("INFO", ev.level)
        assertEquals("[Foo] $marker", ev.message)
        assertTrue("timestamp must be > 0", ev.timestamp > 0L)
    }

    @Test
    fun `debug emits with DEBUG level`() = runTest {
        val marker = nextMarker()
        LogManager.d("T", marker)
        assertEquals("DEBUG", firstWithMarker(marker).level)
    }

    @Test
    fun `warn emits with WARN level`() = runTest {
        val marker = nextMarker()
        LogManager.w("T", marker)
        assertEquals("WARN", firstWithMarker(marker).level)
    }

    @Test
    fun `error emits with ERROR level`() = runTest {
        val marker = nextMarker()
        LogManager.e("T", marker)
        assertEquals("ERROR", firstWithMarker(marker).level)
    }

    @Test
    fun `error with throwable appends the throwable message`() = runTest {
        val marker = nextMarker()
        LogManager.e("Tag", marker, RuntimeException("boom"))
        val ev = firstWithMarker(marker)
        assertEquals("ERROR", ev.level)
        assertTrue(
            "expected throwable msg in '${ev.message}'",
            ev.message.contains(marker) && ev.message.contains("boom"),
        )
    }

    @Test
    fun `formattedTime is HH_mm_ss_SSS shape`() {
        val entry = LogEntry(System.currentTimeMillis(), "INFO", "x")
        assertEquals(12, entry.formattedTime.length)
        assertEquals(':', entry.formattedTime[2])
        assertEquals(':', entry.formattedTime[5])
        assertEquals('.', entry.formattedTime[8])
    }
}
