package com.localllm.app.inference.aicore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the pure / deterministic parts of [AICoreEngine] are testable on the
 * JVM — `checkStatusCode`, `stream`, `complete`, `download`, and
 * `probeAllConfigs` all reach into the AICore system service and require a
 * Pixel device.
 *
 * Status-code constants are wire contract though: clients branch on these
 * integers via the rich error envelope, so locking them down here prevents
 * an accidental renumber.
 */
class AICoreEngineStatusTest {

    @Test
    fun `status code constants match FeatureStatus enum declaration order`() {
        assertEquals(0, AICoreEngine.STATUS_UNAVAILABLE)
        assertEquals(1, AICoreEngine.STATUS_DOWNLOADABLE)
        assertEquals(2, AICoreEngine.STATUS_DOWNLOADING)
        assertEquals(3, AICoreEngine.STATUS_AVAILABLE)
    }

    @Test
    fun `model id is the stable wire identifier`() {
        assertEquals("gemini-nano-aicore", AICoreEngine.MODEL_ID)
    }

    @Test
    fun `statusLabel maps known codes to human strings`() {
        assertEquals("available", AICoreEngine.statusLabel(AICoreEngine.STATUS_AVAILABLE))
        assertEquals("downloadable", AICoreEngine.statusLabel(AICoreEngine.STATUS_DOWNLOADABLE))
        assertEquals("downloading", AICoreEngine.statusLabel(AICoreEngine.STATUS_DOWNLOADING))
        assertEquals("unavailable", AICoreEngine.statusLabel(AICoreEngine.STATUS_UNAVAILABLE))
    }

    @Test
    fun `statusLabel for an unknown code falls back to 'unknown(N)'`() {
        val out = AICoreEngine.statusLabel(99)
        assertTrue("got '$out'", out == "unknown(99)")
    }
}
