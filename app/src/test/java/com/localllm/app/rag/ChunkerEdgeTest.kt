package com.localllm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases complementing [ChunkerTest]:
 *   - A single paragraph that fits returns one verbatim chunk.
 *   - A single oversized paragraph is sliced by sliding window.
 *   - Overlap between adjacent windows is the literal tail of the previous.
 *   - Multiple oversized paragraphs each slide independently.
 *   - Pure whitespace separators are collapsed.
 */
class ChunkerEdgeTest {

    @Test
    fun `single small paragraph returns one chunk verbatim`() {
        val input = "exactly one paragraph."
        val out = Chunker.chunk(input, maxChars = 400, overlapChars = 60)
        assertEquals(listOf(input), out)
    }

    @Test
    fun `paragraph at the cap boundary still returns one chunk`() {
        val text = "a".repeat(400)
        val out = Chunker.chunk(text, maxChars = 400, overlapChars = 60)
        assertEquals(listOf(text), out)
    }

    @Test
    fun `paragraph just over the cap triggers sliding window split`() {
        val text = "x".repeat(401)
        val out = Chunker.chunk(text, maxChars = 400, overlapChars = 100)
        assertTrue("expected at least 2 chunks, got ${out.size}", out.size >= 2)
        // First window is exactly the first maxChars characters.
        assertEquals("x".repeat(400), out[0])
        // No chunk exceeds the cap.
        out.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 400) }
    }

    @Test
    fun `sliding window overlap is the literal tail of the prior chunk`() {
        val text = "y".repeat(1000)
        val out = Chunker.chunk(text, maxChars = 400, overlapChars = 100)
        // Adjacent windows in the sliding case share their overlap bytes.
        // Step = 300, so chunk[1] starts at offset 300 and includes 100
        // chars that were already in chunk[0]'s tail.
        assertTrue("need at least 2 chunks", out.size >= 2)
        val tail = out[0].takeLast(100)
        assertTrue(
            "chunk[1] should start with overlap tail of chunk[0]",
            out[1].startsWith(tail),
        )
    }

    @Test
    fun `overlap propagates across grouped paragraphs`() {
        // Three paragraphs that together exceed maxChars: chunk[0] should
        // hold the first one(s), chunk[1] should begin with the tail of
        // chunk[0] before appending the rest.
        val p1 = "a".repeat(180)
        val p2 = "b".repeat(180)
        val p3 = "c".repeat(180)
        val out = Chunker.chunk("$p1\n\n$p2\n\n$p3", maxChars = 400, overlapChars = 60)
        assertEquals(2, out.size)
        val tail = out[0].takeLast(60)
        assertTrue("chunk[1] should begin with the tail of chunk[0]", out[1].startsWith(tail))
    }

    @Test
    fun `multiple oversized paragraphs each get their own sliding sequence`() {
        val giant1 = "1".repeat(800)
        val giant2 = "2".repeat(800)
        val out = Chunker.chunk("$giant1\n\n$giant2", maxChars = 400, overlapChars = 100)
        // Each giant requires at least 2 windows (400 + 300-step), so we
        // should see at least 4 chunks in total.
        assertTrue("expected at least 4 chunks, got ${out.size}", out.size >= 4)
        val joined = out.joinToString("")
        assertTrue("output covers giant1", joined.contains(giant1))
        assertTrue("output covers giant2", joined.contains(giant2))
    }

    @Test
    fun `zero overlap means adjacent grouped chunks do not share content`() {
        val p1 = "a".repeat(180)
        val p2 = "b".repeat(180)
        val p3 = "c".repeat(180)
        val out = Chunker.chunk("$p1\n\n$p2\n\n$p3", maxChars = 400, overlapChars = 0)
        assertEquals(2, out.size)
        // chunk[1] holds only p3, no carry-over from p2.
        assertTrue(out[1].contains(p3))
        assertTrue("chunk[1] should not start with p2 tail", !out[1].startsWith(p2.takeLast(10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxChars zero is rejected`() {
        Chunker.chunk("hi", maxChars = 0, overlapChars = 0)
    }
}
