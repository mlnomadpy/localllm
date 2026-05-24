package com.localllm.app.embedding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

/**
 * BERT WordPiece is well-defined for greedy longest-prefix matching. We use
 * a tiny hand-crafted vocab whose pieces let us reason about the splits
 * exactly — every special token is present, plus a handful of full words and
 * `##`-prefixed continuations.
 */
class WordPieceTokenizerTest {

    @Rule @JvmField val tmp = TemporaryFolder()

    private fun newTokenizer(): WordPieceTokenizer {
        val vocab = mapOf(
            "[PAD]" to 0,
            "[UNK]" to 1,
            "[CLS]" to 2,
            "[SEP]" to 3,
            "hello" to 4,
            "world" to 5,
            "play" to 6,
            "##ing" to 7,
            "##s" to 8,
            "the" to 9,
            "." to 10,
        )
        return WordPieceTokenizer(vocab, clsId = 2, sepId = 3, padId = 0, unkId = 1)
    }

    @Test
    fun `encode adds CLS prefix and SEP suffix`() {
        val t = newTokenizer()
        val out = t.encode("hello world", maxLen = 8)
        assertEquals(2, out.inputIds[0])              // [CLS]
        assertEquals(4, out.inputIds[1])              // hello
        assertEquals(5, out.inputIds[2])              // world
        assertEquals(3, out.inputIds[3])              // [SEP]
        // remainder is [PAD]
        assertEquals(0, out.inputIds[4])
        assertEquals(0, out.inputIds[7])
        assertEquals(intArrayOf(1, 1, 1, 1, 0, 0, 0, 0).toList(), out.attentionMask.toList())
    }

    @Test
    fun `wordpiece greedily splits unknown suffixes`() {
        val t = newTokenizer()
        val out = t.encode("playing", maxLen = 8)
        // play + ##ing → ids 6 then 7, between CLS and SEP
        assertEquals(2, out.inputIds[0])
        assertEquals(6, out.inputIds[1])
        assertEquals(7, out.inputIds[2])
        assertEquals(3, out.inputIds[3])
    }

    @Test
    fun `oov word becomes UNK`() {
        val t = newTokenizer()
        val out = t.encode("supercalifragilistic", maxLen = 8)
        assertEquals(1, out.inputIds[1]) // [UNK]
    }

    @Test
    fun `punctuation is isolated`() {
        val t = newTokenizer()
        val out = t.encode("hello.world", maxLen = 8)
        // hello, ., world — three real tokens between CLS and SEP
        assertEquals(4, out.inputIds[1]) // hello
        assertEquals(10, out.inputIds[2]) // .
        assertEquals(5, out.inputIds[3]) // world
        assertEquals(3, out.inputIds[4]) // [SEP]
    }

    @Test
    fun `encode truncates to fit CLS plus content plus SEP within maxLen`() {
        val t = newTokenizer()
        // maxLen = 4 → CLS + at most 2 pieces + SEP
        val out = t.encode("hello world the", maxLen = 4)
        assertEquals(2, out.inputIds[0]) // CLS
        assertEquals(4, out.inputIds[1]) // hello
        assertEquals(5, out.inputIds[2]) // world
        assertEquals(3, out.inputIds[3]) // SEP — "the" was dropped
    }

    @Test
    fun `empty input still produces CLS SEP frame`() {
        val t = newTokenizer()
        val out = t.encode("   ", maxLen = 6)
        assertEquals(2, out.inputIds[0]) // CLS
        assertEquals(3, out.inputIds[1]) // SEP (no content between)
        // attention mask only covers CLS + SEP
        assertEquals(intArrayOf(1, 1, 0, 0, 0, 0).toList(), out.attentionMask.toList())
    }

    @Test
    fun `lowercases input before lookup`() {
        val t = newTokenizer()
        val out = t.encode("HELLO World", maxLen = 6)
        assertEquals(4, out.inputIds[1]) // hello, not [UNK]
        assertEquals(5, out.inputIds[2]) // world
    }

    @Test
    fun `fromVocabFile loads tokens in line order`() {
        val vocabFile = File(tmp.root, "vocab.txt").apply {
            writeText(
                listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world").joinToString("\n"),
            )
        }
        val t = WordPieceTokenizer.fromVocabFile(vocabFile.absolutePath)
        val out = t.encode("hello world", maxLen = 6)
        assertEquals(2, out.inputIds[0]) // [CLS] at index 2
        assertEquals(4, out.inputIds[1]) // hello at line 4
        assertEquals(5, out.inputIds[2]) // world at line 5
        assertEquals(3, out.inputIds[3]) // [SEP] at index 3
    }

    @Test
    fun `fromVocabFile errors when a special token is missing`() {
        val vocabFile = File(tmp.root, "vocab.txt").apply {
            writeText(listOf("[PAD]", "[UNK]", "[CLS]", "hello").joinToString("\n"))
        }
        val ex = runCatching {
            WordPieceTokenizer.fromVocabFile(vocabFile.absolutePath)
        }.exceptionOrNull()
        assertTrue("expected an error, got null", ex != null)
        assertTrue(
            "expected message to mention [SEP], got '${ex?.message}'",
            ex?.message?.contains("[SEP]") == true,
        )
    }
}
