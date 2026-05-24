package com.localllm.app.inference

import com.localllm.app.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EngineKeyTest {

    @Test
    fun `asString uses 'model' sentinel when maxTokens is null`() {
        val k = EngineKey("gemma-4-e2b", maxTokens = null, backend = Backend.LITERT_CPU)
        assertEquals("gemma-4-e2b_model_LITERT_CPU", k.asString())
    }

    @Test
    fun `asString embeds explicit maxTokens`() {
        val k = EngineKey("gemma-4-e2b", maxTokens = 2048, backend = Backend.LITERT_GPU)
        assertEquals("gemma-4-e2b_2048_LITERT_GPU", k.asString())
    }

    @Test
    fun `data-class equality keys identical specs to the same bucket`() {
        val a = EngineKey("m", 1024, Backend.LITERT_NPU)
        val b = EngineKey("m", 1024, Backend.LITERT_NPU)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different maxTokens means a different cache key`() {
        val a = EngineKey("m", 1024, Backend.LITERT_CPU)
        val b = EngineKey("m", 2048, Backend.LITERT_CPU)
        assertNotEquals(a, b)
    }

    @Test
    fun `different backend means a different cache key`() {
        val a = EngineKey("m", 1024, Backend.LITERT_CPU)
        val b = EngineKey("m", 1024, Backend.LITERT_GPU)
        assertNotEquals(a, b)
    }
}
