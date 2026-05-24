package com.localllm.app.inference

import com.localllm.app.inference.aicore.AICoreEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoreNotReadyExceptionTest {

    @Test
    fun `carries the status code verbatim`() {
        val ex = AiCoreNotReadyException(AICoreEngine.STATUS_DOWNLOADABLE)
        assertEquals(AICoreEngine.STATUS_DOWNLOADABLE, ex.statusCode)
    }

    @Test
    fun `default probeError is null`() {
        val ex = AiCoreNotReadyException(AICoreEngine.STATUS_DOWNLOADING)
        assertNull(ex.probeError)
        assertNull(ex.cause)
    }

    @Test
    fun `probe error becomes the cause chain`() {
        val cause = IllegalStateException("ErrorCode -101")
        val ex = AiCoreNotReadyException(AICoreEngine.STATUS_UNAVAILABLE, cause)
        assertSame(cause, ex.probeError)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `message embeds the status code so logs are searchable`() {
        val ex = AiCoreNotReadyException(42)
        assertTrue(
            "expected status in '${ex.message}'",
            ex.message?.contains("status=42") == true,
        )
    }
}
