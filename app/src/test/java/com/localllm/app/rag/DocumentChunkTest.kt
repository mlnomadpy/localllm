package com.localllm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The default `data class` equals/hashCode on FloatArray fields uses
 * reference identity, which breaks tests + downstream consumers. The
 * overrides on [DocumentChunk] must compare arrays by content.
 */
class DocumentChunkTest {

    private fun chunk(
        id: Long = 0,
        tenantId: String = "t",
        documentId: String = "d",
        chunkIndex: Int = 0,
        text: String = "t",
        metadata: String? = null,
        embeddingModel: String = "m",
        embedding: FloatArray = floatArrayOf(0.1f, 0.2f, 0.3f),
    ) = DocumentChunk(id, tenantId, documentId, chunkIndex, text, metadata, embeddingModel, embedding)

    @Test
    fun `equal field-by-field chunks compare equal even with distinct float arrays`() {
        val a = chunk(embedding = floatArrayOf(0.1f, 0.2f, 0.3f))
        val b = chunk(embedding = floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different embedding contents are not equal`() {
        val a = chunk(embedding = floatArrayOf(0.1f, 0.2f, 0.3f))
        val b = chunk(embedding = floatArrayOf(0.1f, 0.2f, 0.4f))
        assertNotEquals(a, b)
    }

    @Test
    fun `different text is not equal`() {
        assertNotEquals(chunk(text = "alpha"), chunk(text = "beta"))
    }

    @Test
    fun `different tenant scopes are not equal`() {
        assertNotEquals(chunk(tenantId = "alice"), chunk(tenantId = "bob"))
    }

    @Test
    fun `null vs non-null metadata distinguishes chunks`() {
        assertNotEquals(chunk(metadata = null), chunk(metadata = "{}"))
    }
}
