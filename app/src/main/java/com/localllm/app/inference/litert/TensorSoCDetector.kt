package com.localllm.app.inference.litert

import android.os.Build

/**
 * SoC detection helpers. Tensor-family quirks no longer drive backend
 * selection (that's catalog-declared now) — they only drive:
 *   - the JNI primer workaround inside [LiteRtEngineBuilder] for stuck
 *     LiteRT-LM CPU/GPU initialization on Pixel 6+;
 *   - catalog filtering by `requiredSocMarker` in the UI.
 *
 * `Build.SOC_MODEL` is API 31+. Older devices return `false` from
 * [isTensorSoc]; the Tensor family launched on Android 12 so this is correct
 * by construction.
 */
object TensorSoCDetector {
    fun isTensorSoc(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val soc = Build.SOC_MODEL?.lowercase() ?: return false
        // Pixels report the SoC codename via ro.soc.model, not the marketing
        // name. Tensor G1..G4 → GS101/GS201/ZUMA/ZUMA_PRO; G5 → LAGUNA.
        // Verified LAGUNA on a Pixel 10 device.
        if (soc.contains("tensor")) return true
        return soc.startsWith("gs10") ||
            soc.startsWith("gs20") ||
            soc == "zuma" ||
            soc == "zuma_pro" ||
            soc == "laguna"
    }
}
