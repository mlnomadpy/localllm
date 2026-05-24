package com.localllm.app.inference.litert

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Tensor detection drives the JNI-primer workaround in
 * [LiteRtEngineBuilder.primeTensorJniState]. We swap [Build.SOC_MODEL] via
 * Robolectric reflection so we can exercise every codename without needing
 * a hardware fleet.
 */
@RunWith(RobolectricTestRunner::class)
class TensorSoCDetectorTest {

    private fun setSoc(model: String?) {
        ReflectionHelpers.setStaticField(Build::class.java, "SOC_MODEL", model)
    }

    @Test
    fun `tensor literal substring matches`() {
        setSoc("Google Tensor G3")
        assertTrue(TensorSoCDetector.isTensorSoc())
    }

    @Test
    fun `tensor G1 G2 codenames (gs10x, gs20x) match`() {
        setSoc("gs101"); assertTrue(TensorSoCDetector.isTensorSoc())
        setSoc("gs201"); assertTrue(TensorSoCDetector.isTensorSoc())
    }

    @Test
    fun `zuma family codenames match`() {
        setSoc("zuma"); assertTrue(TensorSoCDetector.isTensorSoc())
        setSoc("zuma_pro"); assertTrue(TensorSoCDetector.isTensorSoc())
    }

    @Test
    fun `Pixel 10 laguna codename matches`() {
        setSoc("LAGUNA")
        assertTrue(TensorSoCDetector.isTensorSoc())
    }

    @Test
    fun `non-tensor codenames return false`() {
        setSoc("SM8750")
        assertFalse(TensorSoCDetector.isTensorSoc())
        setSoc("MT6989")
        assertFalse(TensorSoCDetector.isTensorSoc())
    }

    @Test
    fun `null soc model returns false`() {
        setSoc(null)
        assertFalse(TensorSoCDetector.isTensorSoc())
    }
}
