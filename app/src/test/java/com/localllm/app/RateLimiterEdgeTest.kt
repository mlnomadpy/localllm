package com.localllm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases complementing [RateLimiterTest]:
 *   - Bursts greater than 1 still grant the first request when rate is 0.
 *   - rate=0 exhausts the bucket permanently after burst tokens are spent
 *     (Retry-After saturates at Long.MAX_VALUE — infinite wait).
 *   - Separate client identities don't share state.
 *   - Multiple refill ticks compound monotonically — a later call sees at
 *     least as many tokens as an earlier one at the same elapsed time.
 */
class RateLimiterEdgeTest {

    @Test
    fun `rate zero still grants the burst capacity on first calls`() {
        val rl = RateLimiter(ratePerSec = 0.0, burst = 3.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("c", t0))
        assertNull(rl.tryAcquire("c", t0))
        assertNull(rl.tryAcquire("c", t0))
        // 4th request fails — with rate=0 the wait is infinite.
        val wait = rl.tryAcquire("c", t0)
        assertNotNull(wait)
        assertEquals("rate=0 must saturate Retry-After to Long.MAX_VALUE", Long.MAX_VALUE, wait)
    }

    @Test
    fun `rate zero never refills even far in the future`() {
        val rl = RateLimiter(ratePerSec = 0.0, burst = 1.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("c", t0))
        // A full hour later — still no token, still infinite wait.
        val later = t0 + 3_600L * 1_000_000_000L
        val wait = rl.tryAcquire("c", later)
        assertNotNull(wait)
        assertEquals(Long.MAX_VALUE, wait)
    }

    @Test
    fun `separate clients keep independent buckets`() {
        val rl = RateLimiter(ratePerSec = 1.0, burst = 1.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("alpha", t0))
        assertNull(rl.tryAcquire("beta", t0))
        assertNull(rl.tryAcquire("gamma", t0))
        // Each has now exhausted its single token, independently.
        assertNotNull(rl.tryAcquire("alpha", t0))
        assertNotNull(rl.tryAcquire("beta", t0))
        assertNotNull(rl.tryAcquire("gamma", t0))
    }

    @Test
    fun `refill is monotonic across closely spaced calls`() {
        // With ratePerSec=10 and burst=10, after exhausting the bucket at t0
        // each subsequent call at a later time must see >= tokens than the
        // previous call at an earlier time. We assert this by interleaving
        // successful acquires with strictly-increasing timestamps.
        val rl = RateLimiter(ratePerSec = 10.0, burst = 10.0)
        var t = 0L
        // Drain the burst.
        repeat(10) { assertNull(rl.tryAcquire("c", t)) }
        // The next call at t is rejected.
        assertNotNull(rl.tryAcquire("c", t))
        // Advance one full second — exactly 10 tokens should have refilled.
        t += 1_000_000_000L
        repeat(10) { i ->
            assertNull("token #$i after refill should be granted", rl.tryAcquire("c", t))
        }
        assertNotNull("11th token in same refill window must wait", rl.tryAcquire("c", t))
    }

    @Test
    fun `bucket caps at burst capacity even after long idle`() {
        val rl = RateLimiter(ratePerSec = 5.0, burst = 2.0)
        val t0 = 0L
        // Drain to empty.
        assertNull(rl.tryAcquire("c", t0))
        assertNull(rl.tryAcquire("c", t0))
        assertNotNull(rl.tryAcquire("c", t0))
        // Wait ten seconds — 50 tokens would have refilled in principle but
        // burst caps it at 2.
        val t1 = t0 + 10L * 1_000_000_000L
        assertNull(rl.tryAcquire("c", t1))
        assertNull(rl.tryAcquire("c", t1))
        // Third call at same instant must wait — burst was 2.
        assertNotNull(rl.tryAcquire("c", t1))
    }

    @Test
    fun `tryAcquire uses System nanoTime when called without timestamp`() {
        // Smoke-check the default-argument path. With a generous burst the
        // first call should succeed regardless of the live wall-clock value.
        val rl = RateLimiter(ratePerSec = 1.0, burst = 5.0)
        assertNull(rl.tryAcquire("default-client"))
    }

    @Test
    fun `reset wipes per-client state for every client`() {
        val rl = RateLimiter(ratePerSec = 1.0, burst = 1.0)
        val t0 = 0L
        assertNull(rl.tryAcquire("a", t0))
        assertNull(rl.tryAcquire("b", t0))
        // Both exhausted.
        assertNotNull(rl.tryAcquire("a", t0))
        assertNotNull(rl.tryAcquire("b", t0))
        rl.reset()
        // Fresh buckets for everyone.
        assertNull(rl.tryAcquire("a", t0))
        assertNull(rl.tryAcquire("b", t0))
    }
}
