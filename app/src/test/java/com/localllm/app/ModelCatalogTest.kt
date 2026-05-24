package com.localllm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `Gemma 4 entries are not SoC-gated`() {
        val gemma4 = AVAILABLE_MODELS.first { it.id == "gemma-4-e2b" }
        assertNull(gemma4.requiredSocMarker)
        assertNull(gemma4.npuSocLabel())
        // Non-NPU models always match the current device.
        assertTrue(gemma4.matchesCurrentSoc())
    }

    @Test
    fun `Gemma 4 entries declare LITERT_CPU backend`() {
        val ids = listOf("gemma-4-e2b", "gemma-4-e4b")
        for (id in ids) {
            val m = AVAILABLE_MODELS.first { it.id == id }
            assertEquals("$id should be LITERT_CPU", Backend.LITERT_CPU, m.backend)
        }
    }

    @Test
    fun `AICore virtual entry exists and is the only AICORE backend`() {
        val ai = AVAILABLE_MODELS.first { it.id == "gemini-nano-aicore" }
        assertEquals(Backend.AICORE, ai.backend)
        // Virtual: no file on disk, no URL to download.
        assertEquals("", ai.url)
        assertEquals("", ai.filename)
        val aicoreCount = AVAILABLE_MODELS.count { it.backend == Backend.AICORE }
        assertEquals(1, aicoreCount)
    }

    @Test
    fun `every advertised NPU model declares LITERT_NPU backend with a human label`() {
        val npu = AVAILABLE_MODELS.filter { it.requiredSocMarker != null }
        // 4 Snapdragon + 3 MediaTek + 1 Tensor G5.
        assertEquals(8, npu.size)
        for (m in npu) {
            assertEquals("${m.id} should be LITERT_NPU", Backend.LITERT_NPU, m.backend)
            val label = m.npuSocLabel()
            assertNotNull("missing label for ${m.id}", label)
            // SoC marker should appear (uppercase) in the human label so a
            // power-user can still cross-reference Build.SOC_MODEL — except
            // for laguna which renders as "Tensor G5 (Pixel 10)".
            val marker = m.requiredSocMarker!!.uppercase()
            if (marker != "LAGUNA") {
                assertTrue("label '$label' must contain '$marker'", label!!.contains(marker))
            }
        }
    }

    @Test
    fun `NPU filenames are stable and unique`() {
        val npuFilenames = AVAILABLE_MODELS
            .filter { it.requiredSocMarker != null }
            .map { it.filename }
        assertEquals(npuFilenames.size, npuFilenames.toSet().size)
        npuFilenames.forEach { fn ->
            assertTrue("$fn should end in .litertlm", fn.endsWith(".litertlm"))
            assertTrue("$fn should signal NPU", fn.contains("npu"))
        }
    }

    @Test
    fun `NPU URLs point at the litert-community Gemma3-1B-IT repo`() {
        val npu = AVAILABLE_MODELS.filter { it.requiredSocMarker != null }
        for (m in npu) {
            val expectedPrefix =
                "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/"
            assertTrue(
                "URL for ${m.id} should start with $expectedPrefix but was ${m.url}",
                m.url.startsWith(expectedPrefix)
            )
            // The on-disk filename diverges from the upstream filename (we
            // namespace ours with `npu-` for clarity), but the upstream
            // filename in the URL must still carry the SoC marker. The
            // Tensor G5 variant is the exception: upstream names it
            // `Google_Tensor_G5` rather than the codename `laguna`.
            if (m.requiredSocMarker != "laguna") {
                assertTrue(
                    "upstream URL for ${m.id} must reference SoC ${m.requiredSocMarker}",
                    m.url.contains(m.requiredSocMarker!!)
                )
            } else {
                assertTrue(
                    "Tensor G5 URL must reference Google_Tensor_G5",
                    m.url.contains("Google_Tensor_G5")
                )
            }
        }
    }
}
